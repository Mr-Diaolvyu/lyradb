package io.github.lexaquila.lyradb.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDateTime;

/**
 * 真实数据源（管理员持有，含加密连接信息；用户不可见）
 *
 * <p>连接参数以 JSON 存储，敏感字段（password/accessKeySecret 等）经 {@code CredentialService} 加密。</p>
 */
@Entity
@Table(name = "ent_data_source")
@Data
public class DataSource {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(length = 36)
    private String id;

    @Column(name = "workspace_id", length = 36)
    private String workspaceId;

    @Column(nullable = false, length = 32)
    private String dbType;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Lob
    @Column(name = "connection_params_json", nullable = false)
    private String connectionParamsJson;

    @Column(length = 500)
    private String description;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
