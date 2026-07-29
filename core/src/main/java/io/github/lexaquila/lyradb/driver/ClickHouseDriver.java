package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ClickHouse 驱动实现。
 *
 * <p>导航树结构：连接 → 数据库 → 表 → 分区。远程连接默认使用 HTTPS，
 * 明文 HTTP 只允许显式连接本机回环地址。</p>
 */
public class ClickHouseDriver extends AbstractJdbcDriver {

    public ClickHouseDriver(DriverInfo driverInfo, ClassLoader driverClassLoader) {
        super(driverInfo, driverClassLoader);
    }

    @Override
    public String buildConnectionUrl(Map<String, Object> params) {
        Map<String, Object> safeParams = new HashMap<>(
                params == null ? Map.of() : params);
        String protocol = String.valueOf(
                safeParams.getOrDefault("protocol", "https"))
                .trim().toLowerCase(Locale.ROOT);
        if (!"https".equals(protocol) && !"http".equals(protocol)) {
            throw new IllegalArgumentException("ClickHouse 协议只能是 HTTPS 或 HTTP");
        }
        String host = String.valueOf(
                safeParams.getOrDefault("host", "")).trim();
        if ("http".equals(protocol) && !isLoopback(host)) {
            throw new IllegalArgumentException(
                    "远程 ClickHouse 必须使用 HTTPS；HTTP 仅允许 localhost/回环地址");
        }
        safeParams.put("protocol", protocol);
        return super.buildConnectionUrl(safeParams);
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    @Override
    protected boolean usesCatalogAsNamespace() {
        return true;
    }

    @Override
    protected List<TreeNode> getDatabases(Connection conn) throws SQLException {
        List<TreeNode> nodes = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getCatalogs()) {
            while (rs.next()) {
                String dbName = rs.getString("TABLE_CAT");
                TreeNode node = TreeNode.of(
                        dbName, dbName, "DATABASE", dbName);
                node.setIconType("database");
                node.setHasChildren(true);
                nodes.add(node);
            }
        }
        return nodes;
    }

    @Override
    protected List<TreeNode> getSchemaChildren(
            Connection conn, String parentPath) throws SQLException {
        return getTables(conn, parentPath, null);
    }

    @Override
    public List<TreeNode> getTreeNodes(
            Object connection, String parentPath) throws Exception {
        Connection conn = (Connection) connection;
        if (parentPath == null || parentPath.isEmpty()) {
            return getDatabases(conn);
        }
        if (parentPath.contains("/")) {
            String[] parts = parentPath.split("/", 3);
            if (parts.length < 2) {
                return List.of();
            }
            return getPartitions(conn, parts[0], parts[1]);
        }
        return getTables(conn, parentPath, null);
    }

    private List<TreeNode> getPartitions(
            Connection conn, String dbName, String tableName) throws SQLException {
        List<TreeNode> nodes = new ArrayList<>();
        String sql = "SELECT DISTINCT partition FROM system.parts "
                + "WHERE database = ? AND table = ? AND active = 1 "
                + "ORDER BY partition";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, dbName);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
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
        } catch (SQLException ignored) {
            // 无分区或无 system.parts 权限时不影响表导航。
        }
        return nodes;
    }
}
