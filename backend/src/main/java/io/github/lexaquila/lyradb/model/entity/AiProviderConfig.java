package io.github.lexaquila.lyradb.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

/**
 * AI Provider 配置（KEY 加密存储）
 *
 * <p>支持 OpenAI-compatible 端点：百炼/GLM/豆包/Deepseek/GPT/自定义。</p>
 */
@Entity
@Table(name = "ai_provider_config")
@Data
public class AiProviderConfig {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(length = 36)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;

    /** bailian/glm/doubao/deepseek/gpt/custom */
    @Column(name = "provider_key", length = 16)
    private String providerKey;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "base_url", length = 255)
    private String baseUrl;

    /** PUBLIC / PRIVATE；私有端点还必须通过服务端精确主机白名单。 */
    @Column(name = "deployment_mode", nullable = false, length = 16)
    private String deploymentMode = "PUBLIC";

    /** 加密存储 */
    @Column(name = "api_key", length = 500)
    private String apiKey;

    @Column(length = 100)
    private String model;

    @Column(name = "temperature")
    private Double temperature = 0.2;

    @Column(name = "max_tokens")
    private Integer maxTokens = 2048;

    @Column(name = "is_enabled")
    private boolean enabled = true;

    @Column(name = "is_default")
    private boolean isDefault = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
