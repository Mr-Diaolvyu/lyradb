package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Redis驱动实现
 *
 * <p>
 * Redis是一等公民之一。通过反射调用Jedis客户端API，
 * 将Redis的Key-Value操作适配为统一的DatabaseDriver接口。
 * </p>
 *
 * <p>
 * 导航树结构：连接 → DB索引(0-15) → Key前缀分组 → Key
 * </p>
 *
 * <p>
 * 核心适配逻辑：
 * </p>
 * <ul>
 * <li>connect(): 创建Jedis实例</li>
 * <li>getTreeNodes(): SELECT db → SCAN keys → 按前缀分组</li>
 * <li>executeQuery(): GET/LRANGE/SMEMBERS/HGETALL等命令</li>
 * <li>getTableColumns(): 返回Key的类型和TTL信息</li>
 * </ul>
 */
public class RedisDriver extends AbstractNoSqlDriver {

    /** Redis最大DB索引 */
    private static final int MAX_DB_INDEX = 16;

    /** Key前缀分隔符 */
    private static final String KEY_SEPARATOR = ":";

    public RedisDriver(DriverInfo driverInfo, ClassLoader driverClassLoader) {
        super(driverInfo, driverClassLoader);
    }

    @Override
    public boolean testConnection(Map<String, Object> params) {
        Object jedis = null;
        try {
            jedis = connect(params);
            // 通过反射调用Jedis.ping()
            Method ping = jedis.getClass().getMethod("ping");
            Object result = ping.invoke(jedis);
            return "PONG".equals(result);
        } catch (Exception e) {
            return false;
        } finally {
            if (jedis != null) {
                disconnect(jedis);
            }
        }
    }

    @Override
    public Object connect(Map<String, Object> params) throws Exception {
        String host = getStringParam(params, "host", "localhost");
        int port = getIntParam(params, "port", 6379);
        String password = getStringParam(params, "password", null);
        int dbIndex = getIntParam(params, "databaseIndex", 0);
        boolean ssl = getBooleanParam(params, "ssl", false);

        // 通过反射构建 HostAndPoint + DefaultJedisClientConfig，使用 Jedis 5.x 的
        // Jedis(HostAndPoint, JedisClientConfig) 构造器，正确处理 ssl / dbIndex / 密码。
        // （原实现误用 (host, port, int, boolean) 构造器把 dbIndex 当作 timeout 传入）
        Class<?> hostAndPortClass = Class.forName("redis.clients.jedis.HostAndPort", true, driverClassLoader);
        Object hostAndPort = hostAndPortClass
                .getConstructor(String.class, int.class)
                .newInstance(host, port);

        Object config = buildJedisConfig(ssl, dbIndex, password);

        Class<?> jedisClientConfigClass = Class.forName("redis.clients.jedis.JedisClientConfig", true,
                driverClassLoader);
        Class<?> jedisClass = Class.forName("redis.clients.jedis.Jedis", true, driverClassLoader);
        Object jedis = jedisClass
                .getConstructor(hostAndPortClass, jedisClientConfigClass)
                .newInstance(hostAndPort, config);

        // SELECT dbIndex（config 已设置 database，这里确保生效）
        Method select = jedisClass.getMethod("select", int.class);
        select.invoke(jedis, dbIndex);

        return new JedisWrapper(jedis, jedisClass);
    }

