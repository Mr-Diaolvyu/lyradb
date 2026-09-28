package io.github.lexaquila.lyradb.lineage;

import com.aliyun.dataworks_public20240518.Client;
import com.aliyun.dataworks_public20240518.models.LineageEntity;
import com.aliyun.dataworks_public20240518.models.ListLineagesRequest;
import com.aliyun.dataworks_public20240518.models.ListLineagesResponse;
import com.aliyun.dataworks_public20240518.models.ListLineagesResponseBody;
import com.aliyun.dataworks_public20240518.models.ListLineagesResponseBody.ListLineagesResponseBodyPagingInfo;
import com.aliyun.dataworks_public20240518.models.ListLineagesResponseBody.ListLineagesResponseBodyPagingInfoLineages;
import com.aliyun.teaopenapi.models.Config;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用 DataWorks OpenAPI 读取 MaxCompute 真实表血缘和字段血缘。
 *
 * <p>该服务只接受官方实体 ID，并严格使用 ListLineages 返回的边；不会根据
 * 表名、字段名或 SQL 文本推测关系。调用端可限制深度和节点数，避免在桌面端
 * 无边界展开大型数仓。</p>
 */
public final class DataWorksLineageClient {

    public static final int DEFAULT_DEPTH = 2;
    public static final int DEFAULT_MAX_NODES = 120;
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES_PER_DIRECTION = 10;
    private static final String TABLE_PREFIX = "maxcompute-table:::";
    private static final String COLUMN_PREFIX = "maxcompute-column:::";
    private static final Pattern REGION_PATTERN = Pattern.compile(
            "(?i)(?:service|odps|dt)\\.([a-z0-9-]+)\\.maxcompute\\."
                    + "(?:aliyun\\.com|aliyun-inc\\.com)");

    private final LineageApi api;

