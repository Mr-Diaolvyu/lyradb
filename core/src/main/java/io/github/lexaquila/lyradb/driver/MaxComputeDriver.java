package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.PartitionMetadata;
import io.github.lexaquila.lyradb.model.dto.PartitionMetadataPage;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.TableCommentMetadata;
import io.github.lexaquila.lyradb.model.dto.TableConstraintMetadata;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;
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

    private static final int MAX_CATALOG_OBJECTS = 20_000;
    private static final int SDK_TABLE_BATCH_SIZE = 200;
    private static final int MAX_PARTITION_PAGE_SIZE = 500;
    private static final int MAX_PARTITION_OFFSET = 60_000;
    private static final String TABLE_METADATA_COLUMNS =
            "table_name, table_type, table_comment, is_partitioned";
    private static final String PROJECT_INFORMATION_SCHEMA_TABLES_SQL =
            "SELECT " + TABLE_METADATA_COLUMNS
                    + " FROM INFORMATION_SCHEMA.TABLES LIMIT "
                    + MAX_CATALOG_OBJECTS;
    private static final Pattern PROJECT_URL_PATTERN = Pattern.compile(
            "(?i)(?:[?&]|^)project=([^&;]+)");
    private static final Pattern PRIMARY_KEY_PATTERN = Pattern.compile(
            "(?is)\\bPRIMARY\\s+KEY\\s*\\(([^)]*)\\)");
    private static final Pattern TABLE_COMMENT_PATTERN = Pattern.compile(
            "(?is)\\)\\s*COMMENT\\s+'((?:''|[^'])*)'");
    private static final Pattern EXTENDED_INFO_LINE_PATTERN = Pattern.compile(
            "(?i)^([a-z][a-z0-9 _-]*)\\s*[:=\\t]\\s*(.*)$");

    /**
     * 目录批量加载得到的 SDK 元数据缓存。弱引用连接键避免已关闭连接被长期持有。
     */
    private final Map<Connection, Map<String, SdkTableMetadata>> sdkTableCache =
            Collections.synchronizedMap(new WeakHashMap<>());

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
        String normalized = keyword.toLowerCase(Locale.ROOT);
        int safeLimit = Math.max(1, Math.min(limit, 500));
        // INFORMATION_SCHEMA 同时返回表名和表注释，因此中文业务名称也能
        // 命中；不支持该系统视图时仅对安全的物理表名回退 SHOW TABLES LIKE。
        return getProjectTables(
                (Connection) connection, keyword).stream()
                .filter(node -> searchableText(node).contains(normalized))
                .limit(safeLimit)
                .toList();
    }

    /**
     * 获取表列表 (MaxCompute覆盖父类方法)
     * 
     * <p>
     * MaxCompute 的表/视图列表优先从 INFORMATION_SCHEMA 批量读取；
     * 系统视图不可用时回退 SHOW TABLES。
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
     * Schema 不存在。INFORMATION_SCHEMA 可在一次请求中取得当前 Project
     * 的对象类型和注释；不可用时再用 SHOW TABLES 保住基础目录。</p>
     */
    private List<TreeNode> getProjectTables(Connection conn) throws SQLException {
        List<String> failures = new ArrayList<>();
        String project = executionProject(conn);

        Boolean namespaceSchema = namespaceSchemaMode(conn);
        if (project != null && !Boolean.FALSE.equals(namespaceSchema)) {
            try {
                List<TreeNode> nodes = readInformationSchemaTables(
                        conn, tenantInformationSchemaSql(project),
                        "TENANT_INFORMATION_SCHEMA");
                return verifyBlankCommentsWithSdk(
                        conn, nodes, failures);
            } catch (SQLException exception) {
                failures.add("租户级 Information Schema 不可用："
                        + safeFailureReason(exception));
            }
        } else if (project == null) {
            failures.add("无法从当前 JDBC 连接识别执行 Project，"
                    + "未执行租户级 Information Schema 查询");
        } else {
            failures.add("当前 JDBC 连接未启用 schema namespace；"
                    + "租户级 Information Schema 官方入口要求"
                    + " odps.namespace.schema=true。为避免改变后续 SQL 语义，"
                    + "未在共享连接上执行 SET");
        }

        try {
            List<TreeNode> nodes = readInformationSchemaTables(
                    conn, PROJECT_INFORMATION_SCHEMA_TABLES_SQL,
                    "PROJECT_INFORMATION_SCHEMA");
            return verifyBlankCommentsWithSdk(conn, nodes, failures);
        } catch (SQLException exception) {
            failures.add("项目级 Information Schema 不可用："
                    + safeFailureReason(exception));
        }

        List<TreeNode> showNodes = readShowTables(conn, "SHOW TABLES");
        return enrichShowTablesWithSdk(conn, showNodes, failures);
    }

    private List<TreeNode> getProjectTables(
            Connection conn, String keyword) throws SQLException {
        String normalized = keyword.toLowerCase(Locale.ROOT);
        return getProjectTables(conn).stream()
                .filter(node -> searchableText(node).contains(normalized))
                .toList();
    }

    /**
     * 一次批量读取当前 Project 的对象类型和表级注释。
     *
     * <p>目录加载不能逐表执行 DESCRIBE，否则数千张表会形成 N+1 请求。
     * INFORMATION_SCHEMA 不可用时由调用方回退 SHOW TABLES。</p>
     */
    private List<TreeNode> readInformationSchemaTables(
            Connection conn, String sql, String source) throws SQLException {
        Map<String, TreeNode> uniqueNodes = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            int loaded = 0;
            while (rs.next() && loaded < MAX_CATALOG_OBJECTS) {
                String tableName = rs.getString(1);
                if (tableName == null || tableName.isBlank()) {
                    continue;
                }
                String rawType = rs.getString(2);
                String nodeType = rawType != null
                        && rawType.toUpperCase(Locale.ROOT).contains("VIEW")
                        ? "VIEW" : "TABLE";
                String normalizedName = tableName.trim();
                TreeNode node = tableNode(normalizedName, nodeType);
                String remarks = rs.getString(3);
                if (remarks != null && !remarks.isBlank()) {
                    node.getProperties().put("remarks", remarks.trim());
                    node.getProperties().put("remarksStatus", "AVAILABLE");
                } else {
                    node.getProperties().put("remarksStatus", "EMPTY");
                }
                Object partitioned = resultValue(rs, 4);
                if (partitioned != null) {
                    boolean isPartitioned = booleanValue(partitioned);
                    node.getProperties().put("partitioned", isPartitioned);
                    node.setHasChildren(isPartitioned);
                }
                applyMetadataStatus(node, source, "COMPLETE",
                        "已从 MaxCompute " + sourceLabel(source)
                                + " 批量读取表元数据");
                uniqueNodes.putIfAbsent(
                        nodeType + ":" + normalizedName, node);
                loaded++;
            }
        }
        List<TreeNode> nodes = new ArrayList<>(uniqueNodes.values());
        nodes.sort(Comparator.comparing(
                TreeNode::getName, String.CASE_INSENSITIVE_ORDER));
        return nodes;
    }

    private List<TreeNode> verifyBlankCommentsWithSdk(
            Connection conn,
            List<TreeNode> nodes,
            List<String> priorFailures) {
        List<String> names = nodes.stream()
                .filter(node -> !node.getProperties().containsKey("remarks"))
                .map(TreeNode::getName)
                .toList();
        if (names.isEmpty()) {
            return nodes;
        }
        SdkBatchLoadResult sdk = loadSdkTableMetadata(conn, names);
        for (TreeNode node : nodes) {
            if (node.getProperties().containsKey("remarks")) {
                continue;
            }
            SdkTableMetadata metadata = sdk.tables().get(
                    node.getName().toLowerCase(Locale.ROOT));
            if (metadata != null) {
                applySdkMetadata(node, metadata,
                        "Information Schema 注释为空，已使用实时 SDK 核验");
            } else {
                node.getProperties().put("remarksStatus", "UNAVAILABLE");
                node.getProperties().put("metadataStatus", "PARTIAL");
                node.getProperties().put("metadataReason", joinReasons(
                        priorFailures,
                        "Information Schema 返回空注释，SDK 核验失败："
                                + sdk.reason()));
            }
        }
        return nodes;
    }

    private List<TreeNode> enrichShowTablesWithSdk(
            Connection conn,
            List<TreeNode> nodes,
            List<String> priorFailures) {
        SdkBatchLoadResult sdk = loadSdkTableMetadata(
                conn, nodes.stream().map(TreeNode::getName).toList());
        for (TreeNode node : nodes) {
            SdkTableMetadata metadata = sdk.tables().get(
                    node.getName().toLowerCase(Locale.ROOT));
            if (metadata != null) {
                applySdkMetadata(node, metadata,
                        "Information Schema 不可用，已通过当前 JDBC 连接内置 SDK 批量补齐");
                continue;
            }
            node.getProperties().put("remarksStatus", "UNAVAILABLE");
            applyMetadataStatus(node, "SHOW_TABLES", "FALLBACK",
                    joinReasons(priorFailures,
                            "SDK 元数据不可用：" + sdk.reason()
                                    + "；SHOW TABLES 只能返回对象名，无法返回注释和分区属性"));
        }
        return nodes;
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
                    nodes.add(tableNode(tableName, "TABLE"));
                }
            }
        }
        nodes.sort(Comparator.comparing(
                TreeNode::getName, String.CASE_INSENSITIVE_ORDER));
        return nodes;
    }

    private static void applySdkMetadata(
            TreeNode node, SdkTableMetadata metadata, String reason) {
        node.setType(metadata.type());
        node.setIconType(metadata.type().toLowerCase(Locale.ROOT));
        node.setHasChildren(metadata.partitioned());
        node.getProperties().put("partitioned", metadata.partitioned());
        if (!metadata.partitionKeys().isEmpty()) {
            node.getProperties().put(
                    "partitionKeys", String.join(",", metadata.partitionKeys()));
        }
        if (metadata.comment() == null || metadata.comment().isBlank()) {
            node.getProperties().remove("remarks");
            node.getProperties().put("remarksStatus", "EMPTY");
        } else {
            node.getProperties().put("remarks", metadata.comment().trim());
            node.getProperties().put("remarksStatus", "AVAILABLE");
        }
        applyMetadataStatus(node, "MAXCOMPUTE_JAVA_SDK", "COMPLETE", reason);
    }

    private static void applyMetadataStatus(
            TreeNode node, String source, String status, String reason) {
        node.getProperties().put("metadataSource", source);
        node.getProperties().put("metadataStatus", status);
        node.getProperties().put("metadataReason", reason);
    }

    private static TreeNode tableNode(String tableName, String type) {
        TreeNode node = TreeNode.of(
                tableName, tableName, type, tableName);
        node.setIconType(type.toLowerCase(Locale.ROOT));
        node.setHasChildren(true);
        return node;
    }

    private static String tenantInformationSchemaSql(String project) {
        return "SELECT " + TABLE_METADATA_COLUMNS
                + " FROM SYSTEM_CATALOG.INFORMATION_SCHEMA.TABLES"
                + " WHERE table_catalog = '"
                + project.replace("'", "''") + "' LIMIT "
                + MAX_CATALOG_OBJECTS;
    }

    private String executionProject(Connection connection) {
        try {
            Object value = invokeNoArgs(connection, "getExecuteProject");
            if (hasText(value)) {
                return value.toString().trim();
            }
        } catch (ReflectiveOperationException ignored) {
            // 非官方连接包装器时继续使用标准 JDBC/URL。
        }
        try {
            Object odps = invokeNoArgs(connection, "getOdps");
            Object value = invokeNoArgs(odps, "getDefaultProject");
            if (hasText(value)) {
                return value.toString().trim();
            }
        } catch (ReflectiveOperationException ignored) {
            // 继续使用标准 JDBC。
        }
        try {
            String catalog = connection.getCatalog();
            if (catalog != null && !catalog.isBlank()) {
                return catalog.trim();
            }
        } catch (SQLException | UnsupportedOperationException ignored) {
            // 继续解析 URL。
        }
        try {
            String url = connection.getMetaData().getURL();
            Matcher matcher = PROJECT_URL_PATTERN.matcher(
                    url == null ? "" : url);
            if (matcher.find()) {
                return URLDecoder.decode(
                        matcher.group(1), StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignored) {
            // 调用方会记录“无法识别 Project”，不会静默。
        }
        return null;
    }

    private static Boolean namespaceSchemaMode(Connection connection) {
        try {
            Object value = invokeNoArgs(
                    connection, "isOdpsNamespaceSchema");
            return value instanceof Boolean bool ? bool : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private SdkBatchLoadResult loadSdkTableMetadata(
            Connection connection, Collection<String> rawNames) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (rawNames != null) {
            for (String name : rawNames) {
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                }
            }
        }
        if (names.isEmpty()) {
            return new SdkBatchLoadResult(Map.of(), "无需补充表元数据");
        }

        Map<String, SdkTableMetadata> cached = sdkCache(connection);
        Map<String, SdkTableMetadata> result = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            SdkTableMetadata metadata = cached.get(
                    name.toLowerCase(Locale.ROOT));
            if (metadata == null) {
                missing.add(name);
            } else {
                result.put(name.toLowerCase(Locale.ROOT), metadata);
            }
        }
        if (missing.isEmpty()) {
            return new SdkBatchLoadResult(result, "已复用当前连接的 SDK 元数据缓存");
        }

        List<String> failures = new ArrayList<>();
        try {
            Object odps = invokeNoArgs(connection, "getOdps");
            Object tables = invokeNoArgs(odps, "tables");
            String project = executionProject(connection);
            String schema = optionalText(invokeNoArgs(odps, "getCurrentSchema"));
            boolean namespaceSchema = optionalBoolean(
                    connection, "isOdpsNamespaceSchema");

            for (int start = 0; start < missing.size();
                    start += SDK_TABLE_BATCH_SIZE) {
                int end = Math.min(
                        missing.size(), start + SDK_TABLE_BATCH_SIZE);
                List<String> batch = missing.subList(start, end);
                try {
                    Object loaded = invokeLoadTables(
                            tables, project, schema,
                            namespaceSchema, batch);
                    if (!(loaded instanceof Iterable<?> iterable)) {
                        failures.add("SDK loadTables 返回了非列表结果");
                        continue;
                    }
                    for (Object table : iterable) {
                        SdkTableMetadata metadata = sdkTableMetadata(table);
                        if (metadata == null) {
                            continue;
                        }
                        String key = metadata.name()
                                .toLowerCase(Locale.ROOT);
                        result.put(key, metadata);
                        cached.put(key, metadata);
                    }
                } catch (ReflectiveOperationException exception) {
                    failures.add("SDK 批次 " + (start / SDK_TABLE_BATCH_SIZE + 1)
                            + " 失败：" + safeFailureReason(exception));
                }
            }
        } catch (ReflectiveOperationException exception) {
            failures.add("当前 JDBC 连接未提供可用的 MaxCompute SDK："
                    + safeFailureReason(exception));
        }

        String reason = failures.isEmpty()
                ? "SDK 批量元数据读取成功"
                : String.join("；", failures);
        return new SdkBatchLoadResult(result, reason);
    }

    private Map<String, SdkTableMetadata> sdkCache(Connection connection) {
        synchronized (sdkTableCache) {
            return sdkTableCache.computeIfAbsent(
                    connection, ignored -> Collections.synchronizedMap(
                            new LinkedHashMap<>()));
        }
    }

    private static Object invokeLoadTables(
            Object tables,
            String project,
            String schema,
            boolean namespaceSchema,
            Collection<String> names) throws ReflectiveOperationException {
        if (namespaceSchema && hasText(project) && hasText(schema)) {
            Method method = tables.getClass().getMethod(
                    "loadTables", String.class, String.class,
                    Collection.class);
            return invoke(method, tables, project, schema, names);
        }
        if (hasText(project)) {
            Method method = tables.getClass().getMethod(
                    "loadTables", String.class, Collection.class);
            return invoke(method, tables, project, names);
        }
        Method method = tables.getClass().getMethod(
                "loadTables", Collection.class);
        return invoke(method, tables, names);
    }

    private static SdkTableMetadata sdkTableMetadata(Object table)
            throws ReflectiveOperationException {
        String name = optionalText(invokeNoArgs(table, "getName"));
        if (name == null) {
            return null;
        }
        String comment = optionalText(invokeNoArgs(table, "getComment"));
        boolean partitioned = Boolean.TRUE.equals(
                invokeNoArgs(table, "isPartitioned"));
        boolean view = Boolean.TRUE.equals(
                invokeNoArgs(table, "isVirtualView"));
        List<String> partitionKeys = sdkPartitionKeys(table);
        return new SdkTableMetadata(
                name, view ? "VIEW" : "TABLE", comment,
                partitioned, partitionKeys, table);
    }

    private static List<String> sdkPartitionKeys(Object table) {
        try {
            Object schema = invokeNoArgs(table, "getSchema");
            Object columns = invokeNoArgs(schema, "getPartitionColumns");
            if (!(columns instanceof Iterable<?> iterable)) {
                return List.of();
            }
            List<String> keys = new ArrayList<>();
            for (Object column : iterable) {
                String name = optionalText(invokeNoArgs(column, "getName"));
                if (name != null) {
                    keys.add(name);
                }
            }
            return List.copyOf(keys);
        } catch (ReflectiveOperationException ignored) {
            return List.of();
        }
    }

    private static Object invokeNoArgs(Object target, String method)
            throws ReflectiveOperationException {
        if (target == null) {
            throw new NoSuchMethodException(method + "（目标为空）");
        }
        return invoke(target.getClass().getMethod(method), target);
    }

    private static Object invoke(
            Method method, Object target, Object... arguments)
            throws ReflectiveOperationException {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ReflectiveOperationException reflective) {
                throw reflective;
            }
            throw new ReflectiveOperationException(cause);
        }
    }

    private static boolean optionalBoolean(Object target, String method) {
        try {
            return Boolean.TRUE.equals(invokeNoArgs(target, method));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static boolean hasText(Object value) {
        return value != null && !value.toString().isBlank();
    }

    private static String optionalText(Object value) {
        return hasText(value) ? value.toString().trim() : null;
    }

    private static Object resultValue(ResultSet resultSet, int index) {
        try {
            return resultSet.getObject(index);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(value.toString())
                || "1".equals(value.toString());
    }

    private static String sourceLabel(String source) {
        return switch (source) {
            case "TENANT_INFORMATION_SCHEMA" -> "租户级 Information Schema";
            case "PROJECT_INFORMATION_SCHEMA" -> "项目级 Information Schema";
            default -> source;
        };
    }

    private static String safeFailureReason(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null
                && current != current.getCause()) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        } else {
            message = current.getClass().getSimpleName() + ": " + message;
        }
        String scrubbed = message
                .replaceAll("(?i)(access[_-]?key|access[_-]?id|token|password)"
                        + "\\s*[=:]\\s*[^,;\\s]+", "$1=***")
                .replaceAll("[\\r\\n]+", " ").trim();
        return scrubbed.length() > 240
                ? scrubbed.substring(0, 240) + "…" : scrubbed;
    }

    private static String joinReasons(
            List<String> previous, String current) {
        List<String> reasons = new ArrayList<>();
        if (previous != null) {
            reasons.addAll(previous);
        }
        if (current != null && !current.isBlank()) {
            reasons.add(current);
        }
        return String.join("；", reasons);
    }

    private record SdkTableMetadata(
            String name,
            String type,
            String comment,
            boolean partitioned,
            List<String> partitionKeys,
            Object sdkTable) {

        private SdkTableMetadata {
            partitionKeys = partitionKeys == null
                    ? List.of() : List.copyOf(partitionKeys);
        }
    }

    private record SdkBatchLoadResult(
            Map<String, SdkTableMetadata> tables,
            String reason) {

        private SdkBatchLoadResult {
            tables = tables == null ? Map.of() : Map.copyOf(tables);
            reason = reason == null ? "" : reason;
        }
    }

    private static String searchableText(TreeNode node) {
        Object remarks = node.getProperties() == null
                ? null : node.getProperties().get("remarks");
        return ((node.getName() == null ? "" : node.getName()) + " "
                + (remarks == null ? "" : remarks))
                .toLowerCase(Locale.ROOT);
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

    /** 校验 MaxCompute 表标识符（DESCRIBE/SHOW 无法参数化），仅允许字母数字下划线与点，防注入 */
    private static String safeIdentifier(String identifier) throws SQLException {
        if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
            throw new SQLException("非法的表标识符: " + identifier);
        }
        return identifier;
    }

    /**
     * 获取表的扩展信息 (大小/行数/Lifecycle)
     */
    private Map<String, Object> getExtendedTableInfo(Connection conn, String tableName) throws SQLException {
        Map<String, Object> info = new java.util.HashMap<>();
        try (Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("DESCRIBE EXTENDED " + safeIdentifier(tableName))) {
                ResultSetMetaData metadata = rs.getMetaData();
                int columnCount = metadata == null ? 2 : metadata.getColumnCount();
                while (rs.next()) {
                    List<String> values = new ArrayList<>();
                    int boundedColumnCount = Math.max(
                            1, Math.min(columnCount, 8));
                    for (int index = 1; index <= boundedColumnCount; index++) {
                        try {
                            values.add(rs.getString(index));
                        } catch (SQLException exception) {
                            if (index == 1) {
                                throw exception;
                            }
                            break;
                        }
                    }
                    for (int index = 0; index + 1 < values.size(); index++) {
                        putExtendedInfo(
                                info, values.get(index), values.get(index + 1));
                    }
                    for (String value : values) {
                        if (value != null) {
                            parseExtendedInfoPayload(info, value);
                        }
                    }
                }
            }
        }
        return info;
    }

    private static void parseExtendedInfoPayload(
            Map<String, Object> info, String payload) {
        for (String rawLine : payload.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.startsWith("|") && line.endsWith("|")) {
                String[] cells = line.substring(1, line.length() - 1)
                        .split("\\|");
                if (cells.length >= 2) {
                    for (int index = 0; index + 1 < cells.length; index++) {
                        putExtendedInfo(
                                info, cells[index], cells[index + 1]);
                    }
                    continue;
                }
                if (cells.length == 1) {
                    line = cells[0].trim();
                }
            }
            Matcher matcher = EXTENDED_INFO_LINE_PATTERN.matcher(line);
            if (matcher.matches()) {
                putExtendedInfo(info, matcher.group(1), matcher.group(2));
            }
        }
    }

    private static void putExtendedInfo(
            Map<String, Object> info, String key, String value) {
        if (key == null || value == null) {
            return;
        }
        String normalizedKey = key.trim().toLowerCase(Locale.ROOT);
        String normalizedValue = value.trim();
        if (normalizedKey.contains("comment")
                || normalizedKey.contains("description")) {
            info.put("remarksStatus",
                    normalizedValue.isBlank() ? "EMPTY" : "AVAILABLE");
            if (!normalizedValue.isBlank()) {
                info.put("remarks", normalizedValue);
            }
        } else if (normalizedValue.isBlank()) {
            return;
        } else if (normalizedKey.contains("size")) {
            info.put("tableSize", normalizedValue);
        } else if (normalizedKey.contains("lifecycle")) {
            info.put("lifecycle", normalizedValue);
        } else if (normalizedKey.contains("rows")
                || normalizedKey.contains("count")) {
            info.put("rowCount", normalizedValue);
        }
    }

    @Override
    public PartitionMetadataPage listTablePartitions(
            Object connection,
            String schemaName,
            String tableName,
            int offset,
            int limit) throws Exception {
        if (offset < 0 || offset > MAX_PARTITION_OFFSET) {
            throw new IllegalArgumentException(
                    "分区分页 offset 必须在 0 到 "
                            + MAX_PARTITION_OFFSET + " 之间");
        }
        int safeLimit = Math.max(
                1, Math.min(limit, MAX_PARTITION_PAGE_SIZE));
        String tableRef = qualifiedTableIdentifier(schemaName, tableName);
        Connection conn = (Connection) connection;
        PartitionMetadataPage page = basePartitionPage(
                schemaName, tableName, offset, safeLimit);

        SdkBatchLoadResult sdk = loadSdkTableMetadata(
                conn, List.of(tableName));
        SdkTableMetadata table = sdk.tables().get(
                tableName.toLowerCase(Locale.ROOT));
        List<String> partitionKeys = List.of();
        String sdkPartitionReason = sdk.reason();
        if (table != null) {
            page.setPartitioned(table.partitioned());
            page.setPartitionKeys(table.partitionKeys());
            page.setMetadataSource("MAXCOMPUTE_JAVA_SDK");
            page.setMetadataStatus("COMPLETE");
            page.setOrdering("PARTITION_SPEC_DESC");
            page.setMetadataReason("已通过当前 JDBC 连接内置 SDK 有界读取分区元数据");
            if (!table.partitioned()) {
                return page;
            }
            partitionKeys = table.partitionKeys();
            try {
                SdkPartitionPage sdkPage = readSdkPartitionPage(
                        table.sdkTable(), offset, safeLimit);
                page.setItems(sdkPage.items());
                page.setHasMore(sdkPage.hasMore());
                if (page.getPartitionKeys().isEmpty()
                        && !page.getItems().isEmpty()) {
                    page.setPartitionKeys(new ArrayList<>(
                            page.getItems().get(0).getValues().keySet()));
                }
                return page;
            } catch (ReflectiveOperationException exception) {
                sdkPartitionReason = "SDK 已确认分区表，但分页读取失败："
                        + safeFailureReason(exception);
            }
        } else {
            try {
                partitionKeys = partitionKeysFromDescribe(conn, tableRef);
            } catch (SQLException exception) {
                partitionKeys = List.of();
            }
        }
        page.setPartitionKeys(partitionKeys);

        try {
            ShowPartitionPage show = readShowPartitionPage(
                    conn, tableRef, offset, safeLimit);
            page.setItems(show.items());
            page.setHasMore(show.hasMore());
            if (partitionKeys.isEmpty() && !show.items().isEmpty()) {
                partitionKeys = new ArrayList<>(
                        show.items().get(0).getValues().keySet());
                page.setPartitionKeys(partitionKeys);
            }
            page.setPartitioned(
                    !partitionKeys.isEmpty() || !show.items().isEmpty());
            page.setMetadataSource("SHOW_PARTITIONS");
            page.setMetadataStatus("FALLBACK");
            page.setOrdering("SERVICE_DEFINED");
            page.setMetadataReason("SDK 分区元数据不可用：" + sdkPartitionReason
                    + "；已回退 SHOW PARTITIONS，结果按读取行数硬限制");
            return page;
        } catch (SQLException showFailure) {
            page.setPartitioned(!partitionKeys.isEmpty());
            page.setMetadataSource(partitionKeys.isEmpty()
                    ? "UNAVAILABLE" : "DESCRIBE_TABLE");
            page.setMetadataStatus(partitionKeys.isEmpty()
                    ? "PARTIAL" : "COMPLETE");
            page.setMetadataReason("SDK 分区元数据不可用：" + sdkPartitionReason
                    + "；SHOW PARTITIONS 不可用："
                    + safeFailureReason(showFailure)
                    + (partitionKeys.isEmpty()
                    ? "；无法确认该表是否为分区表"
                    : "；DESCRIBE 已确认分区字段，但暂时无法列出分区值"));
            return page;
        }
    }

    private static PartitionMetadataPage basePartitionPage(
            String schemaName, String tableName, int offset, int limit) {
        PartitionMetadataPage page = new PartitionMetadataPage();
        page.setSchema(schemaName);
        page.setTable(tableName);
        page.setOffset(offset);
        page.setLimit(limit);
        return page;
    }

    private static SdkPartitionPage readSdkPartitionPage(
            Object sdkTable, int offset, int limit)
            throws ReflectiveOperationException {
        Method iteratorMethod = null;
        for (Method method : sdkTable.getClass().getMethods()) {
            if ("getPartitionIterator".equals(method.getName())
                    && method.getParameterCount() == 4) {
                iteratorMethod = method;
                break;
            }
        }
        if (iteratorMethod == null) {
            throw new NoSuchMethodException(
                    "Table.getPartitionIterator(PartitionSpec,boolean,Long,Long)");
        }
        long boundedRead = (long) offset + limit + 1L;
        long batchSize = Math.max(1L, Math.min(1_000L, limit + 1L));
        Object rawIterator = invoke(
                iteratorMethod, sdkTable, null, true,
                Long.valueOf(batchSize), Long.valueOf(boundedRead));
        if (!(rawIterator instanceof Iterator<?> iterator)) {
            throw new ReflectiveOperationException(
                    "SDK 分区迭代器类型不兼容");
        }
        List<PartitionMetadata> items = new ArrayList<>();
        int skipped = 0;
        boolean hasMore = false;
        while (iterator.hasNext()) {
            Object partition = iterator.next();
            if (skipped < offset) {
                skipped++;
                continue;
            }
            if (items.size() >= limit) {
                hasMore = true;
                break;
            }
            Object rawSpec = invokeNoArgs(partition, "getPartitionSpec");
            items.add(partitionMetadata(String.valueOf(rawSpec)));
        }
        return new SdkPartitionPage(items, hasMore);
    }

    private static ShowPartitionPage readShowPartitionPage(
            Connection connection,
            String tableRef,
            int offset,
            int limit) throws SQLException {
        List<PartitionMetadata> items = new ArrayList<>();
        int skipped = 0;
        boolean hasMore = false;
        int hardRowLimit = offset + limit + 1;
        try (Statement statement = connection.createStatement()) {
            try {
                statement.setMaxRows(hardRowLimit);
            } catch (SQLException | UnsupportedOperationException ignored) {
                // 读取循环仍执行硬上限。
            }
            try (ResultSet rows = statement.executeQuery(
                    "SHOW PARTITIONS " + tableRef)) {
                int read = 0;
                while (rows.next() && read < hardRowLimit) {
                    String payload = rows.getString(1);
                    if (payload == null || payload.isBlank()) {
                        continue;
                    }
                    for (String rawLine : payload.split("\\R")) {
                        String spec = rawLine == null ? "" : rawLine.trim();
                        if (spec.isEmpty()) {
                            continue;
                        }
                        read++;
                        if (skipped < offset) {
                            skipped++;
                            continue;
                        }
                        if (items.size() >= limit) {
                            hasMore = true;
                            break;
                        }
                        items.add(partitionMetadata(spec));
                    }
                    if (hasMore) {
                        break;
                    }
                }
            }
        }
        return new ShowPartitionPage(items, hasMore);
    }

    private static PartitionMetadata partitionMetadata(String rawSpec) {
        PartitionMetadata metadata = new PartitionMetadata();
        metadata.setSpec(rawSpec == null ? "" : rawSpec.trim());
        metadata.setValues(parsePartitionSpec(rawSpec));
        return metadata;
    }

    private static Map<String, String> parsePartitionSpec(String rawSpec) {
        if (rawSpec == null || rawSpec.isBlank()) {
            return Map.of();
        }
        List<String> segments = splitPartitionSegments(rawSpec.trim());
        Map<String, String> values = new LinkedHashMap<>();
        for (String segment : segments) {
            int separator = segment.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException(
                        "无法解析 MaxCompute 分区：" + rawSpec);
            }
            String key = segment.substring(0, separator).trim();
            String value = segment.substring(separator + 1).trim();
            try {
                safeIdentifier(key);
            } catch (SQLException exception) {
                throw new IllegalArgumentException(exception.getMessage(), exception);
            }
            if ((value.startsWith("'") && value.endsWith("'"))
                    || (value.startsWith("\"") && value.endsWith("\""))) {
                value = value.substring(1, value.length() - 1);
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("分区字段重复：" + key);
            }
        }
        return values;
    }

    private static List<String> splitPartitionSegments(String spec) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < spec.length(); index++) {
            char value = spec.charAt(index);
            if ((value == '\'' || value == '\"')) {
                if (quote == 0) {
                    quote = value;
                } else if (quote == value) {
                    if (index + 1 < spec.length()
                            && spec.charAt(index + 1) == value) {
                        current.append(value);
                        index++;
                        continue;
                    }
                    quote = 0;
                }
            }
            if (quote == 0 && (value == '/' || value == ',')) {
                if (!current.toString().isBlank()) {
                    segments.add(current.toString().trim());
                }
                current.setLength(0);
            } else {
                current.append(value);
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("分区值引号未闭合：" + spec);
        }
        if (!current.toString().isBlank()) {
            segments.add(current.toString().trim());
        }
        return segments;
    }

    private List<String> partitionKeysFromDescribe(
            Connection connection, String tableRef) throws SQLException {
        return readDescribeColumns(connection, tableRef).stream()
                .filter(DescribeColumn::partition)
                .map(DescribeColumn::name)
                .toList();
    }

    private static SQLException asSqlException(
            String message, Exception exception) {
        return exception instanceof SQLException sql
                ? sql : new SQLException(message, exception);
    }

    private record SdkPartitionPage(
            List<PartitionMetadata> items, boolean hasMore) {

        private SdkPartitionPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    private record ShowPartitionPage(
            List<PartitionMetadata> items, boolean hasMore) {

        private ShowPartitionPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * 获取表的分区键列表 (表级展开时显示分区字段)
     */
    private List<TreeNode> getPartitions(Connection conn, String parentPath) throws SQLException {
        List<TreeNode> nodes = new ArrayList<>();
        String tableName = parentPath;

        PartitionMetadataPage page;
        try {
            page = listTablePartitions(conn, null, tableName, 0, 1);
        } catch (Exception exception) {
            throw asSqlException("读取 MaxCompute 分区字段失败", exception);
        }
        List<String> partitionKeys = page.getPartitionKeys();
        for (String key : partitionKeys) {
            TreeNode node = TreeNode.of(
                    parentPath + "/" + key,
                    key,
                    "PARTITION",
                    parentPath + "/" + key);
            node.setIconType("partition");
            node.setHasChildren(true);
            node.getProperties().put("partitionKey", key);
            node.getProperties().put(
                    "metadataSource", page.getMetadataSource());
            node.getProperties().put(
                    "metadataStatus", page.getMetadataStatus());
            node.getProperties().put(
                    "metadataReason", page.getMetadataReason());
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
        PartitionMetadataPage page;
        try {
            page = listTablePartitions(
                    conn, null, tableName, 0, MAX_PARTITION_PAGE_SIZE);
        } catch (Exception exception) {
            throw asSqlException("读取 MaxCompute 分区值失败", exception);
        }
        for (PartitionMetadata partition : page.getItems()) {
            String spec = partition.getSpec();
            TreeNode node = TreeNode.of(
                    parentPath + "/" + spec,
                    spec,
                    "PARTITION",
                    parentPath + "/" + spec);
            node.setIconType("partition");
            node.setHasChildren(false);
            node.getProperties().put("partitionSpec", spec);
            node.getProperties().put("partitionValues", partition.getValues());
            node.getProperties().put("metadataSource", page.getMetadataSource());
            node.getProperties().put("metadataStatus", page.getMetadataStatus());
            node.getProperties().put("metadataReason", page.getMetadataReason());
            node.getProperties().put("hasMore", page.isHasMore());
            nodes.add(node);
        }
        return nodes;
    }

    @Override
    protected String getTableComment(
            Connection conn, String schemaName, String tableName)
            throws SQLException {
        return readTableCommentMetadata(conn, schemaName, tableName)
                .getRemarks();
    }

    @Override
    public TableCommentMetadata getTableCommentMetadata(
            Object connection, String schemaName, String tableName)
            throws Exception {
        return readTableCommentMetadata(
                (Connection) connection, schemaName, tableName);
    }

    private TableCommentMetadata readTableCommentMetadata(
            Connection conn, String schemaName, String tableName)
            throws SQLException {
        String tableRef = qualifiedTableIdentifier(schemaName, tableName);
        List<String> failures = new ArrayList<>();

        SdkBatchLoadResult sdk = loadSdkTableMetadata(
                conn, List.of(tableName));
        SdkTableMetadata sdkTable = sdk.tables().get(
                tableName.toLowerCase(Locale.ROOT));
        if (sdkTable != null) {
            return commentMetadata(
                    sdkTable.comment(), "MAXCOMPUTE_JAVA_SDK", "COMPLETE",
                    sdkTable.comment() == null
                            ? "SDK 已确认该表未设置注释"
                            : "SDK 已读取表注释");
        }
        failures.add("SDK 不可用：" + sdk.reason());

        try {
            Map<String, Object> extended = getExtendedTableInfo(
                    conn, tableRef);
            Object status = extended.get("remarksStatus");
            Object value = extended.get("remarks");
            if (value != null && !value.toString().isBlank()) {
                return commentMetadata(
                        value.toString(), "DESCRIBE_TABLE", "COMPLETE",
                        "DESC EXTENDED 已读取表注释");
            }
            if ("EMPTY".equals(status)) {
                return commentMetadata(
                        null, "DESCRIBE_TABLE", "COMPLETE",
                        "DESC EXTENDED 已确认该表未设置注释");
            }
            failures.add("DESC EXTENDED 返回结果中未识别到 TableComment");
        } catch (SQLException exception) {
            failures.add("DESC EXTENDED 不可用："
                    + safeFailureReason(exception));
        }
        try {
            String nativeDdl = getNativeTableDdl(conn, tableRef);
            Matcher matcher = TABLE_COMMENT_PATTERN.matcher(nativeDdl);
            if (matcher.find()) {
                String comment = matcher.group(1)
                        .replace("''", "'").trim();
                return commentMetadata(
                        comment.isEmpty() ? null : comment,
                        "SHOW_CREATE_TABLE", "COMPLETE",
                        comment.isEmpty()
                                ? "SHOW CREATE TABLE 已确认该表未设置注释"
                                : "SHOW CREATE TABLE 已读取表注释");
            }
            if (!nativeDdl.isBlank()) {
                return commentMetadata(
                        null, "SHOW_CREATE_TABLE", "COMPLETE",
                        "SHOW CREATE TABLE 已确认该表未设置注释");
            }
            failures.add("SHOW CREATE TABLE 返回空结果");
        } catch (SQLException exception) {
            failures.add("SHOW CREATE TABLE 不可用："
                    + safeFailureReason(exception));
        }
        return commentMetadata(
                null, "UNAVAILABLE", "PARTIAL",
                String.join("；", failures));
    }

    private static TableCommentMetadata commentMetadata(
            String remarks, String source, String status, String reason) {
        TableCommentMetadata metadata = new TableCommentMetadata();
        metadata.setRemarks(remarks);
        metadata.setMetadataSource(source);
        metadata.setMetadataStatus(status);
        metadata.setMetadataReason(reason);
        metadata.setRemarksStatus(remarks == null || remarks.isBlank()
                ? ("COMPLETE".equals(status) ? "EMPTY" : "UNAVAILABLE")
                : "AVAILABLE");
        return metadata;
    }

    @Override
    public List<ColumnMetadata> getTableColumns(
            Object connection, String schemaName, String tableName)
            throws Exception {
        Connection conn = (Connection) connection;

        // JDBC 列元数据通常更快，但部分 MaxCompute JDBC 版本只返回字段名和
        // 类型，REMARKS 全为空。此时必须用 DESCRIBE 补齐字段注释，而不是把
        // “拿到字段”误判成“元数据完整”。
        List<ColumnMetadata> jdbcColumns = List.of();
        try {
            jdbcColumns = super.getTableColumns(
                    connection, null, tableName);
        } catch (SQLException ignored) {
            // 继续使用 DESCRIBE。
        }
        String tableRef = qualifiedTableIdentifier(schemaName, tableName);
        boolean needsDescribe = jdbcColumns.isEmpty()
                || jdbcColumns.stream().anyMatch(column ->
                column.getRemarks() == null || column.getRemarks().isBlank());
        if (!needsDescribe) {
            return jdbcColumns;
        }

        List<DescribeColumn> described = readDescribeColumns(conn, tableRef);
        if (jdbcColumns.isEmpty()) {
            return described.stream()
                    .map(column -> toColumnMetadata(
                            column, schemaName, tableName))
                    .toList();
        }

        Map<String, DescribeColumn> describedByName = new LinkedHashMap<>();
        for (DescribeColumn column : described) {
            describedByName.putIfAbsent(
                    column.name().toLowerCase(Locale.ROOT), column);
        }
        for (ColumnMetadata column : jdbcColumns) {
            DescribeColumn fallback = describedByName.get(
                    column.getName().toLowerCase(Locale.ROOT));
            if (fallback == null) {
                continue;
            }
            String remarks = column.getRemarks();
            if (remarks == null || remarks.isBlank()) {
                remarks = fallback.remarks();
            }
            if (fallback.partition()) {
                remarks = mergeRemark("分区字段", remarks);
            }
            column.setRemarks(remarks);
        }
        return jdbcColumns;
    }

    private List<DescribeColumn> readDescribeColumns(
            Connection conn, String tableRef) throws SQLException {
        List<DescribeColumn> columns = new ArrayList<>();
        boolean partitionSection = false;

        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("DESCRIBE " + tableRef)) {
            int columnCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null && name.contains("\n")) {
                    columns.addAll(parseDescribePayload(name));
                    continue;
                }
                if (isPartitionMarker(name)) {
                    partitionSection = true;
                    continue;
                }
                String typeName = columnCount >= 2 ? rs.getString(2) : null;
                String remarks = columnCount >= 3 ? rs.getString(3) : null;
                DescribeColumn parsed = describeColumn(
                        name, typeName, remarks, partitionSection);
                if (parsed == null) {
                    continue;
                }
                columns.add(parsed);
            }
        }
        return columns;
    }

    private static List<DescribeColumn> parseDescribePayload(String payload) {
        List<DescribeColumn> columns = new ArrayList<>();
        boolean partitionSection = false;
        for (String rawLine : payload.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank()) {
                continue;
            }
            if (isPartitionMarker(line)) {
                partitionSection = true;
                continue;
            }
            if (line.startsWith("+") || line.matches("[-=]{3,}")) {
                continue;
            }
            String name;
            String typeName;
            String remarks = null;
            if (line.startsWith("|") && line.endsWith("|")) {
                String[] cells = line.substring(1, line.length() - 1)
                        .split("\\|", -1);
                if (cells.length < 2) {
                    continue;
                }
                name = cells[0].trim();
                typeName = cells[1].trim();
                if (cells.length >= 3) {
                    remarks = cells[2].trim();
                }
            } else {
                String[] cells = line.split("\\s+", 3);
                if (cells.length < 2) {
                    continue;
                }
                name = cells[0];
                typeName = cells[1];
                if (cells.length == 3) {
                    remarks = cells[2];
                }
            }
            DescribeColumn parsed = describeColumn(
                    name, typeName, remarks, partitionSection);
            if (parsed != null) {
                columns.add(parsed);
            }
        }
        return columns;
    }

    private static DescribeColumn describeColumn(
            String name, String typeName, String remarks,
            boolean partitionSection) {
        if (name == null || name.isBlank()
                || typeName == null || typeName.isBlank()) {
            return null;
        }
        String normalizedName = name.trim();
        if (normalizedName.startsWith("#")
                || "col_name".equalsIgnoreCase(normalizedName)
                || "field".equalsIgnoreCase(normalizedName)
                || "data_type".equalsIgnoreCase(typeName.trim())
                || "type".equalsIgnoreCase(typeName.trim())) {
            return null;
        }
        return new DescribeColumn(
                normalizedName, typeName.trim(),
                remarks == null || remarks.isBlank() ? null : remarks.trim(),
                partitionSection);
    }

    private static boolean isPartitionMarker(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.replace('|', ' ').trim()
                .toLowerCase(Locale.ROOT);
        return (normalized.startsWith("#")
                && normalized.contains("partition"))
                || normalized.startsWith("partition columns")
                || normalized.startsWith("partition column");
    }

    private static ColumnMetadata toColumnMetadata(
            DescribeColumn source, String schemaName, String tableName) {
        ColumnMetadata column = new ColumnMetadata();
        column.setName(source.name());
        column.setDataType(String.valueOf(Types.OTHER));
        column.setTypeName(source.typeName());
        column.setNullable(!source.typeName().toUpperCase(Locale.ROOT)
                .contains("NOT NULL"));
        column.setRemarks(source.partition()
                ? mergeRemark("分区字段", source.remarks())
                : source.remarks());
        column.setSchemaName(schemaName);
        column.setTableName(tableName);
        return column;
    }

    private record DescribeColumn(
            String name, String typeName, String remarks,
            boolean partition) {
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
            Object connection, String schemaName, String tableName, int limit)
            throws Exception {
        PartitionMetadataPage partitions = listTablePartitions(
                connection, schemaName, tableName, 0, 1);
        if (partitions.isPartitioned()) {
            throw new IllegalStateException(
                    "MaxCompute 分区表禁止无分区预览，请先选择一个完整分区");
        }
        if (!"COMPLETE".equals(partitions.getMetadataStatus())) {
            throw new IllegalStateException(
                    "无法可靠确认 MaxCompute 表是否分区，已阻止全表预览："
                            + partitions.getMetadataReason());
        }
        int safeLimit = Math.max(
                1, Math.min(limit, JdbcTableInspector.MAX_PREVIEW_ROWS));
        return "SELECT * FROM "
                + qualifiedTableIdentifier(schemaName, tableName)
                + " TABLESAMPLE (" + safeLimit + " ROWS)";
    }

    @Override
    public String buildPartitionPreviewSql(
            Object connection,
            String schemaName,
            String tableName,
            String partitionSpec,
            int limit) throws Exception {
        Map<String, String> supplied = parsePartitionSpec(partitionSpec);
        PartitionMetadataPage metadata = listTablePartitions(
                connection, schemaName, tableName, 0, 1);
        if (!metadata.isPartitioned()) {
            throw new IllegalArgumentException(
                    "目标表不是已确认的 MaxCompute 分区表："
                            + metadata.getMetadataReason());
        }
        if (metadata.getPartitionKeys().isEmpty()) {
            throw new IllegalStateException(
                    "无法取得 MaxCompute 分区字段，已阻止预览："
                            + metadata.getMetadataReason());
        }

        Map<String, Map.Entry<String, String>> normalized =
                new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : supplied.entrySet()) {
            normalized.put(entry.getKey().toLowerCase(Locale.ROOT), entry);
        }
        Set<String> required = metadata.getPartitionKeys().stream()
                .map(key -> key.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        if (!normalized.keySet().equals(required)) {
            throw new IllegalArgumentException(
                    "必须提供完整且仅包含以下分区字段的分区规范："
                            + String.join(",", metadata.getPartitionKeys()));
        }

        List<String> predicates = new ArrayList<>();
        for (String key : metadata.getPartitionKeys()) {
            Map.Entry<String, String> entry = normalized.get(
                    key.toLowerCase(Locale.ROOT));
            String value = entry.getValue() == null ? "" : entry.getValue();
            predicates.add(safeIdentifier(key) + " = '"
                    + value.replace("'", "''") + "'");
        }
        int safeLimit = Math.max(
                1, Math.min(limit, JdbcTableInspector.MAX_PREVIEW_ROWS));
        return "SELECT * FROM "
                + qualifiedTableIdentifier(schemaName, tableName)
                + " WHERE " + String.join(" AND ", predicates)
                + " LIMIT " + safeLimit;
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
                List<String> partitionNames = new ArrayList<>();
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
                            partitionNames.add(colName.trim());
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

                if (!partitionNames.isEmpty()) {
                    ddl.append("-- Partition keys: ")
                            .append(String.join(",", partitionNames))
                            .append("\n");
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
