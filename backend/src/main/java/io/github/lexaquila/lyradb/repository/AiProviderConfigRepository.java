package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.AiProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiProviderConfigRepository extends JpaRepository<AiProviderConfig, String> {
    List<AiProviderConfig> findByWorkspaceIdOrderByIsDefaultDescCreatedAtDesc(String workspaceId);
    Optional<AiProviderConfig> findByIdAndWorkspaceId(String id, String workspaceId);
    Optional<AiProviderConfig> findByWorkspaceIdAndIsDefaultTrueAndEnabledTrue(String workspaceId);
}
