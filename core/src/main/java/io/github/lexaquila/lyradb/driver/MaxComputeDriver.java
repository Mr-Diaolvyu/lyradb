package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MaxCompute驱动实现
 *
 * <p>
 * MaxCompute是产品的差异化亮点。此驱动实现MaxCompute特有的：
 * </p>
 * <ul>
 * <li>AK/SK认证（在AbstractJdbcDriver.connect()中已处理）</li>
 * <li>Project→表→分区层级导航（核心差异化Aha Moment）</li>
 * <li>分区表元数据查询 + 分区值列表</li>
 * <li>表Lifecycle/大小/行数等MC特有元数据</li>
 * <li>完整DDL生成（含分区定义/Lifecycle/注释）</li>
 * </ul>
 *
 * <p>
 * MaxCompute是OLAP引擎，声明为只读模式（readOnly=true），
 * 前端根据此能力声明自动禁用行内编辑功能。
 * </p>
 */
public class MaxComputeDriver extends AbstractJdbcDriver {

    public MaxComputeDriver(DriverInfo driverInfo, ClassLoader driverClassLoader) {
        super(driverInfo, driverClassLoader);
    }

    @Override
    public List<TreeNode> getTreeNodes(Object connection, String parentPath) throws Exception {
        Connection conn = (Connection) connection;

        if (parentPath == null || parentPath.isEmpty()) {
            // 顶层：返回Project下的表列表
            // MaxCompute的JDBC连接URL中已包含project参数，直接获取表
            return getTables(conn, null, null);
        }

        // 表级以下：展示分区信息
        if (parentPath.contains("/")) {
            // parentPath 形如 "tableName/partitionKey" 或 "tableName/partitionSpec"
            // 表名始终是第一段（原实现误取 parts[1] 导致 SHOW PARTITIONS 用错对象）
            String[] parts = parentPath.split("/", 2);
            String tableName = parts[0];
            return getPartitionValues(conn, tableName, parentPath);
        }

        // 表级：展示分区字段
        return getPartitions(conn, parentPath);
    }

    /**
     * 获取表列表 (MaxCompute覆盖父类方法)
     * 
     * <p>
     * MaxCompute的表列表通过JDBC getTables获取，
     * 但同时检查每个表是否为分区表并添加属性。
     * </p>
     */
    @Override
    protected List<TreeNode> getTables(Connection conn, String catalog, String schema) throws SQLException {
        List<TreeNode> nodes = super.getTables(conn, catalog, schema);

        // 为每个表添加MaxCompute特有属性
        for (TreeNode node : nodes) {
            try {
                enrichTableNode(conn, node);
            } catch (Exception e) {
                // 属性获取失败不影响列表展示
            }
        }
        return nodes;
    }

    /**
     * 为表节点添加MaxCompute特有属性
     */
    private void enrichTableNode(Connection conn, TreeNode node) throws SQLException {
        String tableName = node.getName();

        // 检查分区表
        List<String> partitionKeys = getPartitionKeys(conn, tableName);
        if (!partitionKeys.isEmpty()) {
            node.getProperties().put("partitioned", true);
            node.getProperties().put("partitionKeys", String.join(",", partitionKeys));
            // 获取分区数
            int partitionCount = getPartitionCount(conn, tableName);
            node.getProperties().put("partitionCount", partitionCount);
        } else {
            node.getProperties().put("partitioned", false);
        }

        // 获取表大小和行数 (通过DESCRIBE EXTENDED)
        try {
            Map<String, Object> extendedInfo = getExtendedTableInfo(conn, tableName);
            if (extendedInfo != null) {
                node.getProperties().putAll(extendedInfo);
            }
        } catch (Exception e) {
            // DESCRIBE EXTENDED可能不被所有版本支持
        }
    }

