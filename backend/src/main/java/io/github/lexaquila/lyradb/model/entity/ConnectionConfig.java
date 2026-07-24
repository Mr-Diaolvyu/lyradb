package io.github.lexaquila.lyradb.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据库连接配置实体（JPA持久化）
 *
 * <p>
 * 存储用户保存的数据库连接配置，包括连接参数和加密后的凭证。
 * 使用H2数据库持久化存储，密码/AK-SK等敏感信息通过Jasypt加密。
 * </p>
 */
@Entity
@Table(name = "connection_config")
@Data
@NoArgsConstructor
public class ConnectionConfig {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(length = 36)
    private String id;

    /** 连接名称 */
    @Column(nullable = false)
    private String name;

    /** 数据库类型（MYSQL/POSTGRESQL/ORACLE等） */
    @Column(nullable = false)
    private String dbType;

    /** 显示名称（从DriverInfo获取） */
    @Column(name = "display_name")
    private String displayName;

    /** 连接参数JSON（host/port/username等，密码已加密） */
    @Lob
    @Column(name = "connection_params", columnDefinition = "TEXT")
    private String connectionParamsJson;

    /** 连接分组 */
    @Column(name = "group_name")
    private String group;

    /** 颜色标签 */
    @Column(name = "color")
    private String color;

    /** 描述 */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 标签 (逗号分隔) */
    @Column(name = "tags")
    private String tags;

    /** 收藏标记 */
    @Column(name = "favorite")
    private Boolean favorite = false;

    /** 排序顺序 */
    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    /** 是否自动连接 */
    @Column(name = "auto_connect")
    private Boolean autoConnect = false;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
