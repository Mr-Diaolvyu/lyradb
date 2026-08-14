package io.github.lexaquila.lyradb.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 企业版授权过滤后的轻量元数据目录。
 *
 * <p>响应只包含逻辑数据源名、数据库类型和已授权对象，不暴露真实数据源 ID、
 * 连接参数或凭据。目录用于客户端本地检索、SQL 补全和工作区导航。</p>
 */
@Data
public class EnterpriseMetadataCatalog {

    private String grantedSourceName;
    private String dbType;
    private List<String> schemas = new ArrayList<>();
    private List<Table> tables = new ArrayList<>();
    private boolean truncated;
    private long refreshedAt;

    @Data
    public static class Table {
        /** 用于界面和 SQL 的点分限定命名空间。 */
        private String schema;
        /** 驱动读取元数据时使用的原始命名空间路径。 */
        private String namespace;
        private String name;
        private String qualifiedName;
        private String type;
        private String remarks;
        /** 表注释/分区标识来自哪一层元数据。 */
        private String metadataSource;
        /** AVAILABLE / PARTIAL / UNAVAILABLE / ERROR。 */
        private String metadataStatus;
        /** 元数据不完整时面向用户的真实原因，不把空白伪装成“无注释”。 */
        private String metadataReason;
        /** AVAILABLE / EMPTY / UNAVAILABLE，区分确实为空与没有读取到。 */
        private String remarksStatus;
        /** 驱动已明确识别为分区表时为 true；未知时为 null。 */
        private Boolean partitioned;

        public Table() {
        }

        public Table(String schema, String namespace, String name,
                     String qualifiedName, String type, String remarks) {
            this.schema = schema;
            this.namespace = namespace;
            this.name = name;
            this.qualifiedName = qualifiedName;
            this.type = type;
            this.remarks = remarks;
        }
    }
}
