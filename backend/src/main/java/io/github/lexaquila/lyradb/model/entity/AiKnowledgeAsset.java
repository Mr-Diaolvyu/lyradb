package io.github.lexaquila.lyradb.model.entity;

import io.github.lexaquila.lyradb.ai.knowledge.KnowledgeAssetStatus;
import io.github.lexaquila.lyradb.ai.knowledge.KnowledgeAssetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

/** 工作空间隔离、经人工审核的数据智库资产。 */
@Entity
@Table(name = "ai_knowledge_asset", indexes = {
        @Index(name = "idx_ai_knowledge_workspace_status_updated",
                columnList = "workspace_id,status,updated_at"),
        @Index(name = "idx_ai_knowledge_workspace_type",
                columnList = "workspace_id,asset_type")
})
@Data
public class AiKnowledgeAsset {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(length = 36)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 32)
    private KnowledgeAssetType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private KnowledgeAssetStatus status = KnowledgeAssetStatus.DRAFT;

    @Column(nullable = false, length = 200)
    private String title;

    @Lob
    @Column(nullable = false)
    private String definition;

    @Lob
    @Column(name = "verified_sql")
    private String verifiedSql;

    @Column(name = "db_type", length = 32)
    private String dbType;

    @Column(name = "granted_source_name", length = 100)
    private String grantedSourceName;

    @Column(name = "default_database", length = 200)
    private String defaultDatabase;

    @Column(length = 1_000)
    private String keywords;

    @Column(name = "source_ref", length = 1_000)
    private String sourceRef;

    /** METADATA_SNAPSHOT / MANUAL；仅描述摄取渠道，不代表可信等级。 */
    @Column(name = "ingestion_source", length = 64)
    private String ingestionSource;

    /** 可审计父级定位；元数据层级不冒充真实加工血缘。 */
    @Lob
    @Column(name = "lineage_json")
    private String lineageJson;

    @Column(name = "embedding_model", length = 200)
    private String embeddingModel;

    /** 可选向量 JSON；不可用时检索确定性降级为关键词。 */
    @Lob
    @Column(name = "embedding_json")
    private String embeddingJson;

    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "reviewed_by", length = 36)
    private String reviewedBy;

    @Column(name = "review_comment", length = 1_000)
    private String reviewComment;

    @Column(name = "asset_version", nullable = false)
    private int assetVersion = 1;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
