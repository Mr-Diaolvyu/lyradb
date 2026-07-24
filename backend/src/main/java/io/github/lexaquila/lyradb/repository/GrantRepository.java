package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.Grant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GrantRepository extends JpaRepository<Grant, String> {
    List<Grant> findByUserIdOrderByCreatedAtDesc(String userId);
    List<Grant> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
    Optional<Grant> findByUserIdAndGrantedSourceName(String userId, String grantedSourceName);
    Optional<Grant> findByIdAndUserId(String id, String userId);
}
