package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.ApprovalPolicy;
import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.repository.ApprovalPolicyRepository;
import io.github.lexaquila.lyradb.repository.ApprovalRequestRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 审批服务（状态机见 audit-approval-workflow.md）
 *
 * <p>MVP：创建(PENDING)、批准/驳回、执行标记。支持双人审批（requireTwoApprovers）。</p>
 */
@Service
public class ApprovalService {

    private static final int APPROVAL_TTL_HOURS = 72;
    private static final int EXECUTE_WINDOW_HOURS = 24;

    private final ApprovalRequestRepository repository;
    private final ApprovalPolicyRepository policyRepository;

    public ApprovalService(ApprovalRequestRepository repository, ApprovalPolicyRepository policyRepository) {
        this.repository = repository;
        this.policyRepository = policyRepository;
    }

    private ApprovalPolicy policyOf(String workspaceId) {
        // null/blank workspace → 查找全局策略（workspaceId 为空的策略）；否则按工作空间查
        if (workspaceId == null || workspaceId.isBlank()) {
            return policyRepository.findAll().stream()
                    .filter(p -> p.getWorkspaceId() == null || p.getWorkspaceId().isBlank())
                    .findFirst().orElse(new ApprovalPolicy());
        }
        return policyRepository.findByWorkspaceId(workspaceId).orElse(new ApprovalPolicy());
    }

    public ApprovalRequest create(String workspaceId, String applicantId, String applicantName,
                                  String operationType, String dataSourceId, String grantedSourceName,
                                  String payloadJson, String reason) {
        ApprovalRequest a = new ApprovalRequest();
        a.setWorkspaceId(workspaceId);
        a.setApplicantId(applicantId);
        a.setApplicantName(applicantName);
        a.setOperationType(operationType);
        a.setDataSourceId(dataSourceId);
        a.setGrantedSourceName(grantedSourceName);
        a.setPayloadJson(payloadJson);
        a.setReason(reason);
        a.setStatus("PENDING");
        a.setExpiresAt(LocalDateTime.now().plusHours(APPROVAL_TTL_HOURS));
        return repository.save(a);
    }

    public List<ApprovalRequest> listMine(String applicantId) {
        return repository.findByApplicantIdOrderByCreatedAtDesc(applicantId);
    }

    public List<ApprovalRequest> listPending() {
        return repository.findByStatusOrderByCreatedAtDesc("PENDING");
    }

    public List<ApprovalRequest> listByWorkspace(String workspaceId) {
        return repository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    public ApprovalRequest approve(String id, String approverId, String comment) {
        ApprovalRequest a = get(id);
        if (!"PENDING".equals(a.getStatus())) {
            throw new RuntimeException("申请当前状态不可审批: " + a.getStatus());
        }
        ApprovalPolicy policy = policyOf(a.getWorkspaceId());
        if (policy.isRequireTwoApprovers()) {
            // 双人审批：需两个不同审批人
            List<String> existing = a.getApproverIds() == null || a.getApproverIds().isBlank()
                    ? List.of() : Arrays.asList(a.getApproverIds().split(","));
            if (existing.contains(approverId)) {
                throw new RuntimeException("你已审批过，需另一位审批人");
            }
            int newCount = a.getApproverCount() + 1;
            a.setApproverCount(newCount);
            String ids = existing.isEmpty() ? approverId : existing.stream().reduce((x, y) -> x + "," + y).orElse("") + "," + approverId;
            a.setApproverIds(ids);
            a.setApproverId(approverId);
            a.setApproverComment(comment);
            if (newCount >= 2) {
                a.setStatus("APPROVED");
                a.setExpiresAt(LocalDateTime.now().plusHours(EXECUTE_WINDOW_HOURS));
            } else {
                // 第一人已批，待第二人
                a.setStatus("PENDING");
            }
            return repository.save(a);
        }
        a.setStatus("APPROVED");
        a.setApproverId(approverId);
        a.setApproverComment(comment);
        a.setExpiresAt(LocalDateTime.now().plusHours(EXECUTE_WINDOW_HOURS));
        return repository.save(a);
    }

    public ApprovalRequest reject(String id, String approverId, String comment) {
        ApprovalRequest a = get(id);
        if (!"PENDING".equals(a.getStatus()) && !"DRAFT".equals(a.getStatus())) {
            throw new RuntimeException("申请当前状态不可驳回: " + a.getStatus());
        }
        a.setStatus("REJECTED");
        a.setApproverId(approverId);
        a.setApproverComment(comment);
        return repository.save(a);
    }

    public ApprovalRequest markExecuted(String id, String executionResult, boolean success) {
        ApprovalRequest a = get(id);
        a.setStatus(success ? "DONE" : "FAILED");
        a.setExecutedAt(LocalDateTime.now());
        a.setExecutionResult(executionResult);
        return repository.save(a);
    }

    public ApprovalRequest cancel(String id, String applicantId) {
        ApprovalRequest a = get(id);
        if (!applicantId.equals(a.getApplicantId())) {
            throw new RuntimeException("仅申请人可撤销");
        }
        if (!"PENDING".equals(a.getStatus()) && !"DRAFT".equals(a.getStatus())) {
            throw new RuntimeException("当前状态不可撤销: " + a.getStatus());
        }
        a.setStatus("CANCELLED");
        return repository.save(a);
    }

    public ApprovalRequest get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("审批申请不存在: " + id));
    }
}
