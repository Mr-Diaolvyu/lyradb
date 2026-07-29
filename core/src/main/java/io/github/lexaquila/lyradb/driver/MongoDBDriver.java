package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;

import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * MongoDB驱动实现
 *
 * <p>
 * MongoDB是一等公民之一。通过反射调用MongoDB Java Driver的API，
 * 将MongoDB的文档操作适配为统一的DatabaseDriver接口。
 * </p>
 *
 * <p>
 * 导航树结构：连接 → Database → Collection → 文档
 * </p>
 *
 * <p>
 * 核心适配逻辑：
 * </p>
 * <ul>
 * <li>connect(): 创建MongoClient实例</li>
 * <li>getTreeNodes(): listDatabaseNames → listCollectionNames → 文档统计</li>
 * <li>executeQuery(): find()结果转换为QueryResult</li>
 * <li>getTableColumns(): Collection的字段抽样推断</li>
 * </ul>
 */
public class MongoDBDriver extends AbstractNoSqlDriver {
    private static final System.Logger LOGGER =
            System.getLogger(MongoDBDriver.class.getName());

    public MongoDBDriver(DriverInfo driverInfo, ClassLoader driverClassLoader) {
        super(driverInfo, driverClassLoader);
    }

    @Override
    public boolean testConnection(Map<String, Object> params) {
        AutoCloseableMongoClient client = null;
        try {
            client = (AutoCloseableMongoClient) connect(params);
            // 通过反射调用MongoClient的ping命令
            Method getDatabase = client.clientClass.getMethod("getDatabase", String.class);
            Object adminDb = getDatabase.invoke(client.client, "admin");
            runCommand(adminDb, Map.of("ping", 1));
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    @Override
    public Object connect(Map<String, Object> params) throws Exception {
        String host = getStringParam(params, "host", "localhost");
        int port = getIntParam(params, "port", 27017);
        String username = getStringParam(params, "username", null);
        String password = getStringParam(params, "password", null);
        String authSource = getStringParam(params, "authSource", "admin");
        boolean ssl = getBooleanParam(params, "ssl", false);

        // 使用反射创建MongoClient实例（驱动在隔离的ClassLoader中）
        Class<?> settingsClass = Class.forName("com.mongodb.MongoClientSettings", true, driverClassLoader);
        Object settingsBuilder = settingsClass.getMethod("builder").invoke(null);
        String connectionString = buildConnectionString(
                host, port, username, password, authSource, ssl);

        // 通过ConnectionString创建
        Class<?> connStringClass = Class.forName("com.mongodb.ConnectionString", true, driverClassLoader);
        Object connString = connStringClass.getConstructor(String.class).newInstance(connectionString);

        // MongoClientSettings.builder().applyConnectionString(connString).build()
        Method applyConnectionString = settingsBuilder.getClass()
                .getMethod("applyConnectionString", connStringClass);
        applyConnectionString.invoke(settingsBuilder, connString);

        Method build = settingsBuilder.getClass().getMethod("build");
        Object settings = build.invoke(settingsBuilder);

        // 创建MongoClient
        Class<?> mongoClientClass = Class.forName("com.mongodb.client.MongoClient", true, driverClassLoader);
        Class<?> mongoClientsClass = Class.forName("com.mongodb.client.MongoClients", true, driverClassLoader);
        Method create = mongoClientsClass.getMethod("create", settingsClass);
        Object client = create.invoke(null, settings);

        return new AutoCloseableMongoClient(client, mongoClientClass);
    }

    @Override
    public void disconnect(Object connection) {
        if (connection instanceof AutoCloseableMongoClient) {
            ((AutoCloseableMongoClient) connection).close();
        }
    }

    /**
     * 构造 MongoDB URI。凭据和查询参数必须按 URI 组件编码，防止特殊字符
     * 改写主机或连接选项。
     */
    static String buildConnectionString(String host, int port,
            String username, String password, String authSource, boolean ssl) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("MongoDB 主机不能为空");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("MongoDB 端口超出有效范围");
        }

        String normalizedHost = host.trim();
        if (normalizedHost.indexOf('/') >= 0 || normalizedHost.indexOf('?') >= 0
                || normalizedHost.indexOf('#') >= 0 || normalizedHost.indexOf('@') >= 0) {
            throw new IllegalArgumentException("MongoDB 主机包含非法 URI 字符");
        }
        boolean startsBracket = normalizedHost.startsWith("[");
        boolean endsBracket = normalizedHost.endsWith("]");
        if (startsBracket != endsBracket) {
            throw new IllegalArgumentException("MongoDB IPv6 主机括号不完整");
        }
        if (normalizedHost.indexOf(':') >= 0 && !startsBracket) {
            normalizedHost = "[" + normalizedHost + "]";
        }

        StringBuilder uri = new StringBuilder("mongodb://");
        if (username != null && !username.isEmpty()) {
            uri.append(encodeUriComponent(username))
                    .append(':')
                    .append(encodeUriComponent(password == null ? "" : password))
                    .append('@');
        }
        uri.append(normalizedHost).append(':').append(port).append('/');

        List<String> options = new ArrayList<>();
        if (authSource != null && !authSource.isEmpty()) {
            options.add("authSource=" + encodeUriComponent(authSource));
        }
        if (ssl) {
            options.add("ssl=true");
        }
        if (!options.isEmpty()) {
            uri.append('?').append(String.join("&", options));
        }
        return uri.toString();
    }

