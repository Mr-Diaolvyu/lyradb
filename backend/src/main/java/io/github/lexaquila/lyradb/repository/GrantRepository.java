package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.Grant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GrantRepository extends JpaRepository<Grant, String> {
    List<Grant> findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(
            String userId, String workspaceId);
    List<Grant> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
    Optional<Grant> findByUserIdAndWorkspaceIdAndGrantedSourceName(
            String userId, String workspaceId, String grantedSourceName);
    Optional<Grant> findByIdAndUserIdAndWorkspaceId(
            String id, String userId, String workspaceId);
}
