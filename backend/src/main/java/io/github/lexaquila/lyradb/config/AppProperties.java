package io.github.lexaquila.lyradb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 应用自定义配置属性
 *
 * <p>
 * 从 application.yml 的 app 节点读取配置项，
 * 包括驱动缓存目录、最大连接数、查询行数限制、CORS等。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** 驱动JAR缓存目录路径 */
    private String driverCacheDir;

    /** 每个数据库最大连接数 */
    private int maxConnectionsPerDb = 10;

    /** 查询结果最大行数 */
    private int maxQueryRows = 10000;

    /** 单条 SQL 执行超时（秒），0 表示不限时 */
    private int queryTimeoutSeconds = 60;

    /** CORS配置 */
    private Cors cors = new Cors();

    /** 审计配置 */
    private Audit audit = new Audit();

    /**
     * 发行版：personal（个人版，本地直连，无多租户/审批）或 enterprise（企业版，RBAC+托管+审计+审批）
     */
    private String edition = "personal";

    @Data
    public static class Cors {
        /** 默认仅放行本地前端开发服务器；生产经 app.cors.allowed-origins 覆盖 */
        private String allowedOrigins = "http://localhost:5173";
        private String allowedMethods = "GET,POST,PUT,DELETE,OPTIONS";
        private String allowedHeaders = "*";
    }

    @Data
    public static class Audit {
        /** 脱敏模式：仅存 SQL 哈希，不存明文 SQL（企业合规可开启） */
        private boolean maskSql = false;
    }
}