    public DataWorksLineageClient(LineageApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    public static DataWorksLineageClient fromParameters(
            Map<String, Object> connection) throws Exception {
        String accessKeyId = parameter(connection, "accessKeyId");
        String accessKeySecret = parameter(connection, "accessKeySecret");
        if (blank(accessKeyId) || blank(accessKeySecret)) {
            throw new IllegalArgumentException(
                    "连接缺少 AccessKey ID/Secret，无法调用 DataWorks 血缘 API");
        }
        String regionId = firstNonBlank(
                parameter(connection, "dataWorksRegionId"),
                parameter(connection, "regionId"),
                inferRegion(parameter(connection, "endpoint")));
        if (blank(regionId)) {
            throw new IllegalArgumentException(
                    "无法从 MaxCompute Endpoint 识别地域；请在连接参数中配置 dataWorksRegionId");
        }
        String endpoint = firstNonBlank(
                parameter(connection, "dataWorksEndpoint"),
                "dataworks." + regionId + ".aliyuncs.com");
        if (!regionId.matches("[a-z][a-z0-9-]{0,40}")
                || !endpoint.matches("dataworks(?:-vpc)?\\.[a-z0-9-]+\\.aliyuncs\\.com")) {
            throw new IllegalArgumentException("DataWorks 只允许官方地域 Endpoint");
        }
        Config config = new Config()
                .setProtocol("HTTPS")
                .setAccessKeyId(accessKeyId)
                .setAccessKeySecret(accessKeySecret)
                .setRegionId(regionId)
                .setEndpoint(endpoint)
                .setConnectTimeout(10_000)
                .setReadTimeout(30_000);
        String securityToken = parameter(connection, "securityToken");
        if (!blank(securityToken)) {
            config.setSecurityToken(securityToken);
        }
        return new DataWorksLineageClient(
                new AlibabaLineageApi(new Client(config)));
    }

    public LineageResult explore(
            List<String> rootEntityIds,
            Direction direction,
            int maxDepth,
            int maxNodes) throws Exception {
        return explore(rootEntityIds, direction, maxDepth, maxNodes, entity -> true);
    }

    public LineageResult explore(List<String> rootEntityIds, Direction direction,
            int maxDepth, int maxNodes, java.util.function.Predicate<EntityRef> allowed) throws Exception {
        if (rootEntityIds == null || rootEntityIds.isEmpty()) {
            return new LineageResult(emptyGraph(), 0, Instant.now());
        }
        Direction safeDirection = direction == null
                ? Direction.BOTH : direction;
        int safeDepth = Math.max(1, Math.min(5, maxDepth));
        int safeMaxNodes = Math.max(2, Math.min(500, maxNodes));

        Map<String, EntityRef> entities = new LinkedHashMap<>();
        Map<String, Edge> edges = new LinkedHashMap<>();
        Deque<NodeDepth> pending = new ArrayDeque<>();
        Set<String> queued = new LinkedHashSet<>();
        Set<String> visitedQueries = new LinkedHashSet<>();
        for (String root : rootEntityIds) {
            if (blank(root)) {
                continue;
            }
            EntityRef entity = parseEntity(root, null);
            if (!allowed.test(entity)) throw new IllegalArgumentException("根对象不在允许范围内");
            if (entities.size() >= safeMaxNodes) throw new IllegalArgumentException("根对象超过节点上限");
            entities.putIfAbsent(entity.id(), entity);
            if (queued.add(entity.id())) {
                pending.addLast(new NodeDepth(entity.id(), 0));
            }
        }

        boolean truncated = false;
        while (!pending.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("血缘探查已取消");
            }
            NodeDepth current = pending.removeFirst();
            if (current.depth() >= safeDepth) {
                continue;
            }
            for (QueryDirection queryDirection : safeDirection.queries()) {
                String visitKey = current.entityId() + "\u0000" + queryDirection;
                if (!visitedQueries.add(visitKey)) {
                    continue;
                }
                for (Edge edge : api.list(current.entityId(), queryDirection)) {
                    if (!allowed.test(edge.source()) || !allowed.test(edge.target())) continue;
                    if (queryDirection == QueryDirection.UPSTREAM
                            ? !edge.target().id().equals(current.entityId())
                            : !edge.source().id().equals(current.entityId())) continue;
                    int added = (entities.containsKey(edge.source().id()) ? 0 : 1)
                            + (entities.containsKey(edge.target().id()) ? 0 : 1);
                    if (entities.size() + added > safeMaxNodes) { truncated = true; break; }
                    entities.putIfAbsent(edge.source().id(), edge.source());
                    entities.putIfAbsent(edge.target().id(), edge.target());
                    edges.putIfAbsent(edge.key(), edge);
                    if (entities.size() >= safeMaxNodes) {
                        truncated = true;
                        break;
                    }
                    EntityRef next = queryDirection == QueryDirection.UPSTREAM
                            ? edge.source() : edge.target();
                    if (queued.add(next.id())) {
                        pending.addLast(new NodeDepth(
                                next.id(), current.depth() + 1));
                    }
                }
                if (truncated) {
                    break;
                }
            }
            if (truncated) {
                break;
            }
        }
        return new LineageResult(
                toGraph(entities.values(), edges.values(), truncated),
                edges.size(), Instant.now());
    }

    public static String tableEntityId(String project, String table) {
        requirePart(project, "Project");
        requirePart(table, "表名");
        return TABLE_PREFIX + project.trim() + "::" + table.trim();
    }

    public static String columnEntityId(
            String project, String table, String column) {
        requirePart(project, "Project");
        requirePart(table, "表名");
        requirePart(column, "字段名");
        return COLUMN_PREFIX + project.trim() + "::" + table.trim()
                + "::" + column.trim();
    }

