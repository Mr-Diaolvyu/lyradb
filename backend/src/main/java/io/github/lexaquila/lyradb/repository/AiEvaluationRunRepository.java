package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.AiEvaluationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiEvaluationRunRepository
        extends JpaRepository<AiEvaluationRun, String> {

    Optional<AiEvaluationRun> findFirstByWorkspaceIdOrderByCreatedAtDesc(
            String workspaceId);
}
