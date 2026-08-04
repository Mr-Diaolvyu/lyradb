package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.AiAgentRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AiAgentRunRepository extends JpaRepository<AiAgentRun, String> {
    Optional<AiAgentRun> findByIdAndWorkspaceIdAndUserId(
            String id, String workspaceId, String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from AiAgentRun run where run.id = :id")
    Optional<AiAgentRun> findByIdForUpdate(@Param("id") String id);

    List<AiAgentRun> findTop100ByExecutionNodeIdAndStatusAndCancelRequestedTrue(
            String executionNodeId, String status);

    List<AiAgentRun> findTop100ByStatusAndExpiresAtBefore(
            String status, LocalDateTime expiresAt);

    long countByWorkspaceIdAndStatus(String workspaceId, String status);
}
