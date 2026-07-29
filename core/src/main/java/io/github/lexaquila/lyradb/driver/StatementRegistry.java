package io.github.lexaquila.lyradb.driver;

import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行中语句登记表（查询取消支持）。
 *
 * <p>每次执行由上层分配独立 executionId。驱动仍按连接登记 Statement，
 * 但取消时按 executionId 精确定位，避免后台任务误取消同连接上的其他请求。</p>
 */
public final class StatementRegistry {

    private static final Map<String, RunningStatement> RUNNING = new ConcurrentHashMap<>();
    private static final Map<Object, String> CONNECTION_EXECUTION = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> CURRENT_EXECUTION = new ThreadLocal<>();

    private StatementRegistry() {
    }

    /** 在当前执行线程建立精确取消上下文。 */
    public static void begin(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId 不能为空");
        }
        CURRENT_EXECUTION.set(executionId);
    }

    /** 清理当前执行线程的取消上下文。 */
    public static void end() {
        CURRENT_EXECUTION.remove();
    }

    /** 登记执行中的语句。 */
    public static void register(Object connection, Statement stmt) {
        if (connection == null || stmt == null) {
            return;
        }
        String executionId = CURRENT_EXECUTION.get();
        if (executionId == null) {
            // 兼容未接入执行上下文的旧调用；只能通过连接维度取消。
            executionId = "legacy-" + UUID.randomUUID();
        }
        RunningStatement running = new RunningStatement(connection, stmt);
        RUNNING.put(executionId, running);
        CONNECTION_EXECUTION.put(connection, executionId);
    }

    /** 注销当前线程对应的语句。 */
    public static void unregister(Object connection) {
        if (connection == null) {
            return;
        }
        String executionId = CONNECTION_EXECUTION.remove(connection);
        if (executionId != null) {
            RUNNING.remove(executionId);
        }
    }

    /** 按请求/任务标识精确取消。 */
    public static boolean cancelExecution(String executionId) {
        RunningStatement running = executionId != null ? RUNNING.get(executionId) : null;
        return cancel(running);
    }

    /**
     * 兼容连接维度取消。新代码应优先调用 {@link #cancelExecution(String)}。
     */
    public static boolean cancel(Object connection) {
        String executionId = connection != null ? CONNECTION_EXECUTION.get(connection) : null;
        return cancel(executionId != null ? RUNNING.get(executionId) : null);
    }

    private static boolean cancel(RunningStatement running) {
        if (running == null) {
            return false;
        }
        try {
            running.statement().cancel();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private record RunningStatement(Object connection, Statement statement) {
    }
}
