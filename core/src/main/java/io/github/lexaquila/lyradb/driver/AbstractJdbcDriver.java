package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.TableConstraintMetadata;
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
    private static final System.Logger LOGGER =
            System.getLogger(AbstractJdbcDriver.class.getName());

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
        if (!(connection instanceof Connection jdbc)) {
            return;
        }

        try {
            if (jdbc.isClosed()) {
                return;
            }
        } catch (SQLException exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "检查 JDBC 连接状态失败，仍将尝试回滚并关闭", exception);
        }

        try {
            if (!jdbc.getAutoCommit()) {
                jdbc.rollback();
            }
        } catch (SQLException exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "断开 JDBC 连接前显式回滚失败", exception);
        }

        try {
            jdbc.close();
        } catch (SQLException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "关闭 JDBC 连接失败", exception);
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

    @Override
    public List<TreeNode> searchTreeNodes(
            Object connection, String query, int limit) throws Exception {
        if (!(connection instanceof Connection conn)) {
            return List.of();
        }
        String keyword = query == null ? "" : query.trim();
        if (keyword.isEmpty()) {
            return List.of();
        }

        int safeLimit = Math.max(1, Math.min(limit, 200));
        int collectionLimit = Math.min(800, Math.max(safeLimit * 4, safeLimit));
        String normalized = keyword.toLowerCase(Locale.ROOT);
        DatabaseMetaData metadata = conn.getMetaData();
        Map<String, TreeNode> matches = new LinkedHashMap<>();

        if (!"SQLITE".equalsIgnoreCase(driverInfo.getDbType())
                && !"MAXCOMPUTE".equalsIgnoreCase(driverInfo.getDbType())) {
            collectNamespaceMatches(
                    metadata, normalized, collectionLimit, matches);
        }
        collectTableMatches(
                metadata, keyword, normalized, collectionLimit, matches);

        return matches.values().stream()
                .sorted(Comparator
                        .comparingInt((TreeNode node) ->
                                matchRank(node.getName(), normalized))
                        .thenComparingInt(node -> typeRank(node.getType()))
                        .thenComparing(node -> node.getName()
                                .toLowerCase(Locale.ROOT)))
                .limit(safeLimit)
                .toList();
    }

    @Override
    public List<TreeNode> searchTreeNodes(
            Object connection,
            String namespace,
            String query,
            int limit) throws Exception {
        if (namespace == null || namespace.isBlank()) {
            return searchTreeNodes(connection, query, limit);
        }
        if (!(connection instanceof Connection conn)) {
            return List.of();
        }
        String keyword = query == null ? "" : query.trim();
        if (keyword.isEmpty()) {
            return List.of();
        }

        int safeLimit = Math.max(1, Math.min(limit, 200));
        String normalized = keyword.toLowerCase(Locale.ROOT);
        DatabaseMetaData metadata = conn.getMetaData();
        String escape = metadata.getSearchStringEscape();
        String pattern = "%" + escapeMetadataPattern(keyword, escape) + "%";
        String[] tableTypes = capabilities.isSupportsViews()
                ? new String[]{"TABLE", "VIEW"}
                : new String[]{"TABLE"};
        MetadataNamespace owner = metadataNamespace(namespace);
        List<TreeNode> matches = new ArrayList<>();
        try (ResultSet tables = metadata.getTables(
                owner.catalog(), owner.schema(), pattern, tableTypes)) {
            while (tables.next() && matches.size() < safeLimit) {
                String name = tables.getString("TABLE_NAME");
                if (name == null || name.isBlank()
                        || !name.toLowerCase(Locale.ROOT)
                                .contains(normalized)) {
                    continue;
                }
                String catalog = tables.getString("TABLE_CAT");
                String schema = tables.getString("TABLE_SCHEM");
                String rawType = tables.getString("TABLE_TYPE");
                String type = rawType != null
                        && rawType.toUpperCase(Locale.ROOT).contains("VIEW")
                        ? "VIEW" : "TABLE";
                String path = searchResultPath(catalog, schema, name);
                TreeNode node = TreeNode.of(path, name, type, path);
                node.setIconType(type.toLowerCase(Locale.ROOT));
                node.setHasChildren(true);
                if (catalog != null && !catalog.isBlank()) {
                    node.getProperties().put("catalog", catalog);
                }
                if (schema != null && !schema.isBlank()) {
                    node.getProperties().put("schema", schema);
                }
                matches.add(node);
            }
        }
        return matches.stream()
                .sorted(Comparator
                        .comparingInt((TreeNode node) ->
                                matchRank(node.getName(), normalized))
                        .thenComparing(TreeNode::getName,
                                String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private void collectNamespaceMatches(
            DatabaseMetaData metadata,
            String normalized,
            int limit,
            Map<String, TreeNode> matches) throws SQLException {
        if (usesCatalogAsNamespace()) {
            try (ResultSet catalogs = metadata.getCatalogs()) {
                while (catalogs.next() && matches.size() < limit) {
                    addNamespaceMatch(
                            matches,
                            catalogs.getString("TABLE_CAT"),
                            "DATABASE",
                            normalized);
                }
            }
            if (isSqlServer() && matches.size() < limit) {
                try (ResultSet schemas = metadata.getSchemas()) {
                    while (schemas.next() && matches.size() < limit) {
                        String schema = schemas.getString("TABLE_SCHEM");
                        String catalog = schemas.getString("TABLE_CATALOG");
                        if (schema == null || schema.isBlank()
                                || !schema.toLowerCase(Locale.ROOT)
                                        .contains(normalized)) {
                            continue;
                        }
                        String path = joinPath(catalog, schema);
                        TreeNode node = TreeNode.of(path, schema, "SCHEMA", path);
                        node.setIconType("schema");
                        node.setHasChildren(true);
                        matches.putIfAbsent("SCHEMA:" + path, node);
                    }
                }
            }
            return;
        }

        try (ResultSet schemas = metadata.getSchemas()) {
            while (schemas.next() && matches.size() < limit) {
                addNamespaceMatch(
                        matches,
                        schemas.getString("TABLE_SCHEM"),
                        "SCHEMA",
                        normalized);
            }
        }
    }

    private static void addNamespaceMatch(
            Map<String, TreeNode> matches,
            String name,
            String type,
            String normalized) {
        if (name == null || name.isBlank()
                || !name.toLowerCase(Locale.ROOT).contains(normalized)) {
            return;
        }
        TreeNode node = TreeNode.of(name, name, type, name);
        node.setIconType(type.toLowerCase(Locale.ROOT));
        node.setHasChildren(true);
        matches.putIfAbsent(type + ":" + name, node);
    }

    private void collectTableMatches(
            DatabaseMetaData metadata,
            String keyword,
            String normalized,
            int limit,
            Map<String, TreeNode> matches) throws SQLException {
        String escape = metadata.getSearchStringEscape();
        String pattern = "%" + escapeMetadataPattern(keyword, escape) + "%";
        String[] tableTypes = capabilities.isSupportsViews()
                ? new String[]{"TABLE", "VIEW"}
                : new String[]{"TABLE"};
        if (usesCatalogAsNamespace()) {
            try (ResultSet catalogs = metadata.getCatalogs()) {
                while (catalogs.next() && matches.size() < limit) {
                    collectTableMatches(
                            metadata,
                            catalogs.getString("TABLE_CAT"),
                            pattern,
                            normalized,
                            tableTypes,
                            limit,
                            matches);
                }
            }
            return;
        }
        collectTableMatches(
                metadata, null, pattern, normalized,
                tableTypes, limit, matches);
    }

    private void collectTableMatches(
            DatabaseMetaData metadata, String catalogFilter, String pattern,
            String normalized, String[] tableTypes, int limit,
            Map<String, TreeNode> matches) throws SQLException {
        try (ResultSet tables = metadata.getTables(
                catalogFilter, null, pattern, tableTypes)) {
            while (tables.next() && matches.size() < limit) {
                String name = tables.getString("TABLE_NAME");
                if (name == null || name.isBlank()
                        || !name.toLowerCase(Locale.ROOT).contains(normalized)) {
                    continue;
                }
                String catalog = tables.getString("TABLE_CAT");
                String schema = tables.getString("TABLE_SCHEM");
                String rawType = tables.getString("TABLE_TYPE");
                String type = rawType != null
                        && rawType.toUpperCase(Locale.ROOT).contains("VIEW")
                        ? "VIEW" : "TABLE";
                String path = searchResultPath(catalog, schema, name);
                TreeNode node = TreeNode.of(path, name, type, path);
                node.setIconType(type.toLowerCase(Locale.ROOT));
                node.setHasChildren(true);
                if (catalog != null && !catalog.isBlank()) {
                    node.getProperties().put("catalog", catalog);
                }
                if (schema != null && !schema.isBlank()) {
                    node.getProperties().put("schema", schema);
                }
                matches.putIfAbsent(type + ":" + path, node);
            }
        }
    }

    private String searchResultPath(
            String catalog, String schema, String table) {
        if ("MAXCOMPUTE".equalsIgnoreCase(driverInfo.getDbType())) {
            return table;
        }
        if (isSqlServer()) {
            return joinPath(catalog, schema, table);
        }
        if (usesCatalogAsNamespace()) {
            return joinPath(catalog, table);
        }
        return joinPath(schema, table);
    }

    private static String joinPath(String... parts) {
        return Arrays.stream(parts)
                .filter(part -> part != null && !part.isBlank())
                .collect(java.util.stream.Collectors.joining("/"));
    }

    private static String escapeMetadataPattern(
            String value, String escape) {
        if (escape == null || escape.isEmpty()) {
            return value;
        }
        return value
                .replace(escape, escape + escape)
                .replace("%", escape + "%")
                .replace("_", escape + "_");
    }

    private static int matchRank(String name, String normalized) {
        String candidate = name == null
                ? "" : name.toLowerCase(Locale.ROOT);
        if (candidate.equals(normalized)) {
            return 0;
        }
        if (candidate.startsWith(normalized)) {
            return 1;
        }
        return 2;
    }

    private static int typeRank(String type) {
        return switch (type == null ? "" : type.toUpperCase(Locale.ROOT)) {
            case "DATABASE" -> 0;
            case "SCHEMA" -> 1;
            case "TABLE" -> 2;
            case "VIEW" -> 3;
            default -> 4;
        };
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

        if (!usesCatalogAsNamespace() && !"MAXCOMPUTE".equals(driverInfo.getDbType())) {
            try (ResultSet schemas = metaData.getSchemas()) {
                while (schemas.next()) {
                    String schemaName = schemas.getString("TABLE_SCHEM");
                    if (schemaName == null || schemaName.isBlank()) {
                        continue;
                    }
                    TreeNode node = TreeNode.of(schemaName, schemaName, "SCHEMA", schemaName);
                    node.setIconType("schema");
                    node.setHasChildren(true);
                    nodes.add(node);
                }
            }
            if (!nodes.isEmpty()) {
                return nodes;
            }
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
     * <p>
     * 在表/视图列表之后，按能力声明追加「存储过程 / 函数 / 触发器」组节点（F6 元数据增强）。
     * 各组的展开由 {@link #getTreeNodes} 中的哨兵路径分发处理。
     * </p>
     */
    protected List<TreeNode> getSchemaChildren(Connection conn, String parentPath) throws SQLException {
        if (isSqlServer() && !parentPath.contains("/")) {
            return getCatalogSchemas(conn, parentPath);
        }
        MetadataNamespace owner = metadataNamespace(parentPath);
        String catalog = owner.catalog();
        String schema = owner.schema();
        List<TreeNode> nodes = new ArrayList<>(getTables(conn, catalog, schema));

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

    private List<TreeNode> getCatalogSchemas(
            Connection conn, String catalog) throws SQLException {
        List<TreeNode> nodes = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet schemas = metaData.getSchemas(catalog, "%")) {
            while (schemas.next()) {
                String schemaName = schemas.getString("TABLE_SCHEM");
                if (schemaName == null || schemaName.isBlank()) {
                    continue;
                }
                String path = catalog + "/" + schemaName;
                TreeNode node = TreeNode.of(
                        path, schemaName, "SCHEMA", path);
                node.setIconType("schema");
                node.setHasChildren(true);
                nodes.add(node);
            }
        }
        return nodes;
    }

    /**
     * 列出 schema 下的存储过程
     */
    protected List<TreeNode> getProcedures(Connection conn, String schema) {
        List<TreeNode> nodes = new ArrayList<>();
        try {
            DatabaseMetaData metaData = conn.getMetaData();
            MetadataNamespace owner = metadataNamespace(schema);
            try (ResultSet rs = metaData.getProcedures(
                    owner.catalog(), owner.schema(), "%")) {
                while (rs.next()) {
                    String name = rs.getString("PROCEDURE_NAME");
                    if (name == null)
                        continue;
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
            MetadataNamespace owner = metadataNamespace(schema);
            try (ResultSet rs = metaData.getFunctions(
                    owner.catalog(), owner.schema(), "%")) {
                while (rs.next()) {
                    String name = rs.getString("FUNCTION_NAME");
                    if (name == null)
                        continue;
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
            MetadataNamespace owner = metadataNamespace(schema);
            try (ResultSet rs = metaData.getTables(
                    owner.catalog(), owner.schema(), "%", new String[] { "TRIGGER" })) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (name == null)
                        continue;
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
            String actualCatalog = rs.getString("TABLE_CAT");
            String actualSchema = rs.getString("TABLE_SCHEM");
            String remarks = rs.getString("REMARKS");
            String nodeType = "TABLE".equals(tableType) ? "TABLE" : "VIEW";

            String namespace = actualCatalog != null && actualSchema != null
                    ? actualCatalog + "/" + actualSchema
                    : actualSchema != null ? actualSchema
                    : actualCatalog != null ? actualCatalog
                    : catalog != null && schema != null
                    ? catalog + "/" + schema
                    : schema != null ? schema : catalog;
            TreeNode node = TreeNode.of(
                    namespace != null ? namespace + "." + tableName : tableName,
                    tableName,
                    nodeType,
                    namespace != null ? namespace + "/" + tableName : tableName);
            node.setIconType(nodeType.toLowerCase());
            node.setHasChildren(true);
            if (actualCatalog != null && !actualCatalog.isBlank()) {
                node.getProperties().put("catalog", actualCatalog);
            }
            if (actualSchema != null && !actualSchema.isBlank()) {
                node.getProperties().put("schema", actualSchema);
            }
            if (remarks != null && !remarks.isBlank()) {
                node.getProperties().put("remarks", remarks);
            }
            nodes.add(node);
        }
        rs.close();

        return nodes;
    }

    @Override
    public List<ColumnMetadata> getTableColumns(Object connection, String schemaName, String tableName)
            throws Exception {
        Connection conn = (Connection) connection;
        DatabaseMetaData metaData = conn.getMetaData();

        // 按 (TABLE_CAT, TABLE_SCHEM) 归属分组收集。catalog 传 null 时，以 catalog 组织库的驱动
        // （如 MySQL/MaxCompute）会忽略 schema 参数并匹配所有库中的同名表，导致列成倍重复。
        Map<String, List<ColumnMetadata>> grouped = new LinkedHashMap<>();
        ResultSet rs = metaData.getColumns(metadataCatalog(schemaName), metadataSchema(schemaName), tableName, "%");
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
            String owner = rs.getString("TABLE_CAT") + "\u0000" + rs.getString("TABLE_SCHEM");
            grouped.computeIfAbsent(owner, k -> new ArrayList<>()).add(col);
        }
        rs.close();

        List<ColumnMetadata> columns = selectOwnerColumns(grouped, schemaName);

        // 获取主键信息
        rs = metaData.getPrimaryKeys(metadataCatalog(schemaName), metadataSchema(schemaName), tableName);
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

    /**
     * 从按归属分组的列集合中选出目标表的列：优先取 catalog 或 schema 与传入库名匹配的组，
     * 无匹配时取第一组；组内再按列名去重，兜底个别驱动返回完全重复行的情况。
     */
    private List<ColumnMetadata> selectOwnerColumns(Map<String, List<ColumnMetadata>> grouped, String schemaName) {
        List<ColumnMetadata> selected = null;
        if (schemaName != null) {
            MetadataNamespace target = metadataNamespace(schemaName);
            for (Map.Entry<String, List<ColumnMetadata>> entry : grouped.entrySet()) {
                String[] owner = entry.getKey().split("\u0000", -1);
                boolean catalogMatches = target.catalog() == null
                        || target.catalog().equalsIgnoreCase(owner[0]);
                boolean schemaMatches = target.schema() == null
                        || target.schema().equalsIgnoreCase(owner[1]);
                if (catalogMatches && schemaMatches) {
                    selected = entry.getValue();
                    break;
                }
            }
        }
        if (selected == null) {
            selected = grouped.isEmpty() ? new ArrayList<>() : grouped.values().iterator().next();
        }
        Map<String, ColumnMetadata> deduped = new LinkedHashMap<>();
        for (ColumnMetadata col : selected) {
            deduped.putIfAbsent(col.getName(), col);
        }
        return new ArrayList<>(deduped.values());
    }

    @Override
    public List<TableConstraintMetadata> getTableConstraints(
            Object connection, String schemaName, String tableName)
            throws Exception {
        Connection conn = (Connection) connection;
        return JdbcTableInspector.constraints(
                conn,
                metadataCatalog(schemaName),
                metadataSchema(schemaName),
                tableName);
    }

    @Override
    public String buildTablePreviewSql(
            Object connection, String schemaName, String tableName, int limit)
            throws Exception {
        Connection conn = (Connection) connection;
        return JdbcTableInspector.previewSql(
                conn, driverInfo.getDbType(),
                schemaName, tableName, limit);
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
            StatementRegistry.register(conn, stmt);

            boolean hasResultSet = stmt.execute(sql);
            if (hasResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();

                    // 构建唯一显示列名，避免 JOIN/别名产生重名列时 Map 覆盖数据。
                    Map<String, Integer> labelOccurrences = new HashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        String rawLabel = metaData.getColumnLabel(i);
                        result.addColumn(disambiguateColumnLabel(
                                rawLabel, labelOccurrences));
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
        } finally {
            StatementRegistry.unregister(conn);
        }

        result.setElapsedMs(System.currentTimeMillis() - startTime);
        return result;
    }

    static String disambiguateColumnLabel(String label,
            Map<String, Integer> occurrences) {
        String base = label == null || label.isBlank() ? "column" : label;
        String key = base.toLowerCase(Locale.ROOT);
        int occurrence = occurrences.merge(key, 1, Integer::sum);
        return occurrence == 1 ? base : base + " (" + occurrence + ")";
    }

    @Override
    public int executeUpdate(Object connection, String sql) throws Exception {
        Connection conn = (Connection) connection;
        try (Statement stmt = conn.createStatement()) {
            applyQueryTimeout(stmt);
            StatementRegistry.register(conn, stmt);
            return stmt.executeUpdate(sql);
        } finally {
            StatementRegistry.unregister(conn);
        }
    }

    @Override
    public String getTableDDL(Object connection, String schemaName, String tableName) throws Exception {
        Connection conn = (Connection) connection;
        String quote = identifierQuote(conn.getMetaData());
        String tableRef = qualifiedIdentifier(schemaName, tableName, quote);
        StringBuilder ddl = new StringBuilder();

        List<ColumnMetadata> columns = getTableColumns(conn, schemaName, tableName);
        List<String> pkCols = new ArrayList<>();
        for (ColumnMetadata column : columns) {
            if (column.isPrimaryKey()) {
                pkCols.add(column.getName());
            }
        }

        ddl.append("CREATE TABLE ").append(tableRef).append(" (\n");
        for (int i = 0; i < columns.size(); i++) {
            ColumnMetadata col = columns.get(i);
            ddl.append("    ").append(quoteIdentifier(col.getName(), quote))
                    .append(" ").append(col.getTypeName());
            if (shouldAppendColumnSize(col)) {
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
            if (i < columns.size() - 1 || !pkCols.isEmpty()) {
                ddl.append(",");
            }
            ddl.append("\n");
        }

        if (!pkCols.isEmpty()) {
            ddl.append("    PRIMARY KEY (")
                    .append(joinQuoted(pkCols, quote))
                    .append(")\n");
        }
        ddl.append(");");

        try {
            String indexDdl = buildIndexDdl(conn, schemaName, tableName);
            if (indexDdl != null && !indexDdl.isEmpty()) {
                ddl.append("\n").append(indexDdl);
            }
        } catch (Exception ignored) {
            // 部分驱动或权限下无法获取索引，不影响基础 DDL。
        }

        try {
            String tableComment = getTableComment(
                    (Object) conn, schemaName, tableName);
            if (tableComment != null && !tableComment.isEmpty()) {
                ddl.append("\n-- 表注释: ")
                        .append(tableComment.replace('\r', ' ').replace('\n', ' '));
            }
        } catch (Exception ignored) {
            // 注释属于补充信息，失败不影响基础 DDL。
        }

        return ddl.toString();
    }

    /**
     * 生成索引 DDL（CREATE [UNIQUE] INDEX ... ON table (cols)）
     *
     * <p>
     * 聚合 {@link DatabaseMetaData#getIndexInfo} 的结果，按索引名分组。主键索引（INDEX_NAME 为
     * null）跳过。
     * </p>
     */
    protected String buildIndexDdl(Connection conn, String schemaName, String tableName) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        Map<String, List<String>> indexColumns = new LinkedHashMap<>();
        Map<String, Boolean> indexUnique = new HashMap<>();
        String quote = identifierQuote(metaData);

        try (ResultSet rs = metaData.getIndexInfo(metadataCatalog(schemaName), metadataSchema(schemaName), tableName, false, true)) {
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
                // 跨库同名表可能返回重复索引行，按 (索引名, 列名) 去重
                List<String> cols = indexColumns.computeIfAbsent(indexName, k -> new ArrayList<>());
                if (!cols.contains(columnName)) {
                    cols.add(columnName);
                }
                indexUnique.putIfAbsent(indexName, !nonUnique);
            }
        }

        StringBuilder sb = new StringBuilder();
        String tableRef = qualifiedIdentifier(schemaName, tableName, quote);
        for (Map.Entry<String, List<String>> entry : indexColumns.entrySet()) {
            String indexName = entry.getKey();
            List<String> cols = entry.getValue();
            boolean unique = Boolean.TRUE.equals(indexUnique.get(indexName));
            sb.append("CREATE ").append(unique ? "UNIQUE " : "").append("INDEX ")
                    .append(quoteIdentifier(indexName, quote)).append(" ON ")
                    .append(tableRef).append(" (")
                    .append(joinQuoted(cols, quote)).append(");\n");
        }
        return sb.toString();
    }

    /**
     * 获取表注释（REMARKS）
     */
    @Override
    public String getTableComment(
            Object connection, String schemaName, String tableName)
            throws SQLException {
        return getTableComment(
                (Connection) connection, schemaName, tableName);
    }

    protected String getTableComment(
            Connection connection, String schemaName, String tableName)
            throws SQLException {
        Connection conn = connection;
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getTables(metadataCatalog(schemaName), metadataSchema(schemaName), tableName, null)) {
            if (rs.next()) {
                return rs.getString("REMARKS");
            }
        }
        return null;
    }

    private static boolean shouldAppendColumnSize(ColumnMetadata column) {
        if (column.getTypeName() == null || column.getTypeName().contains("(")
                || column.getColumnSize() <= 0 || column.getColumnSize() > 65_535) {
            return false;
        }
        final int sqlType;
        try {
            sqlType = Integer.parseInt(column.getDataType());
        } catch (NumberFormatException exception) {
            return false;
        }
        return switch (sqlType) {
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR,
                    Types.BINARY, Types.VARBINARY, Types.DECIMAL, Types.NUMERIC -> true;
            default -> false;
        };
    }

    static String identifierQuote(DatabaseMetaData metaData) {
        try {
            String quote = metaData.getIdentifierQuoteString();
            return quote == null || quote.isBlank() ? "" : quote.trim();
        } catch (SQLException exception) {
            return "";
        }
    }

    static String quoteIdentifier(String identifier, String quote) {
        if (identifier == null) {
            return "";
        }
        if (quote == null || quote.isBlank()) {
            return identifier;
        }
        String closing = "[".equals(quote) ? "]" : quote;
        return quote + identifier.replace(closing, closing + closing) + closing;
    }

    private static String qualifiedIdentifier(String namespace,
            String name, String quote) {
        StringBuilder builder = new StringBuilder();
        if (namespace != null && !namespace.isBlank()) {
            for (String part : namespace.split("/")) {
                if (!part.isBlank()) {
                    if (builder.length() > 0) {
                        builder.append('.');
                    }
                    builder.append(quoteIdentifier(part, quote));
                }
            }
        }
        if (builder.length() > 0) {
            builder.append('.');
        }
        return builder.append(quoteIdentifier(name, quote)).toString();
    }

    private static String joinQuoted(List<String> identifiers, String quote) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < identifiers.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(quoteIdentifier(identifiers.get(i), quote));
        }
        return builder.toString();
    }

    /**
     * MySQL 与 SQL Server 以 Catalog 作为数据库命名空间；其他通用 JDBC
     * 驱动优先以 Schema 导航。专用驱动可覆盖此方法。
     */
    protected boolean usesCatalogAsNamespace() {
        return "MYSQL".equalsIgnoreCase(driverInfo.getDbType())
                || "MSSQL".equalsIgnoreCase(driverInfo.getDbType());
    }

    private boolean isSqlServer() {
        return "MSSQL".equalsIgnoreCase(driverInfo.getDbType());
    }

    private MetadataNamespace metadataNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return new MetadataNamespace(null, null);
        }
        if (isSqlServer()) {
            int separator = namespace.indexOf('/');
            if (separator >= 0) {
                return new MetadataNamespace(
                        namespace.substring(0, separator),
                        namespace.substring(separator + 1));
            }
            return new MetadataNamespace(namespace, null);
        }
        return usesCatalogAsNamespace()
                ? new MetadataNamespace(namespace, null)
                : new MetadataNamespace(null, namespace);
    }

    private String metadataCatalog(String namespace) {
        return metadataNamespace(namespace).catalog();
    }

    private String metadataSchema(String namespace) {
        return metadataNamespace(namespace).schema();
    }

    private record MetadataNamespace(String catalog, String schema) {
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