    private static String encodeUriComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private Object runCommand(Object database, Map<String, Object> command)
            throws Exception {
        Class<?> bsonClass = Class.forName(
                "org.bson.conversions.Bson", true, driverClassLoader);
        Class<?> documentClass = Class.forName(
                "org.bson.Document", true, driverClassLoader);
        Object document = documentClass.getConstructor(Map.class).newInstance(command);
        Class<?> databaseClass = Class.forName(
                "com.mongodb.client.MongoDatabase", true, driverClassLoader);
        Method runCommand = databaseClass.getMethod("runCommand", bsonClass);
        return runCommand.invoke(database, document);
    }

    @Override
    public List<TreeNode> getTreeNodes(Object connection, String parentPath) throws Exception {
        AutoCloseableMongoClient wrapper = (AutoCloseableMongoClient) connection;
        Object client = wrapper.client;

        if (parentPath == null || parentPath.isEmpty()) {
            // 顶层：列出所有Database
            return listDatabases(client);
        }

        // 检查路径层级
        String[] parts = parentPath.split("/");
        if (parts.length == 1) {
            // 数据库级：列出Collection
            return listCollections(client, parentPath);
        }

        if (parts.length == 2) {
            // Collection级：列出索引和文档采样
            return listCollectionDetails(client, parentPath);
        }

        // 更深层级：无子节点
        return new ArrayList<>();
    }

    /**
     * 列出Collection的详细信息（索引 + 文档采样）
     */
    private List<TreeNode> listCollectionDetails(Object client, String path) throws Exception {
        List<TreeNode> nodes = new ArrayList<>();
        String[] parts = path.split("/");
        String dbName = parts[0];
        String collName = parts[1];

        // 索引组节点
        TreeNode indexGroup = TreeNode.of(path + "/indexes", "索引", "INDEX_GROUP", path + "/indexes");
        indexGroup.setIconType("index-group");
        indexGroup.setHasChildren(false);
        try {
            List<TreeNode> indexes = listIndexes(client, path);
            indexGroup.getProperties().put("count", indexes.size());
            indexGroup.getProperties().put("indexes", indexes);
        } catch (Exception e) {
            indexGroup.getProperties().put("error", e.getMessage());
        }
        nodes.add(indexGroup);

        // 文档采样节点（点击时加载前100条文档）
        TreeNode sampleNode = TreeNode.of(path + "/sample", "文档采样 (前100条)", "INFO", path + "/sample");
        sampleNode.setIconType("info");
        sampleNode.setHasChildren(false);
        nodes.add(sampleNode);

        return nodes;
    }

    /**
     * 列出所有数据库
     */
    private List<TreeNode> listDatabases(Object client) throws Exception {
        List<TreeNode> nodes = new ArrayList<>();

        Method listDatabaseNames = client.getClass().getMethod("listDatabaseNames");
        Object databaseNames = listDatabaseNames.invoke(client);
        Method iterator = databaseNames.getClass().getMethod("iterator");
        Object cursor = iterator.invoke(databaseNames);
        try {
            Method hasNext = cursor.getClass().getMethod("hasNext");
            Method next = cursor.getClass().getMethod("next");
            while ((Boolean) hasNext.invoke(cursor)) {
                String dbName = String.valueOf(next.invoke(cursor));
                TreeNode node = TreeNode.of(
                        dbName, dbName, "DATABASE", dbName);
                node.setIconType("database");
                node.setHasChildren(true);
                nodes.add(node);
            }
        } finally {
            closeMongoCursor(cursor);
        }

        return nodes;
    }

