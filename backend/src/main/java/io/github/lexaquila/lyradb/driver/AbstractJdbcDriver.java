package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DriverCapability;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import io.github.lexaquila.lyradb.model.entity.FormField;

import java.sql.*;
import java.util.*;

/**
 * JDBC通用驱动基类
 *
 * <p>
 * 为7种JDBC数据库（MySQL/PostgreSQL/Oracle/MSSQL/SQLite/ClickHouse/MaxCompute）
 * 提供通用实现。子类只需覆盖差异化的方法（如树节点层级、DDL查询等）。
 * </p>
 *
 * <p>
 * 核心职责：
 * </p>
 * <ul>
 * <li>通过URLClassLoader加载JDBC驱动（驱动隔离）</li>
 * <li>使用标准JDBC API获取元数据和执行查询</li>
 * <li>将JDBC ResultSet适配为统一的QueryResult</li>
 * <li>提供通用的DatabaseMetaData导航树构建逻辑</li>
 * </ul>
 */
public abstract class AbstractJdbcDriver implements DatabaseDriver {

    protected final DriverInfo driverInfo;
    protected final DriverCapability capabilities;
    protected final ClassLoader driverClassLoader;

    /** 单条 SQL 执行超时（秒），0 表示不限时；由 DriverFactory 注入 */
    protected int queryTimeoutSeconds = 60;

    /**
     * 构造函数
     *
     * @param driverInfo        驱动配置信息
     * @param driverClassLoader 隔离的ClassLoader（URLClassLoader，加载驱动JAR）
     */
    protected AbstractJdbcDriver(DriverInfo driverInfo, ClassLoader driverClassLoader) {
        this.driverInfo = driverInfo;
        this.capabilities = driverInfo.getCapabilities();
        this.driverClassLoader = driverClassLoader;
    }

    /**
     * 设置单条 SQL 执行超时（秒）。由 DriverFactory 在创建实例后注入。
     */
    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /**
     * 为 Statement 设置查询超时。部分驱动（如 MaxCompute）可能不支持，忽略其异常。
     */
    protected void applyQueryTimeout(Statement stmt) {
        if (queryTimeoutSeconds <= 0) {
            return;
        }
        try {
            stmt.setQueryTimeout(queryTimeoutSeconds);
        } catch (Exception e) {
            // 某些驱动不支持 setQueryTimeout，忽略
        }
    }

    @Override
    public DriverInfo getDriverInfo() {
        return driverInfo;
    }

    @Override
    public DriverCapability getCapabilities() {
        return capabilities;
    }

