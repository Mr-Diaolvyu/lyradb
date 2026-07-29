
package io.github.lexaquila.lyradb.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 应用自定义配置属性。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** 应用版本。 */
    private String version = "3.0.1";

    /** 驱动 JAR 缓存目录路径。 */
    private String driverCacheDir;

    /** 每个数据库最大连接数。 */
    private int maxConnectionsPerDb = 10;

    /** 查询结果最大行数。 */
    private int maxQueryRows = 10000;

    /** 单条 SQL 执行超时（秒），0 表示不限时。 */
    private int queryTimeoutSeconds = 60;

    /** CORS 配置。 */
    private Cors cors = new Cors();

    /** 审计配置。 */
    private Audit audit = new Audit();

    /** 企业版配置。 */
    private Enterprise enterprise = new Enterprise();

    /** 服务端出站访问白名单。 */
    private Outbound outbound = new Outbound();

    /**
     * 发行版：personal（个人版，本地直连）或 enterprise（企业版，RBAC、托管、审计及审批）。
     */
    @NotBlank(message = "app.edition 不能为空")
    @Pattern(regexp = "personal|enterprise",
            message = "app.edition 仅支持 personal 或 enterprise")
    private String edition = "personal";

    @Data
    public static class Cors {
        /** 默认仅放行本地前端开发服务器；生产经 app.cors.allowed-origins 覆盖。 */
        private String allowedOrigins = "http://localhost:5173";
        private String allowedMethods = "GET,POST,PUT,DELETE,OPTIONS";
        private String allowedHeaders = "*";
    }

    @Data
    public static class Audit {
        /** 默认仅存 SQL 哈希，不持久化明文 SQL。 */
        private boolean maskSql = true;
    }

    @Data
    public static class Outbound {
        /** Webhook 允许的精确主机或 *.example.com 受控子域；默认空即禁用。 */
        private String webhookAllowedHosts = "";

        /** 自定义 AI Provider 允许的精确主机或受控子域。 */
        private String aiAllowedHosts = "";
    }

    @Data
    public static class Enterprise {
        /** 首次启动管理员用户名；没有用户时必须显式配置。 */
        private String bootstrapAdminUsername;

        /** 首次启动管理员密码；没有用户时必须显式配置。 */
        private String bootstrapAdminPassword;

        /** 首次启动管理员显示名。 */
        private String bootstrapAdminDisplayName = "平台管理员";

        /** 首次启动管理员邮箱。 */
        private String bootstrapAdminEmail;

        /** 企业版 CSRF Cookie 是否仅经 HTTPS 发送。 */
        private boolean cookieSecure;
    }
}
