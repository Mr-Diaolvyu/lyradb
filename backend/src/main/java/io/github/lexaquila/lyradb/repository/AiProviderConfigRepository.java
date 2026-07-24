package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.AiProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiProviderConfigRepository extends JpaRepository<AiProviderConfig, String> {
    List<AiProviderConfig> findByWorkspaceIdOrWorkspaceIdNullOrderByIsDefaultDescCreatedAtDesc(String workspaceId);

    List<AiProviderConfig> findByEnabledTrueOrderByIsDefaultDescCreatedAtDesc();

    Optional<AiProviderConfig> findByIsDefaultTrueAndEnabledTrue();
}
