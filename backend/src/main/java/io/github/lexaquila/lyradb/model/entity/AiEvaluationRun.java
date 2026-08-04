package io.github.lexaquila.lyradb.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

/** 工作空间隔离的 AI 黄金集回归记录。 */
@Entity
@Table(name = "ai_evaluation_run", indexes = {
        @Index(name = "idx_ai_eval_workspace_created",
                columnList = "workspace_id,created_at")
})
@Data
public class AiEvaluationRun {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(length = 36)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;

    @Column(name = "golden_set_version", nullable = false, length = 32)
    private String goldenSetVersion;

    @Column(name = "evaluation_mode", nullable = false, length = 16)
    private String evaluationMode = "MANUAL";

    @Column(name = "provider_key", length = 32)
    private String providerKey;

    @Column(name = "model_name", length = 200)
    private String modelName;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(name = "case_count", nullable = false)
    private int caseCount;

    @Column(name = "passed_count", nullable = false)
    private int passedCount;

    @Column(name = "average_score", nullable = false)
    private double averageScore;

    @Column(name = "release_gate_passed", nullable = false)
    private boolean releaseGatePassed;

    @Lob
    @Column(name = "report_json", nullable = false)
    private String reportJson;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