    /**
     * 列出Collection
     */
    private List<TreeNode> listCollections(Object client, String dbName) throws Exception {
        List<TreeNode> nodes = new ArrayList<>();

        Method getDatabase = client.getClass().getMethod("getDatabase", String.class);
        Object database = getDatabase.invoke(client, dbName);

        Method listCollectionNames = database.getClass().getMethod("listCollectionNames");
        Object collectionNames = listCollectionNames.invoke(database);
        Method iterator = collectionNames.getClass().getMethod("iterator");
        Object cursor = iterator.invoke(collectionNames);
        try {
            Method hasNext = cursor.getClass().getMethod("hasNext");
            Method next = cursor.getClass().getMethod("next");
            while ((Boolean) hasNext.invoke(cursor)) {
                String collName = String.valueOf(next.invoke(cursor));
                if (!collName.startsWith("system.")) {
                    TreeNode node = TreeNode.of(
                            dbName + "/" + collName,
                            collName,
                            "COLLECTION",
                            dbName + "/" + collName);
                    node.setIconType("collection");
                    node.setHasChildren(true);

                    try {
                        Map<String, Object> stats =
                                getCollectionStats(client, dbName, collName);
                        if (stats != null) {
                            node.getProperties().put(
                                    "count", stats.get("count"));
                            node.getProperties().put(
                                    "size", stats.get("size"));
                            node.getProperties().put(
                                    "storageSize", stats.get("storageSize"));
                            node.getProperties().put(
                                    "nindexes", stats.get("nindexes"));
                        }
                    } catch (Exception exception) {
                        // 统计信息获取失败不影响列表展示。
                    }
                    nodes.add(node);
                }
            }
        } finally {
            closeMongoCursor(cursor);
        }

        return nodes;
    }

    /**
     * 获取Collection统计信息
     */
    private Map<String, Object> getCollectionStats(Object client, String dbName, String collName) throws Exception {
        Method getDatabase = client.getClass().getMethod("getDatabase", String.class);
        Object database = getDatabase.invoke(client, dbName);
        Object result = runCommand(database, Map.of("collStats", collName));

        Map<String, Object> stats = new LinkedHashMap<>();
        if (result != null) {
            Method get = result.getClass().getMethod("get", Object.class);
            String[] keys = { "count", "size", "storageSize", "nindexes", "avgObjSize", "capped" };
            for (String key : keys) {
                Object val = get.invoke(result, key);
                if (val != null) {
                    stats.put(key, val);
                }
            }
        }
        return stats;
    }

    /**
     * 列出Collection的索引
     */
    private List<TreeNode> listIndexes(Object client, String path) throws Exception {
        String[] parts = path.split("/");
        String dbName = parts[0];
        String collName = parts[1];

        List<TreeNode> nodes = new ArrayList<>();

        Method getDatabase = client.getClass().getMethod("getDatabase", String.class);
        Object database = getDatabase.invoke(client, dbName);

        Method getCollection = database.getClass().getMethod("getCollection", String.class);
        Object collection = getCollection.invoke(database, collName);

        Method listIndexes = collection.getClass().getMethod("listIndexes");
        Object indexesIterable = listIndexes.invoke(collection);

        Method iterator = indexesIterable.getClass().getMethod("iterator");
        Object cursor = iterator.invoke(indexesIterable);

        try {
            Method hasNext = cursor.getClass().getMethod("hasNext");
            Method next = cursor.getClass().getMethod("next");

            while ((Boolean) hasNext.invoke(cursor)) {
                Object indexDoc = next.invoke(cursor);
                if (indexDoc != null) {
                    Method get = indexDoc.getClass().getMethod("get", Object.class);
                    Object nameVal = get.invoke(indexDoc, "name");
                    String indexName = nameVal != null
                            ? nameVal.toString() : "unknown";

                    TreeNode node = TreeNode.of(
                            path + "#" + indexName,
                            indexName,
                            "INDEX",
                            path + "#" + indexName);
                    node.setIconType("index");
                    node.setHasChildren(false);

                    Object keys = get.invoke(indexDoc, "key");
                    Object unique = get.invoke(indexDoc, "unique");
                    Object sparse = get.invoke(indexDoc, "sparse");
                    node.getProperties().put(
                            "keys", keys != null ? keys.toString() : "");
                    node.getProperties().put(
                            "unique", unique != null ? unique : false);
                    node.getProperties().put(
                            "sparse", sparse != null ? sparse : false);
                    nodes.add(node);
                }
            }
        } finally {
            closeMongoCursor(cursor);
        }

        return nodes;
    }

