package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.TableConstraintMetadata;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern PRIMARY_KEY_PATTERN = Pattern.compile(
            "(?is)\\bPRIMARY\\s+KEY\\s*\\(([^)]*)\\)");

    public MaxComputeDriver(DriverInfo driverInfo, ClassLoader driverClassLoader) {
        super(driverInfo, driverClassLoader);
    }

    @Override
    protected void setExtraConnectionProperties(
            Properties props, Map<String, Object> params) {
        MaxComputeConnectionOptions.apply(props, params);
    }

    @Override
    public List<TreeNode> getTreeNodes(Object connection, String parentPath) throws Exception {
        Connection conn = (Connection) connection;

        if (parentPath == null || parentPath.isEmpty()) {
            return getProjectTables(conn);
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

    @Override
    public List<TreeNode> searchTreeNodes(
            Object connection, String query, int limit) throws Exception {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String keyword = query.trim();
        if (!keyword.matches("[A-Za-z0-9_]+")) {
            return List.of();
        }
        String normalized = keyword.toLowerCase(Locale.ROOT);
        int safeLimit = Math.max(1, Math.min(limit, 500));
        // 让服务端先按名称收敛结果，避免数千张表的 Project 在每次搜索时
        // 都传输完整清单；MaxCompute SHOW TABLES LIKE 使用 * 作为通配符。
        return getProjectTables(
                (Connection) connection, keyword).stream()
                .filter(node -> node.getName() != null
                        && node.getName().toLowerCase(Locale.ROOT)
                                .contains(normalized))
                .limit(safeLimit)
                .toList();
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
        return getProjectTables(conn);
    }

    /**
     * 按当前 JDBC URL 中的执行 Project 直接列出表。
     *
     * <p>MaxCompute JDBC 的 DatabaseMetaData 在部分版本中会额外枚举旧公共
     * Project（MAXCOMPUTE_PUBLIC_DATA），从而让有效连接在导航阶段误报
     * Schema 不存在。SHOW TABLES 只作用于当前执行 Project，可避开该兼容性
     * 路径，也避免为每张表发起分区/扩展信息 N+1 查询。</p>
     */
    private List<TreeNode> getProjectTables(Connection conn) throws SQLException {
        return readShowTables(conn, "SHOW TABLES");
    }

    private List<TreeNode> getProjectTables(
            Connection conn, String keyword) throws SQLException {
        return readShowTables(
                conn, "SHOW TABLES LIKE '*" + keyword + "*'");
    }

    private List<TreeNode> readShowTables(
            Connection conn, String sql) throws SQLException {
        List<TreeNode> nodes = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String payload = rs.getString(1);
                if (payload == null || payload.isBlank()) {
                    continue;
                }
                for (String line : payload.split("\\R")) {
                    String tableName = showTablesName(line);
                    if (tableName.isBlank()) {
                        continue;
                    }
                    TreeNode node = TreeNode.of(
                            tableName, tableName, "TABLE", tableName);
                    node.setIconType("table");
                    node.setHasChildren(true);
                    nodes.add(node);
                }
            }
        }
        nodes.sort(Comparator.comparing(
                TreeNode::getName, String.CASE_INSENSITIVE_ORDER));
        return nodes;
    }

    /**
     * JDBC SHOW 结果使用“身份前缀:对象名”并可能将多行放在单个单元格中。
     */
    private static String showTablesName(String line) {
        if (line == null) {
            return "";
        }
        String normalized = line.trim();
        int promptSeparator = normalized.lastIndexOf(':');
        return (promptSeparator >= 0
                ? normalized.substring(promptSeparator + 1)
                : normalized).trim();
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
    public List<ColumnMetadata> getTableColumns(
            Object connection, String schemaName, String tableName)
            throws Exception {
        Connection conn = (Connection) connection;

        // 精确到表名的 JDBC 列元数据查询不会触发旧公共 Project 枚举，
        // 且能完整保留字段类型、长度、可空和注释。部分 MaxCompute JDBC
        // 版本会把 DESCRIBE 的整张文本表压成单个多行单元格，因此仅将
        // DESCRIBE 保留为元数据为空或不受支持时的降级路径。
        try {
            List<ColumnMetadata> jdbcColumns = super.getTableColumns(
                    connection, null, tableName);
            if (!jdbcColumns.isEmpty()) {
                return jdbcColumns;
            }
        } catch (SQLException ignored) {
            // 继续使用 DESCRIBE 降级。
        }
        String tableRef = qualifiedTableIdentifier(schemaName, tableName);
        List<ColumnMetadata> columns = new ArrayList<>();
        boolean partitionSection = false;

        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("DESCRIBE " + tableRef)) {
            int columnCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                String name = rs.getString(1);
                String typeName = columnCount >= 2 ? rs.getString(2) : null;
                String remarks = columnCount >= 3 ? rs.getString(3) : null;
                if (name == null || name.isBlank()) {
                    continue;
                }
                String normalizedName = name.trim();
                if (normalizedName.startsWith("#")) {
                    if (normalizedName.toLowerCase(Locale.ROOT)
                            .contains("partition")) {
                        partitionSection = true;
                    }
                    continue;
                }
                if (typeName == null || typeName.isBlank()) {
                    continue;
                }

                ColumnMetadata column = new ColumnMetadata();
                column.setName(normalizedName);
                column.setDataType(String.valueOf(Types.OTHER));
                column.setTypeName(typeName.trim());
                column.setNullable(!typeName.toUpperCase(Locale.ROOT)
                        .contains("NOT NULL"));
                column.setRemarks(partitionSection
                        ? mergeRemark("分区字段", remarks) : remarks);
                column.setSchemaName(schemaName);
                column.setTableName(tableName);
                columns.add(column);
            }
        }
        return columns;
    }

    @Override
    public List<TableConstraintMetadata> getTableConstraints(
            Object connection, String schemaName, String tableName)
            throws Exception {
        String ddl = getTableDDL(connection, schemaName, tableName);
        Matcher matcher = PRIMARY_KEY_PATTERN.matcher(ddl);
        if (!matcher.find()) {
            return List.of();
        }
        List<String> primaryColumns = new ArrayList<>();
        for (String rawColumn : matcher.group(1).split(",")) {
            String column = rawColumn.trim()
                    .replace("`", "")
                    .replace("\"", "");
            if (!column.isBlank()) {
                primaryColumns.add(column);
            }
        }
        if (primaryColumns.isEmpty()) {
            return List.of();
        }
        TableConstraintMetadata primaryKey = new TableConstraintMetadata();
        primaryKey.setName("PRIMARY");
        primaryKey.setType("PRIMARY_KEY");
        primaryKey.setColumns(primaryColumns);
        return List.of(primaryKey);
    }

    @Override
    public String buildTablePreviewSql(
            Object connection, String schemaName, String tableName, int limit) {
        int safeLimit = Math.max(
                1, Math.min(limit, JdbcTableInspector.MAX_PREVIEW_ROWS));
        return "SELECT * FROM "
                + qualifiedTableIdentifier(schemaName, tableName)
                + " TABLESAMPLE (" + safeLimit + " ROWS)";
    }

    /**
     * MaxCompute JDBC 官方查询路径使用 {@link Statement#executeQuery(String)}。
     * 通用 JDBC 的 {@code execute + getResultSet} 在部分 ODPS JDBC 版本中不会
     * 返回可读结果集；同时 setMaxRows 只是提示能力，读取循环仍执行硬上限。
     */
    @Override
    public QueryResult executeQuery(
            Object connection, String sql, int limit) throws Exception {
        Connection conn = (Connection) connection;
        long started = System.currentTimeMillis();
        QueryResult result = new QueryResult();
        result.setSql(sql);

        try (Statement statement = conn.createStatement()) {
            try {
                statement.setMaxRows(limit > 0 ? limit : 10_000);
            } catch (SQLException | UnsupportedOperationException ignored) {
                // ODPS JDBC 的部分版本不实现 setMaxRows；下方读取循环仍严格限行。
            }
            applyQueryTimeout(statement);
            StatementRegistry.register(conn, statement);
            try (ResultSet rows = statement.executeQuery(sql)) {
                ResultSetMetaData metadata = rows.getMetaData();
                int columnCount = metadata.getColumnCount();
                HashMap<String, Integer> occurrences = new HashMap<>();
                for (int index = 1; index <= columnCount; index++) {
                    result.addColumn(AbstractJdbcDriver.disambiguateColumnLabel(
                            metadata.getColumnLabel(index), occurrences));
                }
                int rowCount = 0;
                while (rows.next() && (limit <= 0 || rowCount < limit)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= columnCount; index++) {
                        row.put(result.getColumns().get(index - 1),
                                rows.getObject(index));
                    }
                    result.addRow(row);
                    rowCount++;
                }
                result.setTotalRows(rowCount);
                result.setTruncated(limit > 0 && rowCount >= limit);
            }
        } finally {
            StatementRegistry.unregister(conn);
        }
        result.setElapsedMs(System.currentTimeMillis() - started);
        return result;
    }

    @Override
    public String getTableDDL(Object connection, String schemaName, String tableName) throws Exception {
        Connection conn = (Connection) connection;
        StringBuilder ddl = new StringBuilder();

        String tableRef = qualifiedTableIdentifier(schemaName, tableName);
        try {
            String nativeDdl = getNativeTableDdl(conn, tableRef);
            if (!nativeDdl.isBlank()) {
                return nativeDdl;
            }
        } catch (SQLException ignored) {
            // 老版本服务端不支持 SHOW CREATE TABLE 时，继续使用 DESCRIBE 降级。
        }

        // MaxCompute特有的DDL查询
        try (Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("DESCRIBE " + tableRef)) {
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
            // 不再回退 JDBC DatabaseMetaData，避免再次触发旧公共 Project 枚举。
            throw new SQLException("读取 MaxCompute 表 DDL 失败: " + tableRef, e);
        }

        return ddl.toString();
    }

    private String getNativeTableDdl(
            Connection conn, String tableRef) throws SQLException {
        StringBuilder ddl = new StringBuilder();
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SHOW CREATE TABLE " + tableRef)) {
            int columnCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                String rowText = null;
                for (int index = 1; index <= columnCount; index++) {
                    String candidate = rs.getString(index);
                    if (candidate == null || candidate.isBlank()) {
                        continue;
                    }
                    rowText = candidate;
                    if (candidate.toUpperCase(Locale.ROOT)
                            .contains("CREATE")) {
                        break;
                    }
                }
                if (rowText != null) {
                    if (!ddl.isEmpty()) {
                        ddl.append('\n');
                    }
                    ddl.append(rowText.trim());
                }
            }
        }
        return ddl.toString();
    }

    private static String qualifiedTableIdentifier(
            String namespace, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        String normalizedNamespace = namespace == null
                ? "" : namespace.trim().replace('/', '.');
        String qualified = normalizedNamespace.isBlank()
                ? tableName.trim()
                : normalizedNamespace + "." + tableName.trim();
        try {
            return safeIdentifier(qualified);
        } catch (SQLException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static String mergeRemark(String prefix, String value) {
        return value == null || value.isBlank()
                ? prefix : prefix + " · " + value.trim();
    }
}