    @Override
    public boolean testConnection(Map<String, Object> params) {
        try (Connection conn = (Connection) connect(params)) {
            return conn != null && conn.isValid(5);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Object connect(Map<String, Object> params) throws Exception {
        String url = buildConnectionUrl(params);
        String username = getStringParam(params, "username", null);
        String password = getStringParam(params, "password", null);

        // 使用隔离的ClassLoader加载驱动类
        Class<?> driverClass = Class.forName(driverInfo.getDriverClass(), true, driverClassLoader);
        java.sql.Driver driver = (java.sql.Driver) driverClass.getDeclaredConstructor().newInstance();

        Properties props = new Properties();
        if (username != null) {
            props.put("user", username);
        }
        if (password != null) {
            props.put("password", password);
        }

        // MaxCompute特殊处理：使用AK/SK认证
        if ("MAXCOMPUTE".equals(driverInfo.getDbType())) {
            props.put("access_id", getStringParam(params, "accessKeyId", ""));
            props.put("access_key", getStringParam(params, "accessKeySecret", ""));
        }

        // 添加额外连接参数
        setExtraConnectionProperties(props, params);

        return driver.connect(url, props);
    }

    /**
     * 子类可覆盖此方法添加额外的连接属性
     */
    protected void setExtraConnectionProperties(Properties props, Map<String, Object> params) {
        // 默认空实现，子类按需覆盖
    }

    @Override
    public void disconnect(Object connection) {
        if (connection instanceof Connection) {
            try {
                if (!((Connection) connection).isClosed()) {
                    ((Connection) connection).close();
                }
            } catch (SQLException e) {
                // 忽略关闭错误
            }
        }
    }

    @Override
    public List<TreeNode> getTreeNodes(Object connection, String parentPath) throws Exception {
        Connection conn = (Connection) connection;
        if (parentPath == null || parentPath.isEmpty()) {
            // 顶层：返回数据库/Schema列表
            return getDatabases(conn);
        }

        // 元数据组哨兵路径（在 schema 下追加的过程/函数/触发器组）
        String schema;
        if (parentPath.endsWith(GROUP_PROCEDURES)) {
            schema = parentPath.substring(0, parentPath.length() - GROUP_PROCEDURES.length());
            return getProcedures(conn, schema);
        }
        if (parentPath.endsWith(GROUP_FUNCTIONS)) {
            schema = parentPath.substring(0, parentPath.length() - GROUP_FUNCTIONS.length());
            return getFunctions(conn, schema);
        }
        if (parentPath.endsWith(GROUP_TRIGGERS)) {
            schema = parentPath.substring(0, parentPath.length() - GROUP_TRIGGERS.length());
            return getTriggers(conn, schema);
        }

        return getSchemaChildren(conn, parentPath);
    }

    /** 元数据组路径后缀 */
    private static final String GROUP_PROCEDURES = "/__procedures__";
    private static final String GROUP_FUNCTIONS = "/__functions__";
    private static final String GROUP_TRIGGERS = "/__triggers__";

    /**
     * 获取数据库列表
     */
    protected List<TreeNode> getDatabases(Connection conn) throws SQLException {
        List<TreeNode> nodes = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();

        // 对于SQLite，没有数据库列表，直接返回表
        if ("SQLITE".equals(driverInfo.getDbType())) {
            return getTables(conn, null, null);
        }

        ResultSet rs = metaData.getCatalogs();
        while (rs.next()) {
            String dbName = rs.getString("TABLE_CAT");
            TreeNode node = TreeNode.of(dbName, dbName, "DATABASE", dbName);
            node.setIconType("database");
            node.setHasChildren(true);
            nodes.add(node);
        }
        rs.close();

        // 如果没有catalog（某些数据库用schema），尝试获取schema列表
        if (nodes.isEmpty() && !"MAXCOMPUTE".equals(driverInfo.getDbType())) {
            rs = metaData.getSchemas();
            while (rs.next()) {
                String schemaName = rs.getString("TABLE_SCHEM");
                TreeNode node = TreeNode.of(schemaName, schemaName, "SCHEMA", schemaName);
                node.setIconType("schema");
                node.setHasChildren(true);
                nodes.add(node);
            }
            rs.close();
        }

        return nodes;
    }

    /**
     * 获取Schema下的子节点（表/视图等）
     *
     * <p>在表/视图列表之后，按能力声明追加「存储过程 / 函数 / 触发器」组节点（F6 元数据增强）。
     * 各组的展开由 {@link #getTreeNodes} 中的哨兵路径分发处理。</p>
     */
    protected List<TreeNode> getSchemaChildren(Connection conn, String parentPath) throws SQLException {
        List<TreeNode> nodes = new ArrayList<>(getTables(conn, null, parentPath));

        // 追加元数据组节点（能力门控 + 失败不影响表列表）
        if (capabilities.isSupportsProcedures()) {
            nodes.add(groupNode(parentPath, "存储过程", GROUP_PROCEDURES, "procedure-group"));
            nodes.add(groupNode(parentPath, "函数", GROUP_FUNCTIONS, "function-group"));
        }
        if (capabilities.isSupportsTriggers()) {
            nodes.add(groupNode(parentPath, "触发器", GROUP_TRIGGERS, "trigger-group"));
        }
        return nodes;
    }

    /** 构造一个元数据组节点 */
    private TreeNode groupNode(String parentPath, String label, String suffix, String iconType) {
        TreeNode node = TreeNode.of(parentPath + suffix, label, suffix.replace("/", "").toUpperCase(),
                parentPath + suffix);
        node.setIconType(iconType);
        node.setHasChildren(true);
        return node;
    }

    /**
     * 列出 schema 下的存储过程
     */
    protected List<TreeNode> getProcedures(Connection conn, String schema) {
        List<TreeNode> nodes = new ArrayList<>();
        try {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getProcedures(null, schema, "%")) {
                while (rs.next()) {
                    String name = rs.getString("PROCEDURE_NAME");
                    if (name == null) continue;
                    TreeNode node = TreeNode.of(schema + "/" + name, name, "PROCEDURE",
                            schema + "/__procedures__/" + name);
                    node.setIconType("procedure");
                    node.setHasChildren(false);
                    nodes.add(node);
                }
            }
        } catch (SQLException e) {
            // 部分库/权限下失败，返回空列表
        }
        return nodes;
    }

    /**
     * 列出 schema 下的函数
     */
    protected List<TreeNode> getFunctions(Connection conn, String schema) {
        List<TreeNode> nodes = new ArrayList<>();
        try {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getFunctions(null, schema, "%")) {
                while (rs.next()) {
                    String name = rs.getString("FUNCTION_NAME");
                    if (name == null) continue;
                    TreeNode node = TreeNode.of(schema + "/" + name, name, "FUNCTION",
                            schema + "/__functions__/" + name);
                    node.setIconType("function");
                    node.setHasChildren(false);
                    nodes.add(node);
                }
            }
        } catch (SQLException e) {
            // 忽略
        } catch (AbstractMethodError | NoSuchMethodError e) {
            // 老驱动不支持 getFunctions
        }
        return nodes;
    }