    @Override
    public List<ColumnMetadata> getTableColumns(Object connection, String schemaName, String tableName)
            throws Exception {
        AutoCloseableMongoClient wrapper = (AutoCloseableMongoClient) connection;
        Object client = wrapper.client;

        List<ColumnMetadata> columns = new ArrayList<>();

        // 解析parentPath获取dbName和collName
        String[] parts = tableName.split("/");
        String dbName = parts.length > 1 ? parts[0] : "test";
        String collName = parts.length > 1 ? parts[1] : tableName;

        // 获取Collection
        Method getDatabase = client.getClass().getMethod("getDatabase", String.class);
        Object database = getDatabase.invoke(client, dbName);

        Method getCollection = database.getClass().getMethod("getCollection", String.class);
        Object collection = getCollection.invoke(database, collName);

        // 抽样前100条文档推断字段
        Method find = collection.getClass().getMethod("find");
        Object findIterable = find.invoke(collection);

        Method limit = findIterable.getClass().getMethod("limit", int.class);
        Object limited = limit.invoke(findIterable, 100);

        // 使用迭代器遍历文档
        Method iterator = limited.getClass().getMethod("iterator");
        Object cursor = iterator.invoke(limited);

        Set<String> fieldNames = new LinkedHashSet<>();
        try {
            Method hasNext = cursor.getClass().getMethod("hasNext");
            Method next = cursor.getClass().getMethod("next");

            while ((Boolean) hasNext.invoke(cursor)) {
                Object doc = next.invoke(cursor);
                if (doc != null) {
                    Method keySet = doc.getClass().getMethod("keySet");
                    Set<String> keys = (Set<String>) keySet.invoke(doc);
                    fieldNames.addAll(keys);
                }
            }
        } finally {
            closeMongoCursor(cursor);
        }

        // 转换为ColumnMetadata
        for (String field : fieldNames) {
            ColumnMetadata col = new ColumnMetadata();
            col.setName(field);
            col.setTypeName("object");
            col.setNullable(true);
            col.setTableName(collName);
            col.setSchemaName(dbName);
            columns.add(col);
        }

        return columns;
    }

    @Override
    public QueryResult executeQuery(Object connection, String sql, int limit) throws Exception {
        AutoCloseableMongoClient wrapper = (AutoCloseableMongoClient) connection;
        Object client = wrapper.client;
        long startTime = System.currentTimeMillis();

        QueryResult result = new QueryResult();
        result.setSql(sql);

        // sql格式: "dbName.collName" 或 "dbName/collName" → 查询该Collection的前limit条文档
        String[] parts = sql.replace("/", ".").split("\\.");
        if (parts.length < 2) {
            result.addColumn("error");
            Map<String, Object> row = result.newRow();
            row.put("error", "MongoDB查询格式: db.collection 或 db/collection");
            result.addRow(row);
            result.setTotalRows(1);
            result.setElapsedMs(System.currentTimeMillis() - startTime);
            return result;
        }

        String dbName = parts[0];
        String collName = parts[1];

        Method getDatabase = client.getClass().getMethod("getDatabase", String.class);
        Object database = getDatabase.invoke(client, dbName);

        Method getCollection = database.getClass().getMethod("getCollection", String.class);
        Object collection = getCollection.invoke(database, collName);

        // find() 查询
        Method find = collection.getClass().getMethod("find");
        Object findIterable = find.invoke(collection);

        if (limit > 0) {
            Method limitMethod = findIterable.getClass().getMethod("limit", int.class);
            findIterable = limitMethod.invoke(findIterable, limit);
        }

        // 遍历结果
        Method iterator = findIterable.getClass().getMethod("iterator");
        Object cursor = iterator.invoke(findIterable);

        Set<String> allColumns = new LinkedHashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        int rowCount = 0;
        try {
            Method hasNext = cursor.getClass().getMethod("hasNext");
            Method next = cursor.getClass().getMethod("next");

            while ((Boolean) hasNext.invoke(cursor)
                    && (limit <= 0 || rowCount < limit)) {
                Object doc = next.invoke(cursor);
                Map<String, Object> row = new LinkedHashMap<>();

                if (doc != null) {
                    Method keySet = doc.getClass().getMethod("keySet");
                    Set<String> keys = (Set<String>) keySet.invoke(doc);

                    for (String key : keys) {
                        Method get = doc.getClass().getMethod("get", Object.class);
                        Object value = get.invoke(doc, key);
                        row.put(key, value != null ? value.toString() : null);
                        allColumns.add(key);
                    }
                }
                rows.add(row);
                rowCount++;
            }
        } finally {
            closeMongoCursor(cursor);
        }

        for (String col : allColumns) {
            result.addColumn(col);
        }
        for (Map<String, Object> row : rows) {
            result.addRow(row);
        }

        result.setTotalRows(rowCount);
        result.setElapsedMs(System.currentTimeMillis() - startTime);
        return result;
    }

