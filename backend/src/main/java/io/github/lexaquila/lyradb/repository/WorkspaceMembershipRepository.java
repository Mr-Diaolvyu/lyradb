package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.WorkspaceMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkspaceMembershipRepository extends JpaRepository<WorkspaceMembership, String> {
    Optional<WorkspaceMembership> findByUserIdAndWorkspaceId(String userId, String workspaceId);
    List<WorkspaceMembership> findByUserId(String userId);
    boolean existsByUserIdAndWorkspaceId(String userId, String workspaceId);
}