    /**
     * 通过反射构建 DefaultJedisClientConfig
     */
    private Object buildJedisConfig(boolean ssl, int dbIndex, String password) throws Exception {
        Class<?> configClass = Class.forName("redis.clients.jedis.DefaultJedisClientConfig", true, driverClassLoader);
        Object builder = configClass.getMethod("builder").invoke(null);
        Class<?> builderClass = builder.getClass();

        try {
            builderClass.getMethod("ssl", boolean.class).invoke(builder, ssl);
        } catch (NoSuchMethodException e) {
            // 老版本不支持，忽略
        }
        try {
            builderClass.getMethod("database", int.class).invoke(builder, dbIndex);
        } catch (NoSuchMethodException e) {
            // 忽略
        }
        if (password != null && !password.isEmpty()) {
            try {
                builderClass.getMethod("password", String.class).invoke(builder, password);
            } catch (NoSuchMethodException e) {
                builderClass.getMethod("password", char[].class)
                        .invoke(builder, (Object) password.toCharArray());
            }
        }
        try {
            builderClass.getMethod("timeoutMillis", int.class).invoke(builder, 2000);
        } catch (NoSuchMethodException e) {
            // 忽略
        }

        return builderClass.getMethod("build").invoke(builder);
    }

    @Override
    public void disconnect(Object connection) {
        if (connection instanceof JedisWrapper) {
            ((JedisWrapper) connection).close();
        }
    }

    @Override
    public List<TreeNode> getTreeNodes(Object connection, String parentPath) throws Exception {
        JedisWrapper wrapper = (JedisWrapper) connection;
        Object jedis = wrapper.jedis;

        if (parentPath == null || parentPath.isEmpty()) {
            // 顶层：列出DB索引（0-15）
            return listDbIndexes();
        }

        if (parentPath.startsWith("db")) {
            // DB索引级：列出Key前缀分组
            return listKeyPrefixGroups(jedis, parentPath);
        }

        // 前缀分组级：列出Key
        return listKeys(jedis, parentPath);
    }

    /**
     * 列出DB索引
     */
    private List<TreeNode> listDbIndexes() {
        List<TreeNode> nodes = new ArrayList<>();
        for (int i = 0; i < MAX_DB_INDEX; i++) {
            String dbId = "db" + i;
            TreeNode node = TreeNode.of(dbId, "DB " + i, "DATABASE", dbId);
            node.setIconType("database");
            node.setHasChildren(true);
            node.getProperties().put("dbIndex", i);
            nodes.add(node);
        }
        return nodes;
    }

    /**
     * 列出Key前缀分组
     */
    private List<TreeNode> listKeyPrefixGroups(Object jedis, String dbId) throws Exception {
        // 先SELECT对应的db
        int dbIndex = Integer.parseInt(dbId.substring(2));
        Method select = jedis.getClass().getMethod("select", int.class);
        select.invoke(jedis, dbIndex);

        // SCAN所有Key
        Set<String> allKeys = scanKeys(jedis, "*");

        // 按前缀分组
        Map<String, List<String>> prefixGroups = new TreeMap<>();
        for (String key : allKeys) {
            String prefix = getKeyPrefix(key);
            prefixGroups.computeIfAbsent(prefix, k -> new ArrayList<>()).add(key);
        }

        List<TreeNode> nodes = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : prefixGroups.entrySet()) {
            String prefix = entry.getKey();
            int count = entry.getValue().size();
            TreeNode node = TreeNode.of(
                    dbId + "/" + prefix,
                    prefix + " (" + count + ")",
                    "KEY_GROUP",
                    dbId + "/" + prefix);
            node.setIconType("folder");
            node.setHasChildren(true);
            node.getProperties().put("keyCount", count);
            nodes.add(node);
        }

