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
 * SQLite驱动实现
 *
 * <p>
 * SQLite没有数据库/Schema层级概念，导航树直接展示文件中的表。
 * 不支持SSL、端口、网络连接等概念，连接参数只有文件路径。
 * </p>
 */
public class SQLiteDriver extends AbstractJdbcDriver {

    public SQLiteDriver(DriverInfo driverInfo, ClassLoader driverClassLoader) {
        super(driverInfo, driverClassLoader);
    }

    @Override
    protected List<TreeNode> getDatabases(Connection conn) throws SQLException {
        // SQLite没有catalog/schema层级，直接返回表列表
        return getTables(conn, null, null);
    }

    @Override
    protected List<TreeNode> getSchemaChildren(Connection conn, String parentPath) throws SQLException {
        // SQLite表没有子级Schema，返回空
        return new ArrayList<>();
    }

    @Override
    public String getTableDDL(Object connection, String schemaName, String tableName) throws Exception {
        Connection conn = (Connection) connection;
        // SQLite使用特殊语法获取DDL（tableName 作为字符串字面量，转义单引号防注入）
        String safeName = tableName == null ? "" : tableName.replace("'", "''");
        try (var stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SELECT sql FROM sqlite_master WHERE type='table' AND name='" + safeName + "'")) {
            if (rs.next()) {
                return rs.getString(1) + ";";
            }
        }
        return super.getTableDDL(connection, schemaName, tableName);
    }
}
