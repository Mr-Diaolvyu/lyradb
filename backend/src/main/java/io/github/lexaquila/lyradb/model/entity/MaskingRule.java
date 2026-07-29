

package io.github.lexaquila.lyradb.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

/**
 * 数据脱敏规则（企业版 PM3，管理员配置，数据源级）
 *
 * <p>
 * 命中规则的结果集列在返回前端前按 maskType 脱敏，
 * dataSourceId 为空表示全局规则（对所有数据源生效）。
 * </p>
 */
@Entity
@Table(name = "ent_masking_rule", indexes = {
        @Index(name = "idx_masking_workspace_source_enabled",
                columnList = "workspace_id,data_source_id,enabled")
})
@Data
public class MaskingRule {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(length = 36)
    private String id;

    /** 工作空间作用域。 */
    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;

    /** 目标数据源 ID（空 = 当前工作空间全局规则）。 */
    @Column(name = "data_source_id", length = 36)
    private String dataSourceId;

    /** 表名匹配（支持末尾 * 前缀通配；空 = 不限表） */
    @Column(name = "table_pattern", length = 200)
    private String tablePattern;

    /** 列名匹配（支持末尾 * 前缀通配，逗号分隔多个） */
    @Column(name = "column_pattern", length = 500)
    private String columnPattern;

    /** 脱敏方式：FULL 全遮盖 / PARTIAL 保留首尾 / HASH 摘要 */
    @Column(name = "mask_type", length = 16)
    private String maskType = "PARTIAL";

    /** 规则说明（如"手机号脱敏"） */
    @Column(length = 200)
    private String remark;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
