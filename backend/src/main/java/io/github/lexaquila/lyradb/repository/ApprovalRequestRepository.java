package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, String> {
    List<ApprovalRequest> findByApplicantIdOrderByCreatedAtDesc(String applicantId);
    List<ApprovalRequest> findByStatusOrderByCreatedAtDesc(String status);
    List<ApprovalRequest> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
    List<ApprovalRequest> findByStatusAndExpiresAtBefore(String status, LocalDateTime time);
}