    private static SchemaGraph toGraph(
            Iterable<EntityRef> entityValues,
            Iterable<Edge> edgeValues,
            boolean truncated) {
        Map<String, MutableTable> tables = new LinkedHashMap<>();
        for (EntityRef entity : entityValues) {
            MutableTable table = tables.computeIfAbsent(
                    entity.tableKey(), ignored -> new MutableTable(
                            entity.project(), entity.table()));
            if (entity.kind() == EntityKind.COLUMN
                    && !blank(entity.column())) {
                table.columns.putIfAbsent(entity.column().toLowerCase(Locale.ROOT),
                        new ColumnNode(
                                entity.column(), "血缘字段", false,
                                "DataWorks 字段血缘实体"));
            }
        }
        List<TableNode> nodes = tables.values().stream()
                .map(table -> new TableNode(
                        table.project, table.name,
                        List.copyOf(table.columns.values())))
                .toList();
        List<Relation> relations = new ArrayList<>();
        Set<String> relationKeys = new LinkedHashSet<>();
        for (Edge edge : edgeValues) {
            String from = key(
                    edge.source().project(), edge.source().table());
            String to = key(
                    edge.target().project(), edge.target().table());
            String fromColumn = edge.source().kind() == EntityKind.COLUMN
                    ? edge.source().column() : "表输出";
            String toColumn = edge.target().kind() == EntityKind.COLUMN
                    ? edge.target().column() : "表输入";
            String key = from + "\u0000" + to + "\u0000"
                    + fromColumn + "\u0000" + toColumn;
            if (relationKeys.add(key)) {
                relations.add(new Relation(
                        from, to, fromColumn, toColumn));
            }
        }
        return new SchemaGraph(
                nodes, List.copyOf(relations), truncated);
    }

    private static SchemaGraph emptyGraph() {
        return new SchemaGraph(
                List.of(), List.of(), false);
    }

    public static EntityRef parseEntity(String id, String name) {
        if (id == null) {
            throw new IllegalArgumentException("DataWorks 返回了空血缘实体 ID");
        }
        if (id.startsWith(TABLE_PREFIX)) {
            String[] parts = id.substring(TABLE_PREFIX.length())
                    .split(Pattern.quote("::"), -1);
            if (parts.length >= 2) {
                return new EntityRef(id, EntityKind.TABLE,
                        parts[0], parts[1], null, name);
            }
        }
        if (id.startsWith(COLUMN_PREFIX)) {
            String[] parts = id.substring(COLUMN_PREFIX.length())
                    .split(Pattern.quote("::"), -1);
            if (parts.length >= 3) {
                return new EntityRef(id, EntityKind.COLUMN,
                        parts[0], parts[1], parts[2], name);
            }
        }
        String fallback = blank(name) ? id : name;
        return new EntityRef(id, EntityKind.TABLE,
                "DataWorks", fallback, null, name);
    }

