

package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ApprovalRequestRepository
        extends JpaRepository<ApprovalRequest, String> {

    List<ApprovalRequest> findTop100ByApplicantIdAndWorkspaceIdOrderByCreatedAtDesc(
            String applicantId, String workspaceId);

    List<ApprovalRequest> findTop100ByApplicantIdAndWorkspaceIdAndStatusOrderByCreatedAtDesc(
            String applicantId, String workspaceId, String status);

    List<ApprovalRequest> findTop100ByWorkspaceIdAndStatusOrderByCreatedAtDesc(
            String workspaceId, String status);

    List<ApprovalRequest> findTop100ByWorkspaceIdOrderByCreatedAtDesc(
            String workspaceId);

    long countByApplicantIdAndWorkspaceIdAndStatusIn(
            String applicantId, String workspaceId, Collection<String> statuses);

    Optional<ApprovalRequest> findFirstByApplicantIdAndWorkspaceIdAndGrantIdAndOperationTypeAndPayloadHashAndStatusOrderByCreatedAtDesc(
            String applicantId, String workspaceId, String grantId,
            String operationType, String payloadHash, String status);

    List<ApprovalRequest> findByStatusAndExpiresAtBefore(
            String status, LocalDateTime time);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select approval from ApprovalRequest approval "
            + "where approval.id = :id")
    Optional<ApprovalRequest> findByIdForUpdate(
            @Param("id") String id);

    /**
     * 过期转换在独立事务中条件提交，调用方随后抛出“已过期”也不会回滚状态。
     * 调用前不得持有该审批行的悲观锁。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ApprovalRequest approval "
            + "set approval.status = 'EXPIRED', "
            + "approval.updatedAt = :time "
            + "where approval.id = :id "
            + "and approval.status = :status "
            + "and approval.expiresAt is not null "
            + "and approval.expiresAt < :time")
    int expireByIdAndStatusBefore(
            @Param("id") String id,
            @Param("status") String status,
            @Param("time") LocalDateTime time);

    /**
     * 定时兜底同时清理尚未审批和已批准但未执行的过期记录。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ApprovalRequest approval "
            + "set approval.status = 'EXPIRED', "
            + "approval.updatedAt = :time "
            + "where approval.status in ('PENDING', 'APPROVED') "
            + "and approval.expiresAt is not null "
            + "and approval.expiresAt < :time")
    int expireActionableBefore(@Param("time") LocalDateTime time);

    /** 执行态超时只标记为结果未知，绝不自动重跑潜在 DML。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ApprovalRequest approval "
            + "set approval.status = 'EXECUTION_UNKNOWN', "
            + "approval.executionResult = '执行超时，结果未知，禁止自动重试', "
            + "approval.updatedAt = :time "
            + "where approval.status = 'EXECUTING' "
            + "and approval.expiresAt is not null "
            + "and approval.expiresAt < :time")
    int markStaleExecutingUnknown(@Param("time") LocalDateTime time);

    boolean existsByDataSourceIdAndStatus(String dataSourceId, String status);

    boolean existsByGrantIdAndStatus(String grantId, String status);

    boolean existsByWorkspaceIdAndStatus(String workspaceId, String status);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("update ApprovalRequest approval "
            + "set approval.status = 'INVALIDATED', "
            + "approval.updatedAt = :time "
            + "where approval.dataSourceId = :dataSourceId "
            + "and approval.status in ('PENDING', 'APPROVED')")
    int invalidateActionableByDataSource(
            @Param("dataSourceId") String dataSourceId,
            @Param("time") LocalDateTime time);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("update ApprovalRequest approval "
            + "set approval.status = 'INVALIDATED', "
            + "approval.updatedAt = :time "
            + "where approval.grantId = :grantId "
            + "and approval.status in ('PENDING', 'APPROVED')")
    int invalidateActionableByGrant(
            @Param("grantId") String grantId,
            @Param("time") LocalDateTime time);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("update ApprovalRequest approval "
            + "set approval.status = 'INVALIDATED', "
            + "approval.updatedAt = :time "
            + "where approval.workspaceId = :workspaceId "
            + "and approval.status in ('PENDING', 'APPROVED')")
    int invalidateActionableByWorkspace(
            @Param("workspaceId") String workspaceId,
            @Param("time") LocalDateTime time);
}