    /** 校验 MaxCompute 表标识符（DESCRIBE/SHOW 无法参数化），仅允许字母数字下划线与点，防注入 */
    private static String safeIdentifier(String identifier) throws SQLException {
        if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
            throw new SQLException("非法的表标识符: " + identifier);
        }
        return identifier;
    }

    /**
     * 获取分区数量
     */
    private int getPartitionCount(Connection conn, String tableName) throws SQLException {
        int count = 0;
        try (Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SHOW PARTITIONS " + safeIdentifier(tableName))) {
                while (rs.next()) {
                    count++;
                }
            }
        } catch (SQLException e) {
            // 非分区表
        }
        return count;
    }

    /**
     * 获取表的扩展信息 (大小/行数/Lifecycle)
     */
    private Map<String, Object> getExtendedTableInfo(Connection conn, String tableName) throws SQLException {
        Map<String, Object> info = new java.util.HashMap<>();
        try (Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("DESCRIBE EXTENDED " + safeIdentifier(tableName))) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    String value = rs.getString(2);
                    if (key != null && value != null) {
                        if (key.toLowerCase().contains("size")) {
                            info.put("tableSize", value);
                        } else if (key.toLowerCase().contains("lifecycle")) {
                            info.put("lifecycle", value);
                        } else if (key.toLowerCase().contains("rows") || key.toLowerCase().contains("count")) {
                            info.put("rowCount", value);
                        }
                    }
                }
            }
        }
        return info;
    }

    /**
     * 获取分区键列表
     */
    private List<String> getPartitionKeys(Connection conn, String tableName) throws SQLException {
        List<String> keys = new ArrayList<>();
        // MaxCompute JDBC支持getColumns，分区字段通过特定查询获取
        try (Statement stmt = conn.createStatement()) {
            // 使用SHOW PARTITIONS获取分区信息
            try (ResultSet rs = stmt.executeQuery("SHOW PARTITIONS " + safeIdentifier(tableName))) {
                // 有分区结果则说明是分区表
                if (rs.next()) {
                    // 从分区值中解析分区键名
                    String partitionStr = rs.getString(1);
                    if (partitionStr != null && partitionStr.contains("=")) {
                        String[] parts = partitionStr.split(",");
                        for (String part : parts) {
                            String[] kv = part.trim().split("=", 2);
                            if (kv.length == 2 && !keys.contains(kv[0].trim())) {
                                keys.add(kv[0].trim());
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            // 非分区表或无分区时忽略错误
        }
        return keys;
    }

    /**
     * 获取表的分区键列表 (表级展开时显示分区字段)
     */
    private List<TreeNode> getPartitions(Connection conn, String parentPath) throws SQLException {
        List<TreeNode> nodes = new ArrayList<>();
        String tableName = parentPath;

        List<String> partitionKeys = getPartitionKeys(conn, tableName);
        for (String key : partitionKeys) {
            TreeNode node = TreeNode.of(
                    parentPath + "/" + key,
                    key,
                    "PARTITION",
                    parentPath + "/" + key);
            node.setIconType("partition");
            node.setHasChildren(true);
            node.getProperties().put("partitionKey", key);
            nodes.add(node);
        }
        return nodes;
    }

    /**
     * 获取分区值列表
     */
    private List<TreeNode> getPartitionValues(Connection conn, String tableName, String parentPath)
            throws SQLException {
        List<TreeNode> nodes = new ArrayList<>();

        try (Statement stmt = conn.createStatement()) {
            String sql = "SHOW PARTITIONS " + safeIdentifier(tableName);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String partitionSpec = rs.getString(1);
                    if (partitionSpec != null) {
                        TreeNode node = TreeNode.of(
                                parentPath + "/" + partitionSpec,
                                partitionSpec,
                                "PARTITION",
                                parentPath + "/" + partitionSpec);
                        node.setIconType("partition");
                        node.setHasChildren(false);
                        node.getProperties().put("partitionSpec", partitionSpec);
                        nodes.add(node);
                    }
                }
            }
        } catch (SQLException e) {
            // 非分区表忽略
        }

        return nodes;
    }

    @Override
    public String getTableDDL(Object connection, String schemaName, String tableName) throws Exception {
        Connection conn = (Connection) connection;
        StringBuilder ddl = new StringBuilder();

        // 获取列信息
        List<io.github.lexaquila.lyradb.model.dto.ColumnMetadata> columns = getTableColumns(conn, schemaName,
                tableName);

        // MaxCompute特有的DDL查询
        try (Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("DESCRIBE " + safeIdentifier(tableName))) {
                ddl.append("-- MaxCompute Table: ").append(tableName).append("\n");

                // 收集列信息和分区信息
                List<String> columnDefs = new ArrayList<>();
                List<String> partitionDefs = new ArrayList<>();
                boolean inPartitionSection = false;

                while (rs.next()) {
                    String colName = rs.getString("col_name");
                    String colType = rs.getString("data_type");
                    String comment = rs.getString("comment");

                    if (colName != null) {
                        // 检测是否进入分区字段部分
                        if (colName.trim().equalsIgnoreCase("# Partition")) {
                            inPartitionSection = true;
                            continue;
                        }

                        StringBuilder colDef = new StringBuilder();
                        colDef.append("  ").append(colName.trim()).append(" ").append(colType);
                        if (comment != null && !comment.trim().isEmpty()) {
                            colDef.append(" COMMENT '").append(comment.trim()).append("'");
                        }

                        if (inPartitionSection) {
                            partitionDefs.add(colDef.toString());
                        } else {
                            columnDefs.add(colDef.toString());
                        }
                    }
                }

                // 构建DDL
                ddl.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append("\n");
                ddl.append("(\n");
                for (int i = 0; i < columnDefs.size(); i++) {
                    ddl.append(columnDefs.get(i));
                    if (i < columnDefs.size() - 1)
                        ddl.append(",");
                    ddl.append("\n");
                }
                ddl.append(")\n");

                // 分区定义
                if (!partitionDefs.isEmpty()) {
                    ddl.append("PARTITIONED BY (\n");
                    for (int i = 0; i < partitionDefs.size(); i++) {
                        ddl.append(partitionDefs.get(i));
                        if (i < partitionDefs.size() - 1)
                            ddl.append(",");
                        ddl.append("\n");
                    }
                    ddl.append(")\n");
                }

                // Lifecycle信息
                try {
                    List<String> partitionKeys = getPartitionKeys(conn, tableName);
                    if (!partitionKeys.isEmpty()) {
                        int partCount = getPartitionCount(conn, tableName);
                        ddl.append("-- Partitions: ").append(partCount).append(" (keys: ")
                                .append(String.join(",", partitionKeys)).append(")\n");
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        } catch (SQLException e) {
            // 降级到标准DDL生成
            return super.getTableDDL(connection, schemaName, tableName);
        }

        return ddl.toString();
    }
}
