package io.github.lexaquila.lyradb.model.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

/**
 * SQL 查询历史记录实体
 *
 * <p>
 * 记录用户执行过的 SQL 语句，支持按连接/关键字/收藏筛选与全文搜索（PRD F4）。
 * </p>
 */
@Entity
@Table(name = "query_history")
public class QueryHistory {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(length = 36)
    private String id;

    @Column(name = "connection_id", length = 36)
    private String connectionId;

    @Column(name = "db_type", length = 32)
    private String dbType;

    @Lob
    @Column(name = "sql_text", nullable = false)
    private String sql;

    @Column(length = 200)
    private String title;

    @Column(name = "is_favorite")
    private Boolean favorite = false;

    @Column(length = 500)
    private String tags;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "is_success")
    private Boolean success = true;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @PrePersist
    public void prePersist() {
        if (executedAt == null) {
            executedAt = LocalDateTime.now();
        }
        if (favorite == null) {
            favorite = false;
        }
        if (success == null) {
            success = true;
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

    public String getDbType() { return dbType; }
    public void setDbType(String dbType) { this.dbType = dbType; }

    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Boolean getFavorite() { return favorite; }
    public void setFavorite(Boolean favorite) { this.favorite = favorite; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long rowCount) { this.rowCount = rowCount; }

    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
