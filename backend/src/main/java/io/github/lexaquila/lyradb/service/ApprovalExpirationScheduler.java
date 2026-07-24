package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.repository.ApprovalRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审批超时/到期定时任务
 *
 * <p>每 5 分钟扫描一次：将 PENDING 但已过 {@code expiresAt} 的申请标记为 EXPIRED。
 * 企业版「任何导出都要审批」+ 超时不执行自动关闭，防止审批单长期悬挂。</p>
 */
@Service
public class ApprovalExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExpirationScheduler.class);

    private final ApprovalRequestRepository repository;

    public ApprovalExpirationScheduler(ApprovalRequestRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${app.approval.expire-scan-ms:300000}",
               initialDelayString = "${app.approval.expire-scan-ms:300000}")
    public void expireOverdue() {
        try {
            List<ApprovalRequest> overdue = repository.findByStatusAndExpiresAtBefore("PENDING", LocalDateTime.now());
            if (overdue.isEmpty()) return;
            for (ApprovalRequest a : overdue) {
                a.setStatus("EXPIRED");
                repository.save(a);
                log.info("审批单超时自动过期: {} ({})", a.getId(), a.getOperationType());
            }
            log.info("审批超时扫描：标记 {} 个申请为 EXPIRED", overdue.size());
        } catch (Exception e) {
            log.warn("审批超时扫描失败: {}", e.getMessage());
        }
    }
}