    /**
     * 列出 schema 下的触发器（JDBC 无标准 API，best-effort：getTables 类型 TRIGGER）
     */
    protected List<TreeNode> getTriggers(Connection conn, String schema) {
        List<TreeNode> nodes = new ArrayList<>();
        try {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getTables(null, schema, "%", new String[]{"TRIGGER"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (name == null) continue;
                    TreeNode node = TreeNode.of(schema + "/" + name, name, "TRIGGER",
                            schema + "/__triggers__/" + name);
                    node.setIconType("trigger");
                    node.setHasChildren(false);
                    nodes.add(node);
                }
            }
        } catch (SQLException e) {
            // 不支持 TRIGGER 类型，忽略
        }
        return nodes;
    }

    /**
     * 获取表列表
     */
    protected List<TreeNode> getTables(Connection conn, String catalog, String schema) throws SQLException {
        List<TreeNode> nodes = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();

        String[] tableTypes = { "TABLE", "VIEW" };
        if (capabilities.isSupportsViews()) {
            tableTypes = new String[] { "TABLE", "VIEW" };
        } else {
            tableTypes = new String[] { "TABLE" };
        }

        ResultSet rs = metaData.getTables(catalog, schema, "%", tableTypes);
        while (rs.next()) {
            String tableName = rs.getString("TABLE_NAME");
            String tableType = rs.getString("TABLE_TYPE");
            String nodeType = "TABLE".equals(tableType) ? "TABLE" : "VIEW";

            TreeNode node = TreeNode.of(
                    schema != null ? schema + "." + tableName : tableName,
                    tableName,
                    nodeType,
                    schema != null ? schema + "/" + tableName : tableName);
            node.setIconType(nodeType.toLowerCase());
            node.setHasChildren(true);
            nodes.add(node);
        }
        rs.close();

        return nodes;
    }

