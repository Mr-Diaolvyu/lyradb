package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.TableConstraintMetadata;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DriverCapability;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;

import java.util.List;
import java.util.Map;

/**
 * 数据库驱动通用接口
 *
 * <p>
 * 这是整个驱动抽象层的核心接口，定义了所有数据库驱动必须实现的方法。
 * 9种数据库（MySQL/PG/Oracle/MSSQL/SQLite/MaxCompute/ClickHouse/MongoDB/Redis）
 * 都通过此接口统一管理。
 * </p>
 *
 * <p>
 * 设计理念：每种数据库都是"一等公民"，接口不偏袒任何一种。
 * 能力差异通过 {@link DriverCapability} 声明，而非接口方法的可用性。
 * </p>
 *
 * <p>
 * 继承体系：
 * </p>
 * <ul>
 * <li>{@link AbstractJdbcDriver} - JDBC通用基类（7种RDBMS/OLAP）</li>
 * <li>{@link AbstractNoSqlDriver} - NoSQL通用基类（MongoDB/Redis）</li>
 * </ul>
 */
public interface DatabaseDriver {

    /**
     * 获取驱动信息
     */
    DriverInfo getDriverInfo();

    /**
     * 获取驱动能力声明
     */
    DriverCapability getCapabilities();

    /**
     * 测试连接
     *
     * @param params 连接参数（host/port/username/password等，按库类型不同）
     * @return 连接是否成功
     */
    boolean testConnection(Map<String, Object> params);

    /**
     * 获取连接（JDBC类型返回java.sql.Connection，NoSQL类型返回各自客户端对象）
     *
     * @param params 连接参数
     * @return 连接对象
     */
    Object connect(Map<String, Object> params) throws Exception;

    /**
     * 断开连接
     */
    void disconnect(Object connection);

    /**
     * 获取数据库导航树节点
     *
     * <p>
     * 按数据库类型返回不同层级：
     * <ul>
     * <li>RDBMS: 连接→数据库→Schema→表/视图</li>
     * <li>MaxCompute: 连接→Project→表(含分区层级)</li>
     * <li>MongoDB: 连接→Database→Collection</li>
     * <li>Redis: 连接→DB索引→Key(按前缀分组)</li>
     * </ul>
     * </p>
     *
     * @param connection 数据库连接
     * @param parentPath 父节点路径（如null=顶层, "db1"=库下级, "db1/users"=表下级）
     * @return 子节点列表
     */
    List<TreeNode> getTreeNodes(Object connection, String parentPath) throws Exception;

    /**
     * 按名称搜索数据库对象。
     *
     * <p>该接口面向未展开的元数据，不要求调用方先递归加载导航树。
     * 默认实现返回空列表；支持全局元数据搜索的驱动应覆盖此方法。</p>
     *
     * @param connection 数据库连接
     * @param query      数据库、Schema、表或视图名称片段
     * @param limit      最大返回数量
     * @return 与导航树路径兼容的对象节点
     */
    default List<TreeNode> searchTreeNodes(
            Object connection, String query, int limit) throws Exception {
        return List.of();
    }

    /**
     * 在指定数据库、Schema 或 Project 内搜索对象。
     *
     * <p>默认降级为全局搜索；JDBC 驱动应覆盖此方法，把命名空间直接下推给
     * DatabaseMetaData，避免先枚举所有库再过滤。</p>
     */
    default List<TreeNode> searchTreeNodes(
            Object connection,
            String namespace,
            String query,
            int limit) throws Exception {
        return searchTreeNodes(connection, query, limit);
    }

    /**
     * 获取表结构元数据
     *
     * @param connection 数据库连接
     * @param schemaName Schema名
     * @param tableName  表名
     * @return 列信息列表
     */
    List<ColumnMetadata> getTableColumns(Object connection, String schemaName, String tableName) throws Exception;

    /**
     * 获取表级注释。
     *
     * <p>NoSQL 或驱动未提供表级备注时返回 {@code null}。字段注释仍由
     * {@link #getTableColumns(Object, String, String)} 返回。</p>
     */
    default String getTableComment(
            Object connection, String schemaName, String tableName)
            throws Exception {
        return null;
    }

    /**
     * 获取表的主键、外键与索引信息。
     *
     * <p>NoSQL 或不支持该能力的驱动默认返回空列表。</p>
     */
    default List<TableConstraintMetadata> getTableConstraints(
            Object connection, String schemaName, String tableName)
            throws Exception {
        return List.of();
    }

    /**
     * 构建安全、带行数上限的表预览查询。
     *
     * <p>实现必须将命名空间和表名作为标识符转义，禁止直接拼接为裸 SQL。</p>
     */
    default String buildTablePreviewSql(
            Object connection, String schemaName, String tableName, int limit)
            throws Exception {
        throw new UnsupportedOperationException("当前驱动不支持表数据预览");
    }

    /**
     * 只读预览表数据。
     */
    default QueryResult previewTable(
            Object connection, String schemaName, String tableName, int limit)
            throws Exception {
        String sql = buildTablePreviewSql(
                connection, schemaName, tableName, limit);
        return executeQuery(connection, sql, limit);
    }

    /**
     * 执行查询SQL，返回结果
     *
     * @param connection 数据库连接
     * @param sql        SQL语句
     * @param limit      最大返回行数
     * @return 查询结果
     */
    QueryResult executeQuery(Object connection, String sql, int limit) throws Exception;

    /**
     * 执行更新/DDL语句，返回影响行数
     *
     * @param connection 数据库连接
     * @param sql        SQL语句
     * @return 影响行数
     */
    int executeUpdate(Object connection, String sql) throws Exception;

    /**
     * 获取表DDL（CREATE语句）
     */
    String getTableDDL(Object connection, String schemaName, String tableName) throws Exception;

    /**
     * 构建连接URL
     *
     * @param params 连接参数
     * @return 完整连接URL
     */
    String buildConnectionUrl(Map<String, Object> params);
}
