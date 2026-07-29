


package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.driver.DriverFactory;
import io.github.lexaquila.lyradb.driver.DriverRegistry;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.PreDestroy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实数据源服务（管理员持有，连接信息加密；用户不可见）
 *
 * <p>关键：列表/读取均返回**掩码**后的参数（密码等显示 ********），明文仅在连接时解密、内存短暂存在。</p>
 */
@Service
public class DataSourceService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceService.class);

    private final DataSourceRepository repository;
    private final DriverFactory driverFactory;
    private final DriverRegistry driverRegistry;
    private final CredentialService credentialService;
    private final ApprovalSecurityContextService approvalSecurityContextService;
    private final ObjectMapper objectMapper;

    /** 企业活跃连接池同时绑定持久化连接配置指纹，禁止复用更新前的旧连接。 */
    private final Map<String, CachedActiveConnection> active = new ConcurrentHashMap<>();

    public DataSourceService(DataSourceRepository repository, DriverFactory driverFactory,
                            DriverRegistry driverRegistry, CredentialService credentialService,
                            ApprovalSecurityContextService approvalSecurityContextService,
                            ObjectMapper objectMapper) {
        this.repository = repository;
        this.driverFactory = driverFactory;
        this.driverRegistry = driverRegistry;
        this.credentialService = credentialService;
        this.approvalSecurityContextService = approvalSecurityContextService;
        this.objectMapper = objectMapper;
    }

    /** 创建数据源（敏感字段加密入库） */
    public DataSource create(String workspaceId, String dbType, String displayName,
                             Map<String, Object> params, String description, String createdBy) {
        DataSource ds = new DataSource();
        ds.setWorkspaceId(workspaceId);
        ds.setDbType(dbType != null ? dbType.toUpperCase() : null);
        DriverInfo info = driverRegistry.getDriverInfo(dbType);
        ds.setDisplayName(displayName != null ? displayName : (info != null ? info.getDisplayName() : dbType));
        ds.setDescription(description);
        ds.setCreatedBy(createdBy);
        Map<String, Object> enc = credentialService.encryptSensitiveFields(params);
        ds.setConnectionParamsJson(toJson(enc));
        return repository.save(ds);
    }

    /** 列出（参数掩码） */
    public List<Map<String, Object>> listMasked(String workspaceId) {
        List<DataSource> list = (workspaceId != null && !workspaceId.isEmpty())
                ? repository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId)
                : repository.findAll();
        List<Map<String, Object>> out = new ArrayList<>();
        for (DataSource ds : list) {
            out.add(toMaskedView(ds));
        }
        return out;
    }

    /** 单个（参数掩码） */
    public Map<String, Object> getMasked(String id) {
        return toMaskedView(repository.findById(id)
                .orElseThrow(() -> new RuntimeException("数据源不存在: " + id)));
    }

    public DataSource getEntity(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("数据源不存在: " + id));
    }

    /** 更新（掩码 ******** 保留原值） */
    @Transactional
    public synchronized DataSource update(String id, String displayName, String description,
                                          Map<String, Object> params) {
        DataSource ds = getEntity(id);
        if (params != null) {
            approvalSecurityContextService.invalidateForDataSource(ds.getWorkspaceId(), id);
        }
        if (displayName != null) ds.setDisplayName(displayName);
        if (description != null) ds.setDescription(description);
        if (params != null) {
            Map<String, Object> existing = parseParams(ds.getConnectionParamsJson());
            Map<String, Object> merged = new HashMap<>(existing);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (e.getValue() != null
                        && !e.getValue().toString().equals(CredentialService.MASKED_VALUE)) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            ds.setConnectionParamsJson(toJson(credentialService.encryptSensitiveFields(merged)));
            // 参数变更后断开旧连接；事务完成时再次清理并发窗口中新建的旧配置连接。
            disconnect(id);
            disconnectAfterTransaction(id);
        }
        return repository.save(ds);
    }

    @Transactional
    public synchronized void delete(String id) {
        DataSource dataSource = getEntity(id);
        approvalSecurityContextService.invalidateForDataSource(
                dataSource.getWorkspaceId(), id);
        disconnect(id);
        repository.deleteById(id);
        disconnectAfterTransaction(id);
    }

    /** 测试连接（不保存） */
    public Map<String, Object> test(String id) {
        DataSource ds = getEntity(id);
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> params = credentialService.decryptSensitiveFields(parseParams(ds.getConnectionParamsJson()));
            DatabaseDriver driver = driverFactory.createDriver(ds.getDbType());
            boolean ok = driver.testConnection(params);
            result.put("success", ok);
            result.put("message", ok ? "连接成功" : "连接失败");
        } catch (Exception e) {
            log.warn("测试数据源连接失败: {} - {}", id, e.getMessage());
            result.put("success", false);
            result.put("message", "连接失败，请检查地址、网络和凭证");
        }
        return result;
    }

    /**
     * 解析授权 → 取得活跃连接（缓存）。仅企业查询服务内部调用。
     */
    public synchronized ConnectionService.ActiveConnection resolveActiveConnection(String dataSourceId) {
        DataSource ds = getEntity(dataSourceId);
        String fingerprint = connectionFingerprint(ds);
        CachedActiveConnection cached = active.get(dataSourceId);
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            return cached.connection();
        }
        if (cached != null) {
            disconnect(dataSourceId);
        }
        try {
            Map<String, Object> params = credentialService.decryptSensitiveFields(parseParams(ds.getConnectionParamsJson()));
            DatabaseDriver driver = driverFactory.getOrCreateDriver(dataSourceId, ds.getDbType());
            Object conn = driver.connect(params);
            ConnectionService.ActiveConnection ac = new ConnectionService.ActiveConnection(driver, conn);
            active.put(dataSourceId, new CachedActiveConnection(ac, fingerprint));
            log.info("企业数据源已连接: {} ({})", ds.getDisplayName(), ds.getDbType());
            return ac;
        } catch (Exception e) {
            throw new IllegalStateException("连接数据源失败", e);
        }
    }

    public synchronized void disconnect(String dataSourceId) {
        CachedActiveConnection cached = active.get(dataSourceId);
        if (cached == null) {
            return;
        }
        ConnectionService.ActiveConnection ac = cached.connection();
        try (ConnectionService.ActiveConnection.Lease ignored = ac.acquire()) {
            if (active.remove(dataSourceId, cached)) {
                ac.markClosed();
                try {
                    ac.driver.disconnect(ac.connection);
                } catch (Exception e) {
                    log.warn("断开企业数据源失败: {} - {}", dataSourceId, e.getMessage());
                }
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        new ArrayList<>(active.keySet()).forEach(this::disconnect);
    }

    private void disconnectAfterTransaction(String dataSourceId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            disconnect(dataSourceId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        disconnect(dataSourceId);
                    }
                });
    }

    private static String connectionFingerprint(DataSource dataSource) {
        try {
            String value = dataSource.getWorkspaceId() + "\u0000"
                    + dataSource.getDbType() + "\u0000"
                    + dataSource.getConnectionParamsJson();
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算数据源连接配置指纹", exception);
        }
    }

    private record CachedActiveConnection(
            ConnectionService.ActiveConnection connection,
            String fingerprint) { }

    private Map<String, Object> toMaskedView(DataSource ds) {
        Map<String, Object> view = new HashMap<>();
        view.put("id", ds.getId());
        view.put("workspaceId", ds.getWorkspaceId());
        view.put("dbType", ds.getDbType());
        view.put("displayName", ds.getDisplayName());
        view.put("description", ds.getDescription());
        view.put("params", credentialService.maskSensitiveFields(parseParams(ds.getConnectionParamsJson())));
        view.put("createdBy", ds.getCreatedBy());
        view.put("createdAt", ds.getCreatedAt());
        return view;
    }

    private Map<String, Object> parseParams(String json) {
        if (json == null || json.isEmpty()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("数据源参数存储格式损坏", e);
        }
    }

    private String toJson(Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new IllegalStateException("数据源参数序列化失败", e);
        }
    }
}