    @Override
    public int executeUpdate(Object connection, String sql) throws Exception {
        AutoCloseableMongoClient wrapper = (AutoCloseableMongoClient) connection;
        Object client = wrapper.client;

        // sql 为 JSON DSL，格式示例：
        // {"op":"update","db":"mydb","collection":"coll","filter":{"name":"x"},"update":{"$set":{"age":2}}}
        // {"op":"insert","db":"mydb","collection":"coll","document":{"name":"x","age":1}}
        // {"op":"delete","db":"mydb","collection":"coll","filter":{"name":"x"}}
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> cmd = om.readValue(sql, Map.class);

        String op = String.valueOf(cmd.get("op")).toLowerCase();
        String dbName = cmd.get("db") != null ? String.valueOf(cmd.get("db")) : "test";
        String collName = String.valueOf(cmd.get("collection"));

        Method getDatabase = client.getClass().getMethod("getDatabase", String.class);
        Object database = getDatabase.invoke(client, dbName);
        Method getCollection = database.getClass().getMethod("getCollection", String.class);
        Object collection = getCollection.invoke(database, collName);

        Class<?> docClass = Class.forName("org.bson.Document", true, driverClassLoader);
        java.lang.reflect.Constructor<?> docCtor = docClass.getConstructor(Map.class);
        Class<?> bsonClass = Class.forName(
                "org.bson.conversions.Bson", true, driverClassLoader);
        Class<?> mongoCollectionClass = Class.forName(
                "com.mongodb.client.MongoCollection", true, driverClassLoader);

        switch (op) {
            case "update": {
                Object filter = cmd.get("filter") != null
                        ? docCtor.newInstance(normalizeObjectId(cmd.get("filter")))
                        : docCtor.newInstance(new HashMap<>());
                Object update = cmd.get("update") != null
                        ? docCtor.newInstance(cmd.get("update"))
                        : docCtor.newInstance(new HashMap<>());
                Method updateOne = mongoCollectionClass.getMethod(
                        "updateOne", bsonClass, bsonClass);
                Object result = updateOne.invoke(collection, filter, update);
                return getMongoCount(result, "getModifiedCount");
            }
            case "insert": {
                Object document = cmd.get("document") != null
                        ? docCtor.newInstance(cmd.get("document"))
                        : docCtor.newInstance(new HashMap<>());
                Method insertOne = mongoCollectionClass.getMethod("insertOne", Object.class);
                insertOne.invoke(collection, document);
                return 1;
            }
            case "delete": {
                Object filter = cmd.get("filter") != null
                        ? docCtor.newInstance(normalizeObjectId(cmd.get("filter")))
                        : docCtor.newInstance(new HashMap<>());
                Method deleteOne = mongoCollectionClass.getMethod("deleteOne", bsonClass);
                Object result = deleteOne.invoke(collection, filter);
                return getMongoCount(result, "getDeletedCount");
            }
            default:
                throw new UnsupportedOperationException("MongoDB 仅支持 op=update/insert/delete");
        }
    }

