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
    private final ObjectMapper objectMapper;

    /** 企业活跃连接池：dataSourceId → ActiveConnection */
    private final Map<String, ConnectionService.ActiveConnection> active = new ConcurrentHashMap<>();

    public DataSourceService(DataSourceRepository repository, DriverFactory driverFactory,
                            DriverRegistry driverRegistry, CredentialService credentialService,
                            ObjectMapper objectMapper) {
        this.repository = repository;
        this.driverFactory = driverFactory;
        this.driverRegistry = driverRegistry;
        this.credentialService = credentialService;
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
    public DataSource update(String id, String displayName, String description, Map<String, Object> params) {
        DataSource ds = getEntity(id);
        if (displayName != null) ds.setDisplayName(displayName);
        if (description != null) ds.setDescription(description);
        if (params != null) {
            Map<String, Object> existing = parseParams(ds.getConnectionParamsJson());
            Map<String, Object> merged = new HashMap<>(existing);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (e.getValue() != null && !e.getValue().toString().equals("********")) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            ds.setConnectionParamsJson(toJson(credentialService.encryptSensitiveFields(merged)));
            // 参数变更后断开旧连接
            disconnect(id);
        }
        return repository.save(ds);
    }

    public void delete(String id) {
        disconnect(id);
        repository.deleteById(id);
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
            result.put("message", "连接失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 解析授权 → 取得活跃连接（缓存）。仅企业查询服务内部调用。
     */
    public ConnectionService.ActiveConnection resolveActiveConnection(String dataSourceId) {
        ConnectionService.ActiveConnection cached = active.get(dataSourceId);
        if (cached != null) return cached;
        DataSource ds = getEntity(dataSourceId);
        try {
            Map<String, Object> params = credentialService.decryptSensitiveFields(parseParams(ds.getConnectionParamsJson()));
            DatabaseDriver driver = driverFactory.getOrCreateDriver(dataSourceId, ds.getDbType());
            Object conn = driver.connect(params);
            ConnectionService.ActiveConnection ac = new ConnectionService.ActiveConnection(driver, conn);
            active.put(dataSourceId, ac);
            log.info("企业数据源已连接: {} ({})", ds.getDisplayName(), ds.getDbType());
            return ac;
        } catch (Exception e) {
            throw new RuntimeException("连接数据源失败: " + e.getMessage(), e);
        }
    }

    public void disconnect(String dataSourceId) {
        ConnectionService.ActiveConnection ac = active.remove(dataSourceId);
        if (ac != null) {
            try { ac.driver.disconnect(ac.connection); } catch (Exception ignored) {}
        }
    }

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
            log.error("解析数据源参数失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String toJson(Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            return "{}";
        }
    }
}
