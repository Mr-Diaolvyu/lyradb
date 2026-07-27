package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.driver.DriverFactory;
import io.github.lexaquila.lyradb.driver.DriverRegistry;
import io.github.lexaquila.lyradb.model.dto.ConnectionDTO;
import io.github.lexaquila.lyradb.model.entity.ConnectionConfig;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import io.github.lexaquila.lyradb.repository.ConnectionConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接管理服务
 *
 * <p>
 * 负责数据库连接的完整生命周期管理：
 * </p>
 * <ul>
 * <li>CRUD: 保存/更新/删除/查询连接配置</li>
 * <li>连接池: 维护活跃连接（connectionId → Driver + 连接对象）</li>
 * <li>测试连接: 验证连接参数是否有效</li>
 * <li>连接/断开: 建立和释放数据库连接</li>
 * </ul>
 *
 * <p>
 * 凭证安全：密码/AK-SK在保存时加密，使用时解密，返回前端时掩码。
 * </p>
 */
@Service
public class ConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);

    private final ConnectionConfigRepository repository;
    private final DriverFactory driverFactory;
    private final DriverRegistry driverRegistry;
    private final CredentialService credentialService;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final SshTunnelService sshTunnelService;

    /** 活跃连接池：connectionId → ActiveConnection */
    private final Map<String, ActiveConnection> activeConnections = new ConcurrentHashMap<>();

    public ConnectionService(
            ConnectionConfigRepository repository,
            DriverFactory driverFactory,
            DriverRegistry driverRegistry,
            CredentialService credentialService,
            ObjectMapper objectMapper,
            AppProperties appProperties,
            SshTunnelService sshTunnelService) {
        this.repository = repository;
        this.driverFactory = driverFactory;
        this.driverRegistry = driverRegistry;
        this.credentialService = credentialService;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.sshTunnelService = sshTunnelService;
    }

    /**
     * 获取所有连接配置列表
     */
    public List<ConnectionDTO> listConnections() {
        List<ConnectionConfig> configs = repository.findAllByOrderByCreatedAtAsc();
        List<ConnectionDTO> dtos = new ArrayList<>();

        for (ConnectionConfig config : configs) {
            Map<String, Object> params = parseParams(config.getConnectionParamsJson());
            params = credentialService.maskSensitiveFields(params);
            ConnectionDTO dto = ConnectionDTO.fromEntity(config, params);
            dto.setStatus(activeConnections.containsKey(config.getId()) ? "CONNECTED" : "DISCONNECTED");
            dtos.add(dto);
        }

        // Sort: favorites first, then by sortOrder, then by createdAt
        dtos.sort((a, b) -> {
            boolean aFav = Boolean.TRUE.equals(a.getFavorite());
            boolean bFav = Boolean.TRUE.equals(b.getFavorite());
            if (aFav != bFav)
                return aFav ? -1 : 1;
            int aOrder = a.getSortOrder() != null ? a.getSortOrder() : 0;
            int bOrder = b.getSortOrder() != null ? b.getSortOrder() : 0;
            if (aOrder != bOrder)
                return Integer.compare(aOrder, bOrder);
            return a.getCreatedAt() != null && b.getCreatedAt() != null
                    ? a.getCreatedAt().compareTo(b.getCreatedAt())
                    : 0;
        });

        return dtos;
    }

    /**
     * 获取单个连接配置
     */
    public ConnectionDTO getConnection(String id) {
        ConnectionConfig config = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("连接不存在: " + id));

        Map<String, Object> params = parseParams(config.getConnectionParamsJson());
        params = credentialService.maskSensitiveFields(params);
        ConnectionDTO dto = ConnectionDTO.fromEntity(config, params);
        dto.setStatus(activeConnections.containsKey(id) ? "CONNECTED" : "DISCONNECTED");
        return dto;
    }

    /**
     * 创建连接配置
     */
    public ConnectionDTO createConnection(ConnectionDTO dto) {
        ConnectionConfig config = new ConnectionConfig();
        config.setName(dto.getName());
        config.setDbType(dto.getDbType());

        DriverInfo driverInfo = driverRegistry.getDriverInfo(dto.getDbType());
        config.setDisplayName(driverInfo.getDisplayName());

        // 加密敏感字段
        Map<String, Object> encryptedParams = credentialService.encryptSensitiveFields(dto.getParams());
        config.setConnectionParamsJson(toJsonString(encryptedParams));

        config.setGroup(dto.getGroup());
        config.setColor(dto.getColor());
        config.setDescription(dto.getDescription());
        config.setTags(dto.getTags() != null ? String.join(",", dto.getTags()) : null);
        config.setFavorite(dto.getFavorite() != null ? dto.getFavorite() : false);
        config.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
        config.setAutoConnect(dto.getAutoConnect() != null ? dto.getAutoConnect() : false);

        config = repository.save(config);
        log.info("创建连接: {} ({})", config.getName(), config.getDbType());

        Map<String, Object> maskedParams = credentialService.maskSensitiveFields(encryptedParams);
        return ConnectionDTO.fromEntity(config, maskedParams);
    }

    /**
     * 更新连接配置
     */
    public ConnectionDTO updateConnection(String id, ConnectionDTO dto) {
        ConnectionConfig config = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("连接不存在: " + id));

        config.setName(dto.getName());
        if (dto.getDbType() != null) {
            config.setDbType(dto.getDbType());
            DriverInfo driverInfo = driverRegistry.getDriverInfo(dto.getDbType());
            config.setDisplayName(driverInfo.getDisplayName());
        }

        // 更新参数（只有非掩码的密码才会被更新）
        Map<String, Object> existingParams = parseParams(config.getConnectionParamsJson());
        Map<String, Object> newParams = new HashMap<>(existingParams);

        for (Map.Entry<String, Object> entry : dto.getParams().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            // 如果密码是掩码值，保留原密码
            if (value != null && !value.toString().equals("********")) {
                newParams.put(key, value);
            }
        }

        // 加密敏感字段
        Map<String, Object> encryptedParams = credentialService.encryptSensitiveFields(newParams);
        config.setConnectionParamsJson(toJsonString(encryptedParams));

        config.setGroup(dto.getGroup());
        config.setColor(dto.getColor());
        config.setDescription(dto.getDescription());
        config.setTags(dto.getTags() != null ? String.join(",", dto.getTags()) : null);
        config.setFavorite(dto.getFavorite());
        config.setSortOrder(dto.getSortOrder());
        config.setAutoConnect(dto.getAutoConnect());

        config = repository.save(config);
        log.info("更新连接: {}", config.getName());

        Map<String, Object> maskedParams = credentialService.maskSensitiveFields(encryptedParams);
        return ConnectionDTO.fromEntity(config, maskedParams);
    }

    /**
     * 删除连接配置
     */
    public void deleteConnection(String id) {
        // 先断开活跃连接
        disconnect(id);

        repository.deleteById(id);
        log.info("删除连接: {}", id);
    }

    /**
     * 测试连接（不保存配置）
     */
    public Map<String, Object> testConnection(String dbType, Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        try {
            DatabaseDriver driver = driverFactory.createDriver(dbType);
            boolean success = driver.testConnection(params);
            result.put("success", success);
            if (success) {
                result.put("message", "连接成功");
            } else {
                result.put("message", "连接失败");
            }
        } catch (Exception e) {
            log.error("测试连接失败: {} - {}", dbType, e.getMessage());
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 建立数据库连接
     */
    public Map<String, Object> connect(String connectionId) {
        Map<String, Object> result = new HashMap<>();

        // 如果已连接，直接返回
        if (activeConnections.containsKey(connectionId)) {
            result.put("success", true);
            result.put("message", "已经连接");
            return result;
        }

        try {
            ConnectionConfig config = repository.findById(connectionId)
                    .orElseThrow(() -> new RuntimeException("连接不存在: " + connectionId));

            Map<String, Object> params = parseParams(config.getConnectionParamsJson());
            params = credentialService.decryptSensitiveFields(params);

            // 单库连接数上限校验
            enforcePoolLimit(config.getDbType());

            // SSH 隧道（若配置了 sshHost）：将目标库 host:port 转发到本地
            Object sshTunnel = null;
            Map<String, Object> connParams = params;
            String sshHost = strParam(params, "sshHost");
            if (sshHost != null && !sshHost.isEmpty()) {
                int sshPort = intParam(params, "sshPort", 22);
                String sshUser = strParam(params, "sshUser");
                String sshPassword = strParam(params, "sshPassword");
                String sshPrivateKey = strParam(params, "sshPrivateKey");
                String sshPassphrase = strParam(params, "sshPassphrase");
                String dbHost = strParam(params, "host") != null ? strParam(params, "host") : "localhost";
                int dbPort = intParam(params, "port", 0);
                SshTunnelService.Tunnel tunnel = sshTunnelService.open(sshHost, sshPort, sshUser, sshPassword,
                        sshPrivateKey, sshPassphrase, dbHost, dbPort);
                sshTunnel = tunnel;
                connParams = new HashMap<>(params);
                connParams.put("host", "127.0.0.1");
                connParams.put("port", tunnel.getBoundLocalPort());
            }

            // 创建驱动并连接
            DatabaseDriver driver = driverFactory.getOrCreateDriver(connectionId, config.getDbType());
            Object connection = driver.connect(connParams);

            ActiveConnection active = new ActiveConnection(driver, connection);
            active.sshTunnel = sshTunnel;
            activeConnections.put(connectionId, active);
            log.info("成功连接到: {} ({})", config.getName(), config.getDbType());

            result.put("success", true);
            result.put("message", "连接成功");
        } catch (Exception e) {
            log.error("连接失败: {} - {}", connectionId, e.getMessage(), e);
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 断开数据库连接
     */
    public void disconnect(String connectionId) {
        ActiveConnection active = activeConnections.remove(connectionId);
        if (active != null) {
            try {
                active.driver.disconnect(active.connection);
                log.info("已断开连接: {}", connectionId);
            } catch (Exception e) {
                log.warn("断开连接时出错: {} - {}", connectionId, e.getMessage());
            }
            if (active.sshTunnel instanceof SshTunnelService.Tunnel tunnel) {
                try {
                    tunnel.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 应用启动后自动连接标记了 autoConnect=true 的连接
     *
     * <p>
     * 单个连接失败仅记录警告，不阻断应用启动（与 pre-mortem T1 缓解策略一致）。
     * </p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void autoConnectOnStartup() {
        List<ConnectionConfig> configs = repository.findAllByOrderByCreatedAtAsc();
        int connected = 0;
        for (ConnectionConfig config : configs) {
            if (Boolean.TRUE.equals(config.getAutoConnect())) {
                try {
                    Map<String, Object> result = connect(config.getId());
                    if (Boolean.TRUE.equals(result.get("success"))) {
                        connected++;
                    } else {
                        log.warn("自动连接失败: {} - {}", config.getName(), result.get("message"));
                    }
                } catch (Exception e) {
                    log.warn("自动连接异常: {} - {}", config.getName(), e.getMessage());
                }
            }
        }
        if (connected > 0) {
            log.info("启动自动连接完成，成功连接 {} 个", connected);
        }
    }

    /**
     * 校验单库活跃连接数上限（app.max-connections-per-db）
     */
    private void enforcePoolLimit(String dbType) {
        int maxPerDb = appProperties.getMaxConnectionsPerDb();
        if (maxPerDb <= 0) {
            return;
        }
        long count = activeConnections.values().stream()
                .filter(a -> a.driver.getDriverInfo() != null
                        && dbType.equalsIgnoreCase(a.driver.getDriverInfo().getDbType()))
                .count();
        if (count >= maxPerDb) {
            throw new RuntimeException(
                    "已达到该数据库类型的最大连接数上限 (" + maxPerDb + ")，请先断开其他 " + dbType + " 连接");
        }
    }

    /**
     * 切换收藏状态
     */
    public ConnectionDTO toggleFavorite(String id) {
        ConnectionConfig config = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("连接不存在: " + id));
        config.setFavorite(!Boolean.TRUE.equals(config.getFavorite()));
        config = repository.save(config);

        Map<String, Object> params = parseParams(config.getConnectionParamsJson());
        params = credentialService.maskSensitiveFields(params);
        ConnectionDTO dto = ConnectionDTO.fromEntity(config, params);
        dto.setStatus(activeConnections.containsKey(id) ? "CONNECTED" : "DISCONNECTED");
        return dto;
    }

    /**
     * 复制连接配置
     */
    public ConnectionDTO duplicateConnection(String id) {
        ConnectionConfig original = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("连接不存在: " + id));

        ConnectionConfig copy = new ConnectionConfig();
        copy.setName(original.getName() + " (副本)");
        copy.setDbType(original.getDbType());
        copy.setDisplayName(original.getDisplayName());
        copy.setConnectionParamsJson(original.getConnectionParamsJson());
        copy.setGroup(original.getGroup());
        copy.setColor(original.getColor());
        copy.setDescription(original.getDescription());
        copy.setTags(original.getTags());
        copy.setFavorite(false);
        copy.setSortOrder(original.getSortOrder());
        copy.setAutoConnect(false);

        copy = repository.save(copy);
        log.info("复制连接: {} → {}", original.getName(), copy.getName());

        Map<String, Object> params = parseParams(copy.getConnectionParamsJson());
        params = credentialService.maskSensitiveFields(params);
        return ConnectionDTO.fromEntity(copy, params);
    }

    /**
     * 导出所有连接配置 (凭证已解密，用于跨实例迁移)
     */
    public List<ConnectionDTO> exportConnections() {
        List<ConnectionConfig> configs = repository.findAllByOrderByCreatedAtAsc();
        List<ConnectionDTO> dtos = new ArrayList<>();

        for (ConnectionConfig config : configs) {
            Map<String, Object> params = parseParams(config.getConnectionParamsJson());
            params = credentialService.decryptSensitiveFields(params);
            ConnectionDTO dto = ConnectionDTO.fromEntity(config, params);
            dtos.add(dto);
        }

        return dtos;
    }

    /**
     * 导入连接配置 (凭证将重新加密)
     */
    public Map<String, Object> importConnections(List<ConnectionDTO> dtos) {
        int success = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (ConnectionDTO dto : dtos) {
            try {
                ConnectionConfig config = new ConnectionConfig();
                config.setName(dto.getName());
                config.setDbType(dto.getDbType());

                DriverInfo driverInfo = driverRegistry.getDriverInfo(dto.getDbType());
                config.setDisplayName(driverInfo.getDisplayName());

                Map<String, Object> encryptedParams = credentialService.encryptSensitiveFields(dto.getParams());
                config.setConnectionParamsJson(toJsonString(encryptedParams));

                config.setGroup(dto.getGroup());
                config.setColor(dto.getColor());
                config.setDescription(dto.getDescription());
                config.setTags(dto.getTags() != null ? String.join(",", dto.getTags()) : null);
                config.setFavorite(dto.getFavorite() != null ? dto.getFavorite() : false);
                config.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
                config.setAutoConnect(dto.getAutoConnect() != null ? dto.getAutoConnect() : false);

                repository.save(config);
                success++;
            } catch (Exception e) {
                failed++;
                errors.add(dto.getName() + ": " + e.getMessage());
                log.error("导入连接失败: {} - {}", dto.getName(), e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("failed", failed);
        result.put("errors", errors);
        return result;
    }

    /**
     * 获取活跃连接
     */
    public ActiveConnection getActiveConnection(String connectionId) {
        ActiveConnection active = activeConnections.get(connectionId);
        if (active == null) {
            throw new RuntimeException("连接未建立，请先连接: " + connectionId);
        }
        return active;
    }

    /**
     * 检查连接是否活跃
     */
    public boolean isConnected(String connectionId) {
        return activeConnections.containsKey(connectionId);
    }

    // === 工具方法 ===

    private Map<String, Object> parseParams(String json) {
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.error("解析连接参数JSON失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String toJsonString(Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            log.error("序列化连接参数JSON失败: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 活跃连接包装类
     */
    public static class ActiveConnection {
        public final DatabaseDriver driver;
        public final Object connection;
        /** SSH 隧道句柄（若经跳板），断开时关闭 */
        public Object sshTunnel;

        public ActiveConnection(DatabaseDriver driver, Object connection) {
            this.driver = driver;
            this.connection = connection;
        }
    }

    /** 从参数 Map 取字符串 */
    private static String strParam(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null)
            return null;
        String s = v.toString();
        return s.isEmpty() ? null : s;
    }

    private static int intParam(Map<String, Object> params, String key, int def) {
        Object v = params.get(key);
        if (v instanceof Number)
            return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (Exception e) {
                return def;
            }
        }
        return def;
    }
}