        return nodes;
    }

    /**
     * 列出Key
     */
    private List<TreeNode> listKeys(Object jedis, String parentPath) throws Exception {
        // parentPath格式: "db0/users:"
        String[] parts = parentPath.split("/", 2);
        String prefix = parts.length > 1 ? parts[1] : "*";
        if (!prefix.endsWith("*") && !prefix.endsWith(":")) {
            prefix = prefix + "*";
        } else if (prefix.endsWith(":")) {
            prefix = prefix + "*";
        }

        Set<String> keys = scanKeys(jedis, prefix);

        List<TreeNode> nodes = new ArrayList<>();
        for (String key : keys) {
            // 获取Key的类型
            String keyType = getKeyType(jedis, key);
            // 获取TTL
            long ttlValue = getKeyTtl(jedis, key);

            TreeNode node = TreeNode.of(
                    parentPath + "/" + key,
                    key,
                    "KEY",
                    parentPath + "/" + key);
            node.setIconType("key_" + keyType.toLowerCase());
            node.setHasChildren(false);
            node.getProperties().put("keyType", keyType);
            node.getProperties().put("keyName", key);
            node.getProperties().put("ttl", ttlValue);
            node.getProperties().put("ttlDisplay", formatTtl(ttlValue));
            nodes.add(node);
        }

        return nodes;
    }

    /**
     * 获取Key的TTL
     */
    private long getKeyTtl(Object jedis, String key) throws Exception {
        Method ttl = jedis.getClass().getMethod("ttl", String.class);
        Object ttlValue = ttl.invoke(jedis, key);
        if (ttlValue instanceof Number) {
            return ((Number) ttlValue).longValue();
        }
        return -1;
    }

    /**
     * 格式化TTL显示文本
     */
    private String formatTtl(long ttl) {
        if (ttl < 0)
            return "永久";
        if (ttl < 60)
            return ttl + "秒";
        if (ttl < 3600)
            return (ttl / 60) + "分" + (ttl % 60) + "秒";
        if (ttl < 86400)
            return (ttl / 3600) + "小时" + ((ttl % 3600) / 60) + "分";
        return (ttl / 86400) + "天" + ((ttl % 86400) / 3600) + "小时";
    }

    /**
     * 使用SCAN命令扫描Key
     */
    @SuppressWarnings("unchecked")
    private Set<String> scanKeys(Object jedis, String pattern) throws Exception {
        Set<String> keys = new TreeSet<>();
        String cursor = "0";
        int count = 0;
        int maxKeys = 10000; // 安全限制

        do {
            Method scan = jedis.getClass().getMethod("scan", String.class);
            // 使用ScanParams匹配pattern
            Class<?> scanParamsClass = Class.forName("redis.clients.jedis.ScanParams", true, driverClassLoader);
            Object scanParams = scanParamsClass.getConstructor().newInstance();
            Method match = scanParamsClass.getMethod("match", String.class);
            match.invoke(scanParams, pattern);
            Method countMethod = scanParamsClass.getMethod("count", int.class);
            countMethod.invoke(scanParams, 100);

            Method scanWithParams = jedis.getClass().getMethod("scan", String.class, scanParamsClass);
            Object scanResult = scanWithParams.invoke(jedis, cursor, scanParams);

            // 获取结果
            Method getString = scanResult.getClass().getMethod("getCursor");
            cursor = (String) getString.invoke(scanResult);
            Method getResult = scanResult.getClass().getMethod("getResult");
            Collection<String> resultKeys = (Collection<String>) getResult.invoke(scanResult);

            keys.addAll(resultKeys);
            count += resultKeys.size();
        } while (!"0".equals(cursor) && count < maxKeys);

        return keys;
    }

    /**
     * 获取Key的类型
     */
    private String getKeyType(Object jedis, String key) throws Exception {
        Method type = jedis.getClass().getMethod("type", String.class);
        Object result = type.invoke(jedis, key);
        return result != null ? result.toString() : "unknown";
    }

    /**
     * 获取Key的前缀（第一段，用:分隔）
     */
    private String getKeyPrefix(String key) {
        int idx = key.indexOf(KEY_SEPARATOR);
        if (idx > 0) {
            return key.substring(0, idx) + KEY_SEPARATOR;
        }
        return "(无前缀)";
    }

    @Override
    public List<ColumnMetadata> getTableColumns(Object connection, String schemaName, String tableName)
            throws Exception {
        JedisWrapper wrapper = (JedisWrapper) connection;
        Object jedis = wrapper.jedis;

        // tableName格式: "parentPath/keyName"
        String keyName = tableName.contains("/") ? tableName.substring(tableName.lastIndexOf("/") + 1) : tableName;

        String keyType = getKeyType(jedis, keyName);
        List<ColumnMetadata> columns = new ArrayList<>();

        ColumnMetadata keyCol = new ColumnMetadata();
        keyCol.setName("key");
        keyCol.setTypeName("string");
        keyCol.setTableName(keyName);
        columns.add(keyCol);

        ColumnMetadata typeCol = new ColumnMetadata();
        typeCol.setName("type");
        typeCol.setTypeName("string");
        typeCol.setTableName(keyName);
        columns.add(typeCol);

        ColumnMetadata valueCol = new ColumnMetadata();
        valueCol.setName("value");
        valueCol.setTypeName(keyType.toLowerCase());
        valueCol.setTableName(keyName);
        columns.add(valueCol);

        // 获取TTL
        Method ttl = jedis.getClass().getMethod("ttl", String.class);
        Object ttlValue = ttl.invoke(jedis, keyName);
        ColumnMetadata ttlCol = new ColumnMetadata();
        ttlCol.setName("ttl");
        ttlCol.setTypeName("long");
        ttlCol.setTableName(keyName);
        ttlCol.setDefaultValue(ttlValue != null ? ttlValue.toString() : "-1");
        columns.add(ttlCol);

        return columns;
    }

    @Override
    public QueryResult executeQuery(Object connection, String sql, int limit) throws Exception {
        JedisWrapper wrapper = (JedisWrapper) connection;
        Object jedis = wrapper.jedis;
        long startTime = System.currentTimeMillis();

        QueryResult result = new QueryResult();
        result.setSql(sql);

        // sql格式: "GET key" / "KEYS pattern" / "TYPE key" 等
        String upperSql = sql.trim().toUpperCase();

        if (upperSql.startsWith("GET ")) {
            // GET命令（附带 TTL 信息，便于前端行内查看/编辑）
            String key = sql.substring(4).trim();
            Method get = jedis.getClass().getMethod("get", String.class);
            Object value = get.invoke(jedis, key);

            result.addColumn("key");
            result.addColumn("value");
            result.addColumn("ttl");
            result.addColumn("ttlDisplay");
            Map<String, Object> row = result.newRow();
            row.put("key", key);
            row.put("value", value != null ? value.toString() : "(nil)");
            long ttlVal = getKeyTtl(jedis, key);
            row.put("ttl", ttlVal);
            row.put("ttlDisplay", formatTtl(ttlVal));
            result.addRow(row);
            result.setTotalRows(1);

        } else if (upperSql.startsWith("KEYS ") || upperSql.startsWith("SCAN ")) {
            // KEYS/SCAN命令（支持前缀过滤 pattern，如 SCAN user:*），附带 type/TTL 信息
            String pattern = upperSql.startsWith("KEYS ") ? sql.substring(5).trim() : sql.substring(5).trim();

            Set<String> keys = scanKeys(jedis, pattern);
            result.addColumn("key");
            result.addColumn("type");
            result.addColumn("ttl");
            result.addColumn("ttlDisplay");

            int count = 0;
            for (String key : keys) {
                if (limit > 0 && count >= limit) {
                    result.setTruncated(true);
                    break;
                }
                Map<String, Object> row = result.newRow();
                row.put("key", key);
                try {
                    row.put("type", getKeyType(jedis, key));
                    long ttlVal = getKeyTtl(jedis, key);
                    row.put("ttl", ttlVal);
                    row.put("ttlDisplay", formatTtl(ttlVal));
                } catch (Exception e) {
                    // 单个 key 的附加信息失败不影响列表
                }
                result.addRow(row);
                count++;
            }
            result.setTotalRows(count);

        } else if (upperSql.startsWith("TYPE ")) {
            // TYPE命令
            String key = sql.substring(5).trim();
            String keyType = getKeyType(jedis, key);

            result.addColumn("key");
            result.addColumn("type");
            Map<String, Object> row = result.newRow();
            row.put("key", key);
            row.put("type", keyType);
            result.addRow(row);
            result.setTotalRows(1);

        } else if (upperSql.startsWith("HGETALL ")) {
            // HGETALL命令
            String key = sql.substring(8).trim();
            Method hgetAll = jedis.getClass().getMethod("hgetAll", String.class);
            Object mapResult = hgetAll.invoke(jedis, key);

            result.addColumn("field");
            result.addColumn("value");

            if (mapResult instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> map = (Map<String, String>) mapResult;
                int count = 0;
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    if (limit > 0 && count >= limit) {
                        result.setTruncated(true);
                        break;
                    }
                    Map<String, Object> row = result.newRow();
                    row.put("field", entry.getKey());
                    row.put("value", entry.getValue());
                    result.addRow(row);
                    count++;
                }
                result.setTotalRows(count);
            }

        } else if (upperSql.startsWith("LRANGE ")) {
            // LRANGE命令
            String[] args = sql.substring(7).trim().split("\\s+");
            if (args.length >= 3) {
                String key = args[0];
                long start = Long.parseLong(args[1]);
                long end = Long.parseLong(args[2]);
                if (limit > 0 && end > start + limit - 1) {
                    end = start + limit - 1;
                }

                Method lrange = jedis.getClass().getMethod("lrange", String.class, long.class, long.class);
                Object listResult = lrange.invoke(jedis, key, start, end);

                result.addColumn("index");
                result.addColumn("value");

                if (listResult instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> list = (List<String>) listResult;
                    for (int i = 0; i < list.size(); i++) {
                        Map<String, Object> row = result.newRow();
                        row.put("index", i);
                        row.put("value", list.get(i));
                        result.addRow(row);
                    }
                    result.setTotalRows(list.size());
                }
            }
        } else if (upperSql.startsWith("SMEMBERS ")) {
            // SMEMBERS命令
            String key = sql.substring(9).trim();
            Method smembers = jedis.getClass().getMethod("smembers", String.class);
            Object setResult = smembers.invoke(jedis, key);

            result.addColumn("member");
            if (setResult instanceof Set) {
                @SuppressWarnings("unchecked")
                Set<String> set = (Set<String>) setResult;
                int count = 0;
                for (String member : set) {
                    if (limit > 0 && count >= limit) {
                        result.setTruncated(true);
                        break;
                    }
                    Map<String, Object> row = result.newRow();
                    row.put("member", member);
                    result.addRow(row);
                    count++;
                }
                result.setTotalRows(count);
            }

        } else if (upperSql.startsWith("ZRANGE ")) {
            // ZRANGE命令
            String[] args = sql.substring(7).trim().split("\\s+");
            if (args.length >= 3) {
                String key = args[0];
                long start = Long.parseLong(args[1]);
                long end = Long.parseLong(args[2]);
                if (limit > 0 && end > start + limit - 1) {
                    end = start + limit - 1;
                }

                Method zrange = jedis.getClass().getMethod("zrange", String.class, long.class, long.class);
                Object listResult = zrange.invoke(jedis, key, start, end);

                result.addColumn("rank");
                result.addColumn("member");

                if (listResult instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> list = (List<String>) listResult;
                    for (int i = 0; i < list.size(); i++) {
                        Map<String, Object> row = result.newRow();
                        row.put("rank", i);
                        row.put("member", list.get(i));
                        result.addRow(row);
                    }
                    result.setTotalRows(list.size());
                }
            }

        } else if (upperSql.startsWith("STRLEN ")) {
            String key = sql.substring(7).trim();
            Method strlen = jedis.getClass().getMethod("strlen", String.class);
            Object len = strlen.invoke(jedis, key);
            result.addColumn("key");
            result.addColumn("length");
            Map<String, Object> row = result.newRow();
            row.put("key", key);
            row.put("length", len);
            result.addRow(row);
            result.setTotalRows(1);

        } else if (upperSql.startsWith("DBSIZE")) {
            Method dbsize = jedis.getClass().getMethod("dbSize");
            Object size = dbsize.invoke(jedis);
            result.addColumn("dbSize");
            Map<String, Object> row = result.newRow();
            row.put("dbSize", size);
            result.addRow(row);
            result.setTotalRows(1);

        } else if (upperSql.startsWith("TTL ")) {
            String key = sql.substring(4).trim();
            long ttlVal = getKeyTtl(jedis, key);
            result.addColumn("key");
            result.addColumn("ttl");
            result.addColumn("ttlDisplay");
            Map<String, Object> row = result.newRow();
            row.put("key", key);
            row.put("ttl", ttlVal);
            row.put("ttlDisplay", formatTtl(ttlVal));
            result.addRow(row);
            result.setTotalRows(1);

        } else if (upperSql.startsWith("INFO")) {
            // INFO 命令：返回服务器信息
            Method info;
            Object infoStr;
            try {
                info = jedis.getClass().getMethod("info");
                infoStr = info.invoke(jedis);
            } catch (NoSuchMethodException e) {
                info = jedis.getClass().getMethod("info", String.class);
                infoStr = info.invoke(jedis, "");
            }
            result.addColumn("info");
            Map<String, Object> row = result.newRow();
            row.put("info", infoStr != null ? infoStr.toString() : "");
            result.addRow(row);
            result.setTotalRows(1);

        } else {
            result.addColumn("error");
            Map<String, Object> row = result.newRow();
            row.put("error",
                    "不支持的Redis命令。支持: GET/KEYS/SCAN/TYPE/HGETALL/LRANGE/SMEMBERS/ZRANGE/STRLEN/DBSIZE/INFO/TTL");
            result.addRow(row);
            result.setTotalRows(1);
        }

        result.setElapsedMs(System.currentTimeMillis() - startTime);
        return result;
    }

    @Override
    public int executeUpdate(Object connection, String sql) throws Exception {
        JedisWrapper wrapper = (JedisWrapper) connection;
        Object jedis = wrapper.jedis;

        String upper = sql.trim().toUpperCase();
        String[] tokens = sql.trim().split("\\s+");
        if (upper.startsWith("SET ")) {
            // SET key value [value...]
            if (tokens.length < 3) {
                throw new IllegalArgumentException("SET 命令格式: SET key value");
            }
            String key = tokens[1];
            String value = joinTokens(tokens, 2);
            Method set = jedis.getClass().getMethod("set", String.class, String.class);
            set.invoke(jedis, key, value);
            return 1;
        } else if (upper.startsWith("DEL ")) {
            // DEL key [key...]
            String[] keys = java.util.Arrays.copyOfRange(tokens, 1, tokens.length);
            if (keys.length == 1) {
                Method del = jedis.getClass().getMethod("del", String.class);
                Object removed = del.invoke(jedis, keys[0]);
                return removed instanceof Number ? ((Number) removed).intValue() : 1;
            } else {
                // 多 key：用 del(String...) 可变参
                Method del = jedis.getClass().getMethod("del", String[].class);
                Object removed = del.invoke(jedis, (Object) keys);
                return removed instanceof Number ? ((Number) removed).intValue() : keys.length;
            }
        } else if (upper.startsWith("EXPIRE ")) {
            // EXPIRE key seconds
            if (tokens.length < 3) {
                throw new IllegalArgumentException("EXPIRE 命令格式: EXPIRE key seconds");
            }
            String key = tokens[1];
            long seconds = Long.parseLong(tokens[2]);
            Method expire = jedis.getClass().getMethod("expire", String.class, long.class);
            Object res = expire.invoke(jedis, key, seconds);
            return res instanceof Number ? ((Number) res).intValue() : 1;
        } else if (upper.startsWith("PERSIST ")) {
            String key = tokens[1];
            Method persist = jedis.getClass().getMethod("persist", String.class);
            Object res = persist.invoke(jedis, key);
            return res instanceof Number ? ((Number) res).intValue() : 1;
        } else if (upper.equals("FLUSHDB") || upper.equals("FLUSHDB;")) {
            Method flush = jedis.getClass().getMethod("flushDB");
            flush.invoke(jedis);
            return 0;
        }
        throw new UnsupportedOperationException(
                "Redis 仅支持 SET/DEL/EXPIRE/PERSIST/FLUSHDB 等写命令，不支持 SQL 式 DML");
    }

    /** 拼接 tokens 从 fromIndex 开始的部分（用于 SET 的 value） */
    private String joinTokens(String[] tokens, int fromIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = fromIndex; i < tokens.length; i++) {
            if (i > fromIndex) {
                sb.append(' ');
            }
            sb.append(tokens[i]);
        }
        return sb.toString();
    }

    @Override
    public String getTableDDL(Object connection, String schemaName, String tableName) throws Exception {
        JedisWrapper wrapper = (JedisWrapper) connection;
        Object jedis = wrapper.jedis;

        String keyName = tableName.contains("/") ? tableName.substring(tableName.lastIndexOf("/") + 1) : tableName;

        String keyType = getKeyType(jedis, keyName);
        long ttlValue = getKeyTtl(jedis, keyName);

        StringBuilder info = new StringBuilder();
        info.append("-- Redis Key Info: ").append(keyName).append("\n");
        info.append("-- Type: ").append(keyType).append("\n");
        info.append("-- TTL: ").append(ttlValue).append(" seconds (").append(formatTtl(ttlValue)).append(")\n");

        // 获取Value摘要
        try {
            String valueSummary = getKeyValueSummary(jedis, keyName, keyType);
            if (valueSummary != null) {
                info.append("-- Value Summary: ").append(valueSummary).append("\n");
            }
        } catch (Exception e) {
            info.append("-- Value Summary: (unable to read)\n");
        }

        // 获取内存使用
        try {
            Method memoryUsage = jedis.getClass().getMethod("memoryUsage", String.class);
            Object memSize = memoryUsage.invoke(jedis, keyName);
            if (memSize != null) {
                info.append("-- Memory Usage: ").append(memSize).append(" bytes\n");
            }
        } catch (Exception e) {
            // memoryUsage may not be available in older Jedis versions
        }

        return info.toString();
    }

    /**
     * 获取Key Value摘要
     */
    private String getKeyValueSummary(Object jedis, String key, String keyType) throws Exception {
        switch (keyType.toLowerCase()) {
            case "string": {
                Method strlen = jedis.getClass().getMethod("strlen", String.class);
                Object len = strlen.invoke(jedis, key);
                return "string, length=" + len;
            }
            case "list": {
                Method llen = jedis.getClass().getMethod("llen", String.class);
                Object len = llen.invoke(jedis, key);
                return "list, length=" + len;
            }
            case "hash": {
                Method hlen = jedis.getClass().getMethod("hlen", String.class);
                Object len = hlen.invoke(jedis, key);
                return "hash, fields=" + len;
            }
            case "set": {
                Method scard = jedis.getClass().getMethod("scard", String.class);
                Object len = scard.invoke(jedis, key);
                return "set, members=" + len;
            }
            case "zset": {
                Method zcard = jedis.getClass().getMethod("zcard", String.class);
                Object len = zcard.invoke(jedis, key);
                return "zset, members=" + len;
            }
            case "stream": {
                Method xlen = jedis.getClass().getMethod("xlen", String.class);
                Object len = xlen.invoke(jedis, key);
                return "stream, entries=" + len;
            }
            default:
                return keyType + " (unknown type)";
        }
    }

    /**
     * Jedis实例的包装类
     */
    private static class JedisWrapper implements AutoCloseable {
        final Object jedis;
        private final Class<?> jedisClass;

        JedisWrapper(Object jedis, Class<?> jedisClass) {
            this.jedis = jedis;
            this.jedisClass = jedisClass;
        }

        @Override
        public void close() {
            try {
                Method close = jedisClass.getMethod("close");
                close.invoke(jedis);
            } catch (Exception e) {
                // 忽略关闭错误
            }
        }
    }
}