    private static String inferRegion(String endpoint) {
        if (blank(endpoint)) {
            return null;
        }
        Matcher matcher = REGION_PATTERN.matcher(endpoint);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String parameter(
            Map<String, Object> connection, String name) {
        for (Map.Entry<String, Object> entry
                : connection.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)
                    && entry.getValue() != null
                    && !entry.getValue().toString().isBlank()) {
                return entry.getValue().toString().trim();
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!blank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static void requirePart(String value, String label) {
        if (blank(value)) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (value.contains("::")) {
            throw new IllegalArgumentException(label + "不能包含 ::");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public enum Direction {
        UPSTREAM("仅上游", List.of(QueryDirection.UPSTREAM)),
        DOWNSTREAM("仅下游", List.of(QueryDirection.DOWNSTREAM)),
        BOTH("上下游", List.of(
                QueryDirection.UPSTREAM, QueryDirection.DOWNSTREAM));

        private final String label;
        private final List<QueryDirection> queries;

        Direction(String label, List<QueryDirection> queries) {
            this.label = label;
            this.queries = queries;
        }

        List<QueryDirection> queries() {
            return queries;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum EntityKind {
        TABLE("表血缘"),
        COLUMN("字段血缘");

        private final String label;

        EntityKind(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum ProbePolicy {
        MANUAL("仅手动", 0),
        ON_SELECTION("选择根表后", 0),
        EVERY_30_MINUTES("每 30 分钟", 30 * 60 * 1000),
        EVERY_6_HOURS("每 6 小时", 6 * 60 * 60 * 1000);

        private final String label;
        private final int intervalMs;

        ProbePolicy(String label, int intervalMs) {
            this.label = label;
            this.intervalMs = intervalMs;
        }

        public int intervalMs() {
            return intervalMs;
        }

        public static ProbePolicy fromValue(Object value) {
            if (value != null) {
                try {
                    return valueOf(value.toString().trim()
                            .toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // 使用安全的手动默认策略。
                }
            }
            return MANUAL;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum QueryDirection {
        UPSTREAM,
        DOWNSTREAM
    }

    @FunctionalInterface
    public interface LineageApi {
        List<Edge> list(String entityId, QueryDirection direction)
                throws Exception;
    }

    public record EntityRef(
            String id,
            EntityKind kind,
            String project,
            String table,
            String column,
            String name) {
        public EntityRef {
            id = Objects.requireNonNull(id, "id");
            kind = kind == null ? EntityKind.TABLE : kind;
            project = blank(project) ? "DataWorks" : project;
            table = blank(table) ? (blank(name) ? id : name) : table;
        }

        String tableKey() {
            return project.toLowerCase(Locale.ROOT) + "\u0000"
                    + table.toLowerCase(Locale.ROOT);
        }
    }

    public record Edge(EntityRef source, EntityRef target) {
        public Edge {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
        }

        String key() {
            return source.id() + "\u0000" + target.id();
        }
    }

    public record LineageResult(
            SchemaGraph graph,
            int edgeCount,
            Instant observedAt) {
    }

    public record TableNode(String schema, String name, List<ColumnNode> columns) {}
    public record ColumnNode(String name, String typeName, boolean primaryKey, String remarks) {}
    public record Relation(String from, String to, String fromColumn, String toColumn) {}
    public record SchemaGraph(List<TableNode> tables, List<Relation> relations, boolean truncated) {}
    private static String key(String project, String table) { return project + "." + table; }

    private record NodeDepth(String entityId, int depth) {
    }

    private static final class MutableTable {
        private final String project;
        private final String name;
        private final Map<String, ColumnNode> columns =
                new LinkedHashMap<>();

        private MutableTable(String project, String name) {
            this.project = project;
            this.name = name;
        }
    }

    private static final class AlibabaLineageApi implements LineageApi {
        private final Client client;

        private AlibabaLineageApi(Client client) {
            this.client = client;
        }

        @Override
        public List<Edge> list(
                String entityId, QueryDirection direction) throws Exception {
            List<Edge> edges = new ArrayList<>();
            int pageNumber = 1;
            while (pageNumber <= MAX_PAGES_PER_DIRECTION) {
                ListLineagesRequest request = new ListLineagesRequest()
                        .setNeedAttachRelationship(true)
                        .setPageNumber(pageNumber)
                        .setPageSize(PAGE_SIZE);
                if (direction == QueryDirection.UPSTREAM) {
                    request.setDstEntityId(entityId);
                } else {
                    request.setSrcEntityId(entityId);
                }
                ListLineagesResponse response = client.listLineages(request);
                ListLineagesResponseBody body = response == null
                        ? null : response.getBody();
                if (body == null || Boolean.FALSE.equals(body.getSuccess())) {
                    throw new IllegalStateException(
                            "DataWorks ListLineages 未返回成功结果");
                }
                ListLineagesResponseBodyPagingInfo paging =
                        body.getPagingInfo();
                if (paging == null || paging.getLineages() == null
                        || paging.getLineages().isEmpty()) {
                    break;
                }
                for (ListLineagesResponseBodyPagingInfoLineages item
                        : paging.getLineages()) {
                    LineageEntity source = item.getSrcEntity();
                    LineageEntity target = item.getDstEntity();
                    if (source == null || target == null
                            || blank(source.getId()) || blank(target.getId())) {
                        continue;
                    }
                    edges.add(new Edge(
                            parseEntity(source.getId(), source.getName()),
                            parseEntity(target.getId(), target.getName())));
                }
                long totalCount = paging.getTotalCount() == null
                        ? edges.size() : paging.getTotalCount();
                if ((long) pageNumber * PAGE_SIZE >= totalCount) {
                    break;
                }
                if (pageNumber == MAX_PAGES_PER_DIRECTION) {
                    throw new IllegalStateException("血缘分页超过安全上限，请缩小探查范围");
                }
                pageNumber++;
            }
            return List.copyOf(edges);
        }
    }
}
