package io.github.lexaquila.lyradb.driver;

import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行中语句登记表（查询取消支持）
 *
 * <p>
 * JDBC 驱动在执行查询/更新前将 Statement 以连接对象为键登记，
 * 执行结束后注销。上层通过连接对象调用 {@link #cancel(Object)}
 * 触发 {@link Statement#cancel()}，实现正在执行查询的取消。
 * </p>
 *
 * <p>
 * 说明：同一连接同一时刻仅有一条语句在执行（连接由 ConnectionService 串行使用），
 * 因此按连接对象登记即可；NoSQL 驱动（MongoDB/Redis）命令为短耗时操作，不参与登记。
 * </p>
 */
public final class StatementRegistry {

    private static final Map<Object, Statement> RUNNING = new ConcurrentHashMap<>();

    private StatementRegistry() {
    }

    /** 登记执行中的语句 */
    public static void register(Object connection, Statement stmt) {
        if (connection != null && stmt != null) {
            RUNNING.put(connection, stmt);
        }
    }

    /** 注销语句（执行结束或异常时调用） */
    public static void unregister(Object connection) {
        if (connection != null) {
            RUNNING.remove(connection);
        }
    }

    /**
     * 取消指定连接上正在执行的语句
     *
     * @return true 表示找到执行中语句并已发出取消请求
     */
    public static boolean cancel(Object connection) {
        Statement stmt = connection != null ? RUNNING.get(connection) : null;
        if (stmt == null) {
            return false;
        }
        try {
            stmt.cancel();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