    @Override
    public List<ColumnMetadata> getTableColumns(Object connection, String schemaName, String tableName)
            throws Exception {
        Connection conn = (Connection) connection;
        List<ColumnMetadata> columns = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();

        ResultSet rs = metaData.getColumns(null, schemaName, tableName, "%");
        while (rs.next()) {
            ColumnMetadata col = new ColumnMetadata();
            col.setName(rs.getString("COLUMN_NAME"));
            col.setDataType(String.valueOf(rs.getInt("DATA_TYPE")));
            col.setTypeName(rs.getString("TYPE_NAME"));
            col.setColumnSize(rs.getInt("COLUMN_SIZE"));
            col.setDecimalDigits(rs.getInt("DECIMAL_DIGITS"));
            col.setNullable(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
            col.setDefaultValue(rs.getString("COLUMN_DEF"));
            col.setRemarks(rs.getString("REMARKS"));
            col.setSchemaName(schemaName);
            col.setTableName(tableName);
            columns.add(col);
        }
        rs.close();

        // 获取主键信息
        rs = metaData.getPrimaryKeys(null, schemaName, tableName);
        while (rs.next()) {
            String pkCol = rs.getString("COLUMN_NAME");
            for (ColumnMetadata col : columns) {
                if (col.getName().equals(pkCol)) {
                    col.setPrimaryKey(true);
                }
            }
        }
        rs.close();

        return columns;
    }

    @Override
    public QueryResult executeQuery(Object connection, String sql, int limit) throws Exception {
        Connection conn = (Connection) connection;
        long startTime = System.currentTimeMillis();

        QueryResult result = new QueryResult();
        result.setSql(sql);

        try (Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(limit > 0 ? limit : 10000);
            applyQueryTimeout(stmt);

            boolean hasResultSet = stmt.execute(sql);
            if (hasResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();

                    // 构建列名
                    for (int i = 1; i <= columnCount; i++) {
                        result.addColumn(metaData.getColumnLabel(i));
                    }

                    // 读取数据行
                    int rowCount = 0;
                    while (rs.next() && (limit <= 0 || rowCount < limit)) {
                        Map<String, Object> row = result.newRow();
                        for (int i = 1; i <= columnCount; i++) {
                            Object value = rs.getObject(i);
                            row.put(result.getColumns().get(i - 1), value);
                        }
                        result.addRow(row);
                        rowCount++;
                    }
                    result.setTotalRows(rowCount);
                    if (limit > 0 && rowCount >= limit) {
                        result.setTruncated(true);
                    }
                }
            } else {
                int updateCount = stmt.getUpdateCount();
                result.addColumn("Affected Rows");
                Map<String, Object> row = result.newRow();
                row.put("Affected Rows", updateCount);
                result.addRow(row);
                result.setTotalRows(1);
            }
        }

        result.setElapsedMs(System.currentTimeMillis() - startTime);
        return result;
    }

    @Override
    public int executeUpdate(Object connection, String sql) throws Exception {
        Connection conn = (Connection) connection;
        try (Statement stmt = conn.createStatement()) {
            applyQueryTimeout(stmt);
            return stmt.executeUpdate(sql);
        }
    }

    @Override
    public String getTableDDL(Object connection, String schemaName, String tableName) throws Exception {
        Connection conn = (Connection) connection;
        StringBuilder ddl = new StringBuilder();

        // 获取列信息构建CREATE TABLE
        List<ColumnMetadata> columns = getTableColumns(conn, schemaName, tableName);

        ddl.append("CREATE TABLE ").append(tableName).append(" (\n");

        for (int i = 0; i < columns.size(); i++) {
            ColumnMetadata col = columns.get(i);
            ddl.append("    ").append(col.getName()).append(" ")
                    .append(col.getTypeName());
            if (col.getColumnSize() > 0) {
                ddl.append("(").append(col.getColumnSize());
                if (col.getDecimalDigits() > 0) {
                    ddl.append(",").append(col.getDecimalDigits());
                }
                ddl.append(")");
            }
            if (!col.isNullable()) {
                ddl.append(" NOT NULL");
            }
            if (col.getDefaultValue() != null) {
                ddl.append(" DEFAULT ").append(col.getDefaultValue());
            }
            if (i < columns.size() - 1) {
                ddl.append(",");
            }
            ddl.append("\n");
        }

        // 主键
        List<String> pkCols = new ArrayList<>();
        for (ColumnMetadata col : columns) {
            if (col.isPrimaryKey()) {
                pkCols.add(col.getName());
            }
        }
        if (!pkCols.isEmpty()) {
            ddl.append("    PRIMARY KEY (");
            ddl.append(String.join(", ", pkCols));
            ddl.append(")\n");
        }

        ddl.append(");");

        // 追加索引定义（来自 DatabaseMetaData.getIndexInfo）
        try {
            String indexDdl = buildIndexDdl(conn, schemaName, tableName);
            if (indexDdl != null && !indexDdl.isEmpty()) {
                ddl.append("\n").append(indexDdl);
            }
        } catch (Exception e) {
            // 部分驱动/权限下无法获取索引，忽略
        }

        // 追加表注释
        try {
            String tableComment = getTableComment(conn, schemaName, tableName);
            if (tableComment != null && !tableComment.isEmpty()) {
                ddl.append("\n-- 表注释: ").append(tableComment);
            }
        } catch (Exception e) {
            // 忽略
        }

        return ddl.toString();
    }

