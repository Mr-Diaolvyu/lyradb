package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * ClickHouse驱动实现
 *
 * <p>
 * ClickHouse的JDBC元数据API与标准RDBMS略有不同。
 * 导航树结构：连接 → 数据库 → 表 → 分区（ClickHouse支持分区表）。
 * </p>
 *
 * <p>
 * ClickHouse不支持事务，支持DML但不支持传统索引和触发器。
 * </p>
 */
public class ClickHouseDriver extends AbstractJdbcDriver {

    public ClickHouseDriver(DriverInfo driverInfo, ClassLoader driverClassLoader) {
        super(driverInfo, driverClassLoader);
    }

    @Override
    protected List<TreeNode> getDatabases(Connection conn) throws SQLException {
        List<TreeNode> nodes = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();

        // ClickHouse使用catalogs获取数据库列表
        ResultSet rs = metaData.getCatalogs();
        while (rs.next()) {
            String dbName = rs.getString("TABLE_CAT");
            TreeNode node = TreeNode.of(dbName, dbName, "DATABASE", dbName);
            node.setIconType("database");
            node.setHasChildren(true);
            nodes.add(node);
        }
        rs.close();

        return nodes;
    }

    @Override
    protected List<TreeNode> getSchemaChildren(Connection conn, String parentPath) throws SQLException {
        // parentPath是数据库名，返回该库下的表
        return getTables(conn, parentPath, null);
    }

    @Override
    public List<TreeNode> getTreeNodes(Object connection, String parentPath) throws Exception {
        Connection conn = (Connection) connection;

        if (parentPath == null || parentPath.isEmpty()) {
            return getDatabases(conn);
        }

        // 检查parentPath是否包含表名（格式: "dbName/tableName"）
        if (parentPath.contains("/")) {
            // 表级以下：展示分区信息
            String[] parts = parentPath.split("/");
            String dbName = parts[0];
            String tableName = parts[1];

            return getPartitions(conn, dbName, tableName);
        }

        // 数据库级：展示表
        return getTables(conn, parentPath, null);
    }

    /**
     * 获取ClickHouse分区信息
     */
    private List<TreeNode> getPartitions(Connection conn, String dbName, String tableName) throws SQLException {
        List<TreeNode> nodes = new ArrayList<>();
        try (var stmt = conn.createStatement()) {
            // dbName 作为反引号标识符、tableName 作为字符串字面量，分别转义防注入
            String safeDb = dbName == null ? "" : dbName.replace("`", "``");
            String safeTable = tableName == null ? "" : tableName.replace("'", "''");
            String sql = "SELECT DISTINCT partition FROM `" + safeDb + "`.`system.parts` " +
                    "WHERE table = '" + safeTable + "' AND active = 1 ORDER BY partition";
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String partition = rs.getString(1);
                    TreeNode node = TreeNode.of(
                            dbName + "/" + tableName + "/" + partition,
                            "Partition: " + partition,
                            "PARTITION",
                            dbName + "/" + tableName + "/" + partition);
                    node.setIconType("partition");
                    node.setHasChildren(false);
                    nodes.add(node);
                }
            }
        } catch (SQLException e) {
            // 某些表可能没有分区，忽略错误
        }
        return nodes;
    }
}
