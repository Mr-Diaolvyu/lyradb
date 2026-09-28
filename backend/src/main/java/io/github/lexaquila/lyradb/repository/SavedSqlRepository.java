package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.SavedSql;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedSqlRepository extends JpaRepository<SavedSql, String> {
    List<SavedSql> findByWorkspaceIdAndUserIdOrderByUpdatedAtDesc(String workspaceId, String userId);
    Optional<SavedSql> findByIdAndWorkspaceIdAndUserId(String id, String workspaceId, String userId);
    long countByWorkspaceIdAndUserId(String workspaceId, String userId);
    long countByUserId(String userId);
}
