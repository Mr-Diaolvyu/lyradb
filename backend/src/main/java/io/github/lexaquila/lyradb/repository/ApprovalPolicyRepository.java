package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.ApprovalPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApprovalPolicyRepository extends JpaRepository<ApprovalPolicy, String> {
    Optional<ApprovalPolicy> findByWorkspaceId(String workspaceId);
}