    /**
     * 生成索引 DDL（CREATE [UNIQUE] INDEX ... ON table (cols)）
     *
     * <p>聚合 {@link DatabaseMetaData#getIndexInfo} 的结果，按索引名分组。主键索引（INDEX_NAME 为 null）跳过。</p>
     */
    protected String buildIndexDdl(Connection conn, String schemaName, String tableName) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        Map<String, List<String>> indexColumns = new LinkedHashMap<>();
        Map<String, Boolean> indexUnique = new HashMap<>();

        try (ResultSet rs = metaData.getIndexInfo(null, schemaName, tableName, false, true)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                if (indexName == null) {
                    continue; // 通常为主键/隐式，跳过
                }
                String columnName = rs.getString("COLUMN_NAME");
                if (columnName == null) {
                    continue;
                }
                boolean nonUnique = rs.getBoolean("NON_UNIQUE");
                indexColumns.computeIfAbsent(indexName, k -> new ArrayList<>()).add(columnName);
                indexUnique.putIfAbsent(indexName, !nonUnique);
            }
        }

        StringBuilder sb = new StringBuilder();
        String tableRef = schemaName != null ? schemaName + "." + tableName : tableName;
        for (Map.Entry<String, List<String>> entry : indexColumns.entrySet()) {
            String indexName = entry.getKey();
            List<String> cols = entry.getValue();
            boolean unique = Boolean.TRUE.equals(indexUnique.get(indexName));
            sb.append("CREATE ").append(unique ? "UNIQUE " : "").append("INDEX ")
                    .append(indexName).append(" ON ").append(tableRef).append(" (")
                    .append(String.join(", ", cols)).append(");\n");
        }
        return sb.toString();
    }

    /**
     * 获取表注释（REMARKS）
     */
    protected String getTableComment(Connection conn, String schemaName, String tableName) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getTables(null, schemaName, tableName, null)) {
            if (rs.next()) {
                return rs.getString("REMARKS");
            }
        }
        return null;
    }

    @Override
    public String buildConnectionUrl(Map<String, Object> params) {
        String template = driverInfo.getConnectionUrlTemplate();
        String url = template;

        // 替换模板中的占位符
        for (FormField field : driverInfo.getConnectionFormFields()) {
            String placeholder = "{" + field.getName() + "}";
            Object value = params.get(field.getName());
            if (value != null) {
                url = url.replace(placeholder, value.toString());
            } else if (field.getDefaultValue() != null) {
                url = url.replace(placeholder, field.getDefaultValue().toString());
            }
        }

        return url;
    }

    /**
     * 安全获取字符串参数
     */
    protected String getStringParam(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * 安全获取整数参数
     */
    protected int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 安全获取布尔参数
     */
    protected boolean getBooleanParam(Map<String, Object> params, String key, boolean defaultValue) {
        Object value = params.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }
}
