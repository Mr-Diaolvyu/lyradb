package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.EnterpriseQueryHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface EnterpriseQueryHistoryRepository extends JpaRepository<EnterpriseQueryHistory, String> {
    List<EnterpriseQueryHistory> findTop100ByWorkspaceIdAndUserIdOrderByCreatedAtDesc(
            String workspaceId, String userId);
    long deleteByCreatedAtBefore(LocalDateTime cutoff);
}
