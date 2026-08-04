package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.ai.knowledge.KnowledgeAssetStatus;
import io.github.lexaquila.lyradb.model.entity.AiKnowledgeAsset;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiKnowledgeAssetRepository
        extends JpaRepository<AiKnowledgeAsset, String> {

    Optional<AiKnowledgeAsset> findByIdAndWorkspaceId(
            String id, String workspaceId);

    List<AiKnowledgeAsset> findByWorkspaceIdAndStatusOrderByUpdatedAtDesc(
            String workspaceId, KnowledgeAssetStatus status, Pageable pageable);

    List<AiKnowledgeAsset> findByWorkspaceIdAndCreatedByOrderByUpdatedAtDesc(
            String workspaceId, String createdBy, Pageable pageable);

    List<AiKnowledgeAsset> findByWorkspaceIdOrderByUpdatedAtDesc(
            String workspaceId, Pageable pageable);
}
