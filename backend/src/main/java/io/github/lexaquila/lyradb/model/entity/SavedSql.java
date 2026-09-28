package io.github.lexaquila.lyradb.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

@Entity
@Table(name = "ent_saved_sql")
@Data
public class SavedSql {
    @Id @GeneratedValue @UuidGenerator @Column(length = 36)
    private String id;
    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Column(name = "grant_id", nullable = false, length = 36)
    private String grantId;
    @Column(name = "granted_source_name", nullable = false, length = 100)
    private String grantedSourceName;
    @Column(nullable = false, length = 100)
    private String title;
    @Lob @Column(name = "encrypted_sql", nullable = false)
    private String encryptedSql;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
