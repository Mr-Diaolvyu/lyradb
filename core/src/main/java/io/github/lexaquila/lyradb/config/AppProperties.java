
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
    private String version = "3.1.2";

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

    /** AI 原生能力及治理预算。 */
    private Ai ai = new Ai();

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

    @Data
    public static class Ai {
        /** 兼容现有 SQL 助手的 Ask Lyra 入口。 */
        private boolean askLyraEnabled = true;

        /** 仅将审核通过的团队知识注入模型上下文。 */
        private boolean knowledgeCoreEnabled;

        /** 允许生成并显式确认只读 Agent 计划。 */
        private boolean governedReadAgentEnabled;

        /** 允许把 AI 建议保存为待审核知识草稿。 */
        private boolean teamKnowledgeLoopEnabled;

        /** 允许读取黄金集与质量摘要。 */
        private boolean qualityEnabled;

        /** 启用 MaxCompute 专项只读工具。 */
        private boolean maxComputeAgentEnabled;

        /** 启用独立身份、只读工具白名单的 Agent Gateway。 */
        private boolean agentGatewayEnabled;

        /** 3.x 版本硬门禁：配置为 true 时应用拒绝启动。 */
        private boolean writeAgentEnabled;

        /** 单次知识上下文最大字符数。 */
        private int maxKnowledgeContextChars = 12_000;

        /** 为已审核知识启用外部向量嵌入；失败时确定性降级为关键词检索。 */
        private boolean knowledgeSemanticEnabled;

        /** OpenAI-compatible Embedding 模型名。 */
        private String knowledgeEmbeddingModel = "text-embedding-v3";

        /** 混合检索中的关键词权重，向量权重为 1 减去该值。 */
        private double knowledgeLexicalWeight = 0.65;

        /** 通过 MaxCompute JDBC 读取分区元数据并执行 EXPLAIN/COST SQL。 */
        private boolean maxComputeLiveEvidenceEnabled;

        /** 预检是否必须具备完整实时证据才允许生成计划。 */
        private boolean maxComputeLiveEvidenceRequired;

        /** Agent Gateway 每令牌普通请求的每分钟上限。 */
        private int gatewayRequestsPerMinute = 120;

        /** 执行/预检等高成本工具的每令牌每分钟上限。 */
        private int gatewayExpensiveRequestsPerMinute = 20;

        /** MCP 允许的额外 Origin，逗号分隔；同源请求无需配置。 */
        private String gatewayAllowedOrigins = "";

        /** MCP JSON 请求体序列化后的硬上限。 */
        private int mcpMaxRequestBytes = 1_048_576;

        /** 是否允许精确白名单中的私有 OpenAI-compatible 模型端点。 */
        private boolean privateModelEnabled;

        /** 私有模型精确主机白名单；禁止通配符。 */
        private String privateModelAllowedHosts = "";

        /** 私有模型是否强制 HTTPS；生产默认必须开启。 */
        private boolean privateModelRequireHttps = true;

        /** 多节点部署时显式配置的执行节点 ID；空值由进程生成。 */
        private String executionNodeId = "";

        /** 跨节点取消请求的数据库轮询间隔。 */
        private int cancelPollIntervalMs = 1_000;

        /** 只读 Agent 计划有效期。 */
        private int readAgentPlanTtlSeconds = 300;

        /** 只读 Agent 的产品级行数上限，仍会与 Grant 取最小值。 */
        private int readAgentMaxRows = 1_000;

        /** 单次任务允许的预估微单位成本；0 表示不允许有可计费执行。 */
        private long readAgentMaxEstimatedCostMicros;
    }
}
