
package io.github.lexaquila.lyradb.model.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDateTime;

/**
 * 定时报表订阅（迭代二 PM2）
 *
 * <p>
 * 用户将 SELECT 查询保存为订阅，按 HOURLY/DAILY/WEEKLY 周期自动执行，
 * 结果通过 Webhook 推送（V1 仅 Webhook，邮件规划中）。
 * 每次执行产生一条 {@link ReportRun} 记录。
 * </p>
 */
@Entity
@Table(name = "report_schedule")
@Data
public class ReportSchedule {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(length = 36)
    private String id;

    /** 订阅名称 */
    @Column(length = 100, nullable = false)
    private String name;

    /** 服务端写入的所有者，客户端不可伪造。 */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "owner_username", nullable = false, length = 100)
    private String ownerUsername;

    /** 创建时选择的工作空间，客户端不可伪造。 */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;

    /** 连接 ID */
    @Column(name = "connection_id", length = 36, nullable = false)
    private String connectionId;

    /** 连接显示名（展示用） */
    @Column(name = "connection_name", length = 100)
    private String connectionName;

    /** 报表 SQL（仅允许 SELECT/WITH） */
    @Column(length = 4000, nullable = false)
    private String sql;

    /** 默认数据库（可选） */
    @Column(name = "default_database", length = 100)
    private String defaultDatabase;

    /** 周期类型：HOURLY / DAILY / WEEKLY */
    @Column(name = "schedule_type", length = 16, nullable = false)
    private String scheduleType = "DAILY";

    /** 执行分钟（0-59，各周期通用） */
    @Column(name = "run_minute")
    private int runMinute = 0;

    /** 执行小时（0-23，DAILY/WEEKLY 用） */
    @Column(name = "run_hour")
    private int runHour = 9;

    /** 执行星期（1=周一 ... 7=周日，WEEKLY 用） */
    @Column
    private int weekday = 1;

    /** Webhook 推送地址（http/https） */
    @Column(name = "webhook_url", length = 500, nullable = false)
    private String webhookUrl;

    @Column
    private boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 最近一次执行时间（调度去重依据） */
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** 最近一次执行状态：SUCCESS / FAILED */
    @Column(name = "last_status", length = 16)
    private String lastStatus;
}
