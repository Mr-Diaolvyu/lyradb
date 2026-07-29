package io.github.lexaquila.lyradb.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.concurrent.Future;

/**
 * 后台查询任务（迭代二 E1，内存驻留不落库）
 *
 * <p>
 * 长查询转入后台执行后由 {@link io.github.lexaquila.lyradb.service.BackgroundTaskService}
 * 维护生命周期：RUNNING → DONE / ERROR / CANCELLED。
 * 结果集暂存于服务内存（有上限，先进先出淘汰），前端通过任务 ID 回取。
 * </p>
 */
@Data
public class BackgroundTask {

    /** 任务 ID（UUID） */
    private String id;

    /** 任务所有者用户名；用于服务端隔离，不接受客户端传入。 */
    private String ownerUsername;

    /** 提交任务时选择的工作空间。 */
    private String workspaceId;

    /** 连接 ID */
    private String connectionId;

    /** 连接显示名（前端任务面板展示用） */
    private String connectionName;

    /** SQL 全文 */
    private String sql;

    /** 状态：RUNNING / DONE / ERROR / CANCELLED */
    private String status = "RUNNING";

    /** 提交时间 */
    private LocalDateTime submittedAt = LocalDateTime.now();

    /** 完成时间 */
    private LocalDateTime finishedAt;

    /** 执行耗时（毫秒） */
    private long elapsedMs;

    /** 结果行数（DONE 时有效） */
    private long totalRows;

    /** 失败原因（ERROR 时有效） */
    private String errorMessage;

    /** 结果是否仍可回看（暂存区可能已淘汰） */
    private boolean resultAvailable = false;

    /** 执行句柄（仅服务端取消用，不序列化） */
    @JsonIgnore
    private transient Future<?> future;
}
