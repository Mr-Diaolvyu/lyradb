package io.github.lexaquila.lyradb.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;

/**
 * 报表订阅执行记录（迭代二 PM2）
 */
@Entity
@Table(name = "report_run")
@Data
public class ReportRun {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(length = 36)
    private String id;

    @Column(name = "schedule_id", length = 36, nullable = false)
    private String scheduleId;

    @Column(name = "run_at")
    private LocalDateTime runAt = LocalDateTime.now();

    /** 查询是否成功 */
    @Column
    private boolean success;

    /** 结果行数 */
    @Column(name = "row_count")
    private long rowCount;

    /** 查询耗时（毫秒） */
    @Column(name = "elapsed_ms")
    private long elapsedMs;

    /** Webhook 推送状态：PUSHED / PUSH_FAILED / SKIPPED */
    @Column(name = "push_status", length = 16)
    private String pushStatus;

    /** 失败原因（查询或推送失败时） */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;
}