    /**
     * 将 filter 中的 _id 字符串（24位 hex）转换为 ObjectId 实例，
     * 保证前端以字符串形式回传的 _id 能正确匹配文档
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeObjectId(Object filterObj) {
        Map<String, Object> filter = new LinkedHashMap<>((Map<String, Object>) filterObj);
        Object id = filter.get("_id");
        if (id instanceof String idStr && idStr.matches("[0-9a-fA-F]{24}")) {
            try {
                Class<?> objectIdClass = Class.forName("org.bson.types.ObjectId", true, driverClassLoader);
                filter.put("_id", objectIdClass.getConstructor(String.class).newInstance(idStr));
            } catch (Exception e) {
                // ObjectId 转换失败时保留字符串形式
            }
        }
        return filter;
    }

    /** 从 Mongo UpdateResult/DeleteResult 中取影响行数 */
    private int getMongoCount(Object result, String methodName) throws Exception {
        if (result == null)
            return 0;
        try {
            Method m = result.getClass().getMethod(methodName);
            Object val = m.invoke(result);
            return val instanceof Number ? ((Number) val).intValue() : 0;
        } catch (NoSuchMethodException e) {
            return 0;
        }
    }

    /**
     * MongoCursor 持有服务端游标和网络资源，所有遍历路径都必须显式关闭。
     */
    private void closeMongoCursor(Object cursor) {
        if (cursor == null) {
            return;
        }
        try {
            Class<?> cursorClass = Class.forName(
                    "com.mongodb.client.MongoCursor", true, driverClassLoader);
            cursorClass.getMethod("close").invoke(cursor);
        } catch (Exception exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "关闭 MongoDB 游标失败", exception);
        }
    }

    @Override
    public String getTableDDL(Object connection, String schemaName, String tableName) throws Exception {
        // MongoDB没有DDL概念，返回Collection统计信息 + 索引列表
        AutoCloseableMongoClient wrapper = (AutoCloseableMongoClient) connection;
        Object client = wrapper.client;

        StringBuilder info = new StringBuilder();
        info.append("-- MongoDB Collection Info: ").append(tableName).append("\n");
        info.append("-- Schema-less document collection\n\n");

        String[] parts = tableName.split("/");
        String dbName = parts.length > 1 ? parts[0] : "test";
        String collName = parts.length > 1 ? parts[1] : tableName;

        // 获取Collection统计
        try {
            Map<String, Object> stats = getCollectionStats(client, dbName, collName);
            if (stats != null && !stats.isEmpty()) {
                info.append("-- === Collection Statistics ===\n");
                for (Map.Entry<String, Object> entry : stats.entrySet()) {
                    info.append("-- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
                info.append("\n");
            }
        } catch (Exception e) {
            info.append("-- 无法获取统计信息: ").append(e.getMessage()).append("\n\n");
        }

        // 获取索引列表
        try {
            List<TreeNode> indexes = listIndexes(client, dbName + "/" + collName);
            if (!indexes.isEmpty()) {
                info.append("-- === Indexes (").append(indexes.size()).append(") ===\n");
                for (TreeNode idx : indexes) {
                    info.append("-- ").append(idx.getName());
                    info.append("  keys=").append(idx.getProperties().get("keys"));
                    Object unique = idx.getProperties().get("unique");
                    if (Boolean.TRUE.equals(unique)) {
                        info.append("  UNIQUE");
                    }
                    Object sparse = idx.getProperties().get("sparse");
                    if (Boolean.TRUE.equals(sparse)) {
                        info.append("  SPARSE");
                    }
                    info.append("\n");
                }
            }
        } catch (Exception e) {
            info.append("-- 无法获取索引信息: ").append(e.getMessage()).append("\n");
        }

        return info.toString();
    }

    /**
     * MongoClient的AutoCloseable包装类
     */
    private static class AutoCloseableMongoClient implements AutoCloseable {
        final Object client;
        private final Class<?> clientClass;

        AutoCloseableMongoClient(Object client, Class<?> clientClass) {
            this.client = client;
            this.clientClass = clientClass;
        }

        @Override
        public void close() {
            try {
                Method close = clientClass.getMethod("close");
                close.invoke(client);
            } catch (Exception e) {
                // 忽略关闭错误
            }
        }
    }
}
