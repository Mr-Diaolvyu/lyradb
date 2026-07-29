
package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.repository.ApprovalRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审批超时定时任务。
 *
 * <p>使用数据库条件更新保证只有执行瞬间仍为 PENDING/APPROVED 的记录才会过期，
 * 不会用较早扫描到的实体覆盖并发批准结果。</p>
 */
@Service
public class ApprovalExpirationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(ApprovalExpirationScheduler.class);

    private final ApprovalRequestRepository repository;

    public ApprovalExpirationScheduler(
            ApprovalRequestRepository repository) {
        this.repository = repository;
    }

    @Scheduled(
            fixedDelayString =
                    "${app.approval.expire-scan-ms:300000}",
            initialDelayString =
                    "${app.approval.expire-scan-ms:300000}")
    public void expireOverdue() {
        try {
            LocalDateTime now = LocalDateTime.now();
            int expired = repository.expireActionableBefore(now);
            if (expired > 0) {
                log.info("审批超时扫描：原子标记 {} 个申请为 EXPIRED",
                        expired);
            }
            int unknown = repository.markStaleExecutingUnknown(now);
            if (unknown > 0) {
                log.error("审批执行超时：{} 个任务结果未知，已禁止自动重试，需人工核验目标库与审计日志",
                        unknown);
            }
        } catch (Exception exception) {
            log.warn("审批超时扫描失败: {}",
                    exception.getMessage(), exception);
        }
    }
}
