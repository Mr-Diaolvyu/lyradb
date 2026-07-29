
package io.github.lexaquila.lyradb.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 企业用户实体
 *
 * <p>密码以 BCrypt 哈希存储；角色为字符串集合（PLATFORM_ADMIN/DS_ADMIN/STEWARD/ANALYST/AUDITOR）。</p>
 */
@Entity
@Table(name = "sys_user")
@Data
public class User {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(length = 36)
    private String id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(length = 200)
    private String email;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 密码或认证策略变化时递增，用于让既有会话立即失效。 */
    @Column(name = "credential_version", nullable = false)
    private long credentialVersion = 0;

    /** 角色集合 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sys_user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", length = 32)
    private List<String> roles = new ArrayList<>();

    /** 所属工作空间（多对多，EAGER 以便 /auth/me 直接返回） */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "sys_user_workspace",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "workspace_id"))
    private List<Workspace> workspaces = new ArrayList<>();

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
