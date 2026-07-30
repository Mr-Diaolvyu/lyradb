package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.metadata.snapshot.MetadataSnapshot;
import io.github.lexaquila.lyradb.metadata.snapshot.MetadataSnapshotRenderer;
import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 显式采集授权范围内的纯结构元数据，并生成短生命周期快照。
 */
@Service
public class EnterpriseMetadataSnapshotService {

    private static final Set<String> TABLE_TYPES =
            Set.of("TABLE", "VIEW", "COLLECTION");
    private static final Set<String> CONTAINER_TYPES =
            Set.of("DATABASE", "SCHEMA", "PROJECT");
    private static final int MAX_TABLES = 200;
    private static final int MAX_COLUMNS = 5_000;
    private static final int PREVIEW_TABLES = 20;

    private final GrantService grantService;
    private final DataSourceService dataSourceService;
    private final ApprovalSecurityContextService securityContextService;
    private final MetadataSnapshotSessionStore snapshotStore;
    private final MetadataSnapshotRenderer renderer =
            new MetadataSnapshotRenderer();

    public EnterpriseMetadataSnapshotService(
            GrantService grantService,
            DataSourceService dataSourceService,
            ApprovalSecurityContextService securityContextService,
            MetadataSnapshotSessionStore snapshotStore) {
        this.grantService = grantService;
        this.dataSourceService = dataSourceService;
        this.securityContextService = securityContextService;
        this.snapshotStore = snapshotStore;
    }

    public CaptureResult capture(
            String workspaceId, User owner, CaptureRequest request) {
        if (request == null || request.grantedSourceName() == null
                || request.grantedSourceName().isBlank()) {
            throw new IllegalArgumentException(
                    "grantedSourceName 不能为空");
        }
        MetadataSnapshotSessionStore.MapScope scope =
                normalizeScope(request);
        Grant grant = grantService.resolveForUser(
                owner.getId(), workspaceId,
                request.grantedSourceName().trim());
        validateRequestedScope(grant, scope);
        DataSource source =
                dataSourceService.getEntity(grant.getDataSourceId());
        if (!workspaceId.equals(source.getWorkspaceId())) {
            throw new AccessDeniedException(
                    "数据源不属于当前工作空间");
        }
        if ("REDIS".equalsIgnoreCase(source.getDbType())) {
            throw new IllegalArgumentException(
                    "Redis 无关系型结构快照，已禁止扫描键和值");
        }

        String before = securityContextService.fingerprint(grant);
        SnapshotBuild build = buildSnapshot(source, grant, scope);
        Grant freshGrant = grantService.getByIdForUser(
                grant.getId(), owner.getId(), workspaceId);
        if (!grant.getDataSourceId().equals(freshGrant.getDataSourceId())
                || !grant.getGrantedSourceName().equals(
                freshGrant.getGrantedSourceName())) {
            throw new AccessDeniedException(
                    "元数据采集期间授权资源发生变化");
        }
        validateRequestedScope(freshGrant, scope);
        String after = securityContextService.fingerprint(freshGrant);
        if (!constantTimeEquals(before, after)) {
            throw new IllegalStateException(
                    "元数据采集期间授权或数据源配置发生变化");
        }

        long tokens = renderer.estimateJsonTokens(build.snapshot());
        String contentSha256 = sha256(
                renderer.toJsonUtf8(build.snapshot()));
        MetadataSnapshotSessionStore.SnapshotSession session =
                snapshotStore.create(
                        owner.getId(), workspaceId, freshGrant.getId(),
                        freshGrant.getDataSourceId(),
                        freshGrant.getGrantedSourceName(), after, scope,
                        build.snapshot(), build.tableCount(),
                        build.columnCount(), tokens);
        CountSummary counts = count(build.snapshot());
        return new CaptureResult(
                session.id(), freshGrant.getDataSourceId(),
                freshGrant.getGrantedSourceName(),
                scope.database(), scope.schemas(), scope.tables(),
                counts.databaseCount(), counts.schemaCount(),
                build.tableCount(), build.columnCount(), tokens,
                preview(build.snapshot()), contentSha256,
                session.expiresAt());
    }

    public RenderedSnapshot download(
            String workspaceId, User owner,
            String snapshotId, String format) {
        MetadataSnapshotSessionStore.SnapshotSession session =
                requireCurrent(workspaceId, owner, snapshotId);
        String normalized = format == null
                ? "json" : format.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "json" -> rendered(
                    renderer.toJsonUtf8(session.snapshot()),
                    "application/json;charset=UTF-8",
                    "lyradb-metadata-" + snapshotId + ".json",
                    session);
            case "markdown", "md" -> rendered(
                    renderer.toMarkdownUtf8(session.snapshot()),
                    "text/markdown;charset=UTF-8",
                    "lyradb-metadata-" + snapshotId + ".md",
                    session);
            default -> throw new IllegalArgumentException(
                    "format 仅支持 json/markdown");
        };
    }

    /**
     * AI 附加时重新验证用户、工作空间、授权与安全指纹。
     */
    public MetadataSnapshotSessionStore.SnapshotSession consumeForAi(
            String workspaceId, User owner, String snapshotId) {
        MetadataSnapshotSessionStore.SnapshotSession session =
                snapshotStore.consumeForAi(
                        snapshotId, owner.getId(), workspaceId);
        validateCurrentGrant(owner, workspaceId, session);
        return session;
    }

    public String renderForAi(
            MetadataSnapshotSessionStore.SnapshotSession session) {
        return renderer.toJson(session.snapshot());
    }

    public MetadataAudit auditOf(
            MetadataSnapshotSessionStore.SnapshotSession session) {
        if (session == null) {
            throw new IllegalArgumentException("元数据快照不能为空");
        }
        return new MetadataAudit(
                session.id(), session.scope(),
                sha256(renderer.toJsonUtf8(session.snapshot())));
    }

    private RenderedSnapshot rendered(
            byte[] content, String contentType, String fileName,
            MetadataSnapshotSessionStore.SnapshotSession session) {
        return new RenderedSnapshot(
                content, contentType, fileName,
                sha256(content), session);
    }

    private MetadataSnapshotSessionStore.SnapshotSession requireCurrent(
            String workspaceId, User owner, String snapshotId) {
        MetadataSnapshotSessionStore.SnapshotSession session =
                snapshotStore.require(
                        snapshotId, owner.getId(), workspaceId);
        validateCurrentGrant(owner, workspaceId, session);
        return session;
    }

    private void validateCurrentGrant(
            User owner, String workspaceId,
            MetadataSnapshotSessionStore.SnapshotSession session) {
        Grant grant = grantService.getByIdForUser(
                session.grantId(), owner.getId(), workspaceId);
        if (!session.dataSourceId().equals(grant.getDataSourceId())
                || !session.grantedSourceName().equals(
                        grant.getGrantedSourceName())) {
            throw new AccessDeniedException(
                    "元数据快照与当前授权不匹配");
        }
        String current = securityContextService.fingerprint(grant);
        if (!constantTimeEquals(
                session.securityContextHash(), current)) {
            throw new AccessDeniedException(
                    "元数据快照生成后授权或数据源配置已变化");
        }
    }

    private SnapshotBuild buildSnapshot(
            DataSource source, Grant grant,
            MetadataSnapshotSessionStore.MapScope scope) {
        ConnectionService.ActiveConnection active =
                dataSourceService.resolveActiveConnection(source.getId());
        boolean mongo =
                "MONGODB".equalsIgnoreCase(source.getDbType());
        Map<String, Map<String, List<MetadataSnapshot.Table>>> grouped =
                new LinkedHashMap<>();
        Set<String> matchedRequested = new LinkedHashSet<>();
        int[] counts = {0, 0};

        try (ConnectionService.ActiveConnection.Lease ignored =
                     active.acquire()) {
            List<TreeNode> roots =
                    safeNodes(active.driver.getTreeNodes(
                            active.connection, null));
            for (TreeNode root : roots) {
                if (isTable(root)) {
                    String schema = defaultSchema(grant, scope);
                    addTable(active, source, grant, scope,
                            "default", schema, tableOwnerPath(root),
                            root, mongo,
                            grouped, matchedRequested, counts);
                    continue;
                }
                if (!isContainer(root)) {
                    continue;
                }
                String rootType = upper(root.getType());
                if (!containerTraversalAuthorized(
                        source.getDbType(), grant, scope, null, root)) {
                    continue;
                }
                List<TreeNode> children = safeNodes(
                        active.driver.getTreeNodes(
                                active.connection, root.getPath()));
                for (TreeNode child : children) {
                    if (isTable(child)) {
                        String database = "DATABASE".equals(rootType)
                                ? root.getName() : databaseName(scope);
                        String schema = "SCHEMA".equals(rootType)
                                || "PROJECT".equals(rootType)
                                ? root.getName() : "";
                        addTable(active, source, grant, scope,
                                database, schema, containerPath(root),
                                child, mongo,
                                grouped, matchedRequested, counts);
                        continue;
                    }
                    if (!mongo && isContainer(child)) {
                        if (!containerTraversalAuthorized(source.getDbType(),
                                grant, scope, root.getName(), child)) {
                            continue;
                        }
                        List<TreeNode> tables = safeNodes(
                                active.driver.getTreeNodes(
                                        active.connection,
                                        child.getPath()));
                        for (TreeNode table : tables) {
                            if (isTable(table)) {
                                addTable(active, source, grant, scope,
                                        root.getName(), child.getName(),
                                        containerPath(child), table, false,
                                        grouped,
                                        matchedRequested, counts);
                            }
                        }
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "元数据采集已中断", exception);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "无法读取授权范围内的数据库结构", exception);
        }

        for (String requested : scope.tables()) {
            if (!matchedRequested.contains(
                    requested.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "请求的授权表不存在或无法读取: " + requested);
            }
        }
        MetadataSnapshot snapshot = toSnapshot(
                source, grant, grouped);
        return new SnapshotBuild(snapshot, counts[0], counts[1]);
    }

    private void addTable(
            ConnectionService.ActiveConnection active,
            DataSource source, Grant grant,
            MetadataSnapshotSessionStore.MapScope scope,
            String database, String schema, String ownerNamespace,
            TreeNode node,
            boolean mongo,
            Map<String, Map<String, List<MetadataSnapshot.Table>>> grouped,
            Set<String> matchedRequested, int[] counts) throws Exception {
        String table = node.getName();
        List<String> candidates =
                qualifiedCandidates(database, schema, table);
        if (!schemaAuthorized(grant, database, schema)
                || !authorized(grant, candidates)
                || !scopeMatches(scope, database, schema, candidates)) {
            return;
        }
        if (counts[0] >= MAX_TABLES) {
            throw new IllegalArgumentException(
                    "元数据快照表数量超过 200 张");
        }
        MetadataSnapshot.Table snapshotTable;
        if (mongo) {
            // MongoDB 驱动的 getTableColumns 会采样文档，此处只保留库/集合树结构。
            snapshotTable = new MetadataSnapshot.Table(
                    table, node.getType(), "",
                    List.of(), List.of());
        } else {
            List<ColumnMetadata> columns =
                    active.driver.getTableColumns(
                            active.connection, blankToNull(ownerNamespace),
                            table);
            List<ColumnMetadata> safeColumns =
                    columns == null ? List.of() : columns;
            if (counts[1] + safeColumns.size() > MAX_COLUMNS) {
                throw new IllegalArgumentException(
                        "元数据快照字段数量超过 5000 个");
            }
            snapshotTable =
                    MetadataSnapshot.Table.fromTreeNode(
                            node, safeColumns);
            counts[1] += safeColumns.size();
        }
        grouped.computeIfAbsent(
                        database == null || database.isBlank()
                                ? "default" : database,
                        ignored -> new LinkedHashMap<>())
                .computeIfAbsent(
                        schema == null ? "" : schema,
                        ignored -> new ArrayList<>())
                .add(snapshotTable);
        counts[0]++;
        for (String requested : scope.tables()) {
            if (candidates.stream().anyMatch(
                    value -> value.equalsIgnoreCase(requested))) {
                matchedRequested.add(
                        requested.toLowerCase(Locale.ROOT));
            }
        }
    }

    private static MetadataSnapshot toSnapshot(
            DataSource source, Grant grant,
            Map<String, Map<String, List<MetadataSnapshot.Table>>>
                    grouped) {
        List<MetadataSnapshot.Database> databases =
                new ArrayList<>();
        grouped.forEach((database, schemas) -> {
            List<MetadataSnapshot.Schema> schemaValues =
                    new ArrayList<>();
            schemas.forEach((schema, tables) ->
                    schemaValues.add(new MetadataSnapshot.Schema(
                            schema, "", tables)));
            databases.add(new MetadataSnapshot.Database(
                    database, "", schemaValues));
        });
        MetadataSnapshot.DataSource dataSource =
                new MetadataSnapshot.DataSource(
                        "", grant.getGrantedSourceName(),
                        source.getDbType(), "", databases);
        return MetadataSnapshot.of(List.of(dataSource));
    }

    private static void validateRequestedScope(
            Grant grant,
            MetadataSnapshotSessionStore.MapScope scope) {
        Set<String> allowedSchemas =
                SqlParseUtil.splitCsv(grant.getAllowedSchemas());
        Set<String> allowedTables =
                SqlParseUtil.splitCsv(grant.getAllowedTables());
        Set<String> blockedTables =
                SqlParseUtil.splitCsv(grant.getBlockedTables());
        if (allowedSchemas.isEmpty() || allowedTables.isEmpty()) {
            throw new AccessDeniedException(
                    "授权未配置可用 Schema 与表白名单");
        }
        for (String schema : scope.schemas()) {
            if (!SqlParseUtil.matchAny(schema, allowedSchemas)) {
                throw new AccessDeniedException(
                        "请求包含未授权 Schema");
            }
        }
        for (String table : scope.tables()) {
            if (!table.contains(".") || table.contains("*")
                    || !SqlParseUtil.matchAny(table, allowedTables)
                    || SqlParseUtil.matchAny(table, blockedTables)) {
                throw new AccessDeniedException(
                        "请求包含未授权或不完整限定的表");
            }
            String schema = SqlParseUtil.schemaOf(table);
            if (schema == null
                    || !SqlParseUtil.matchAny(
                            schema, allowedSchemas)) {
                throw new AccessDeniedException(
                        "请求表的 Schema 未授权");
            }
        }
    }

    /** 在读取任何容器子节点前同时应用请求范围与授权 Schema 门禁。 */
    private static boolean containerTraversalAuthorized(
            String dbType, Grant grant,
            MetadataSnapshotSessionStore.MapScope scope,
            String parentDatabase, TreeNode node) {
        String type = upper(node.getType());
        if ("SCHEMA".equals(type) || "PROJECT".equals(type)) {
            String database = parentDatabase == null
                    ? scope.database() : parentDatabase;
            return schemaSelected(scope, node.getName())
                    && schemaAuthorized(
                    grant, database, node.getName());
        }
        if (!"DATABASE".equals(type)) {
            return false;
        }
        if (scope.database() != null
                && !scope.database().equalsIgnoreCase(node.getName())) {
            return false;
        }
        String normalizedDbType = dbType == null ? ""
                : dbType.trim().toUpperCase(Locale.ROOT);
        if ("MSSQL".equals(normalizedDbType)
                || "SQLSERVER".equals(normalizedDbType)) {
            return sqlServerDatabaseMayContainAuthorizedSchema(
                    grant, node.getName());
        }
        return schemaAuthorized(grant, node.getName(), "");
    }

    private static boolean sqlServerDatabaseMayContainAuthorizedSchema(
            Grant grant, String database) {
        Set<String> allowed =
                SqlParseUtil.splitCsv(grant.getAllowedSchemas());
        for (String pattern : allowed) {
            String normalized = pattern.replace('/', '.');
            int separator = normalized.indexOf('.');
            if (separator < 0) {
                return true;
            }
            String databasePattern =
                    normalized.substring(0, separator);
            if (SqlParseUtil.matchAny(
                    database, Set.of(databasePattern))) {
                return true;
            }
        }
        return false;
    }

    private static boolean schemaAuthorized(
            Grant grant, String database, String schema) {
        Set<String> allowed =
                SqlParseUtil.splitCsv(grant.getAllowedSchemas());
        if (allowed.isEmpty()) {
            return false;
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (schema != null && !schema.isBlank()) {
            candidates.add(schema);
            if (database != null && !database.isBlank()) {
                candidates.add(database + "." + schema);
                candidates.add(database + "/" + schema);
            }
        } else if (database != null && !database.isBlank()) {
            candidates.add(database);
        }
        return candidates.stream().anyMatch(
                value -> SqlParseUtil.matchAny(value, allowed));
    }

    private static boolean authorized(
            Grant grant, List<String> candidates) {
        Set<String> allowed =
                SqlParseUtil.splitCsv(grant.getAllowedTables());
        Set<String> blocked =
                SqlParseUtil.splitCsv(grant.getBlockedTables());
        return candidates.stream().anyMatch(
                value -> SqlParseUtil.matchAny(value, allowed))
                && candidates.stream().noneMatch(
                value -> SqlParseUtil.matchAny(value, blocked));
    }

    private static boolean scopeMatches(
            MetadataSnapshotSessionStore.MapScope scope,
            String database, String schema, List<String> candidates) {
        if (scope.database() != null && database != null
                && !scope.database().equalsIgnoreCase(database)) {
            return false;
        }
        if (!scope.schemas().isEmpty()
                && scope.schemas().stream().noneMatch(
                value -> value.equalsIgnoreCase(schema))) {
            return false;
        }
        return scope.tables().isEmpty()
                || scope.tables().stream().anyMatch(
                requested -> candidates.stream().anyMatch(
                        value -> value.equalsIgnoreCase(requested)));
    }
    private static boolean schemaSelected(
            MetadataSnapshotSessionStore.MapScope scope,
            String schema) {
        return scope.schemas().isEmpty()
                || scope.schemas().stream().anyMatch(
                value -> value.equalsIgnoreCase(schema));
    }

    private static String containerPath(TreeNode node) {
        if (node == null) {
            return null;
        }
        String path = blankToNull(node.getPath());
        return path == null ? blankToNull(node.getName()) : path;
    }

    private static String tableOwnerPath(TreeNode node) {
        if (node == null) {
            return null;
        }
        String path = blankToNull(node.getPath());
        if (path == null) {
            return null;
        }
        int separator = path.lastIndexOf('/');
        if (separator <= 0) {
            return null;
        }
        String leaf = path.substring(separator + 1);
        if (!leaf.equalsIgnoreCase(node.getName())) {
            return null;
        }
        return path.substring(0, separator);
    }


    private static MetadataSnapshotSessionStore.MapScope normalizeScope(
            CaptureRequest request) {
        String database = blankToNull(request.database());
        List<String> schemas = normalizeNames(
                request.schemas(), "schemas");
        List<String> tables = normalizeNames(
                request.tables(), "tables");
        if (database == null && schemas.isEmpty()
                && tables.isEmpty()) {
            throw new IllegalArgumentException(
                    "必须明确选择 database、schemas 或 tables 范围");
        }
        return new MetadataSnapshotSessionStore.MapScope(
                database, schemas, tables);
    }

    private static List<String> normalizeNames(
            List<String> values, String field) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > MAX_TABLES) {
            throw new IllegalArgumentException(
                    field + " 数量超过限制");
        }
        LinkedHashSet<String> normalized =
                new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()
                    || value.trim().length() > 1_024) {
                throw new IllegalArgumentException(
                        field + " 包含无效名称");
            }
            normalized.add(value.trim());
        }
        List<String> sorted = new ArrayList<>(normalized);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(sorted);
    }

    private static List<String> qualifiedCandidates(
            String database, String schema, String table) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (database != null && !database.isBlank()
                && schema != null && !schema.isBlank()) {
            values.add(database + "." + schema + "." + table);
        }
        if (schema != null && !schema.isBlank()) {
            values.add(schema + "." + table);
        }
        if (database != null && !database.isBlank()) {
            values.add(database + "." + table);
        }
        values.add(table);
        return List.copyOf(values);
    }

    private static String defaultSchema(
            Grant grant,
            MetadataSnapshotSessionStore.MapScope scope) {
        if (!scope.schemas().isEmpty()) {
            return scope.schemas().get(0);
        }
        return SqlParseUtil.splitCsv(grant.getAllowedSchemas())
                .stream().filter(value -> !value.contains("*"))
                .findFirst().orElse("");
    }

    private static String databaseName(
            MetadataSnapshotSessionStore.MapScope scope) {
        return scope.database() == null
                ? "default" : scope.database();
    }

    private static List<TreeNode> safeNodes(
            List<TreeNode> values) {
        return values == null ? List.of() : values;
    }

    private static boolean isTable(TreeNode node) {
        return node != null
                && TABLE_TYPES.contains(upper(node.getType()));
    }

    private static boolean isContainer(TreeNode node) {
        return node != null
                && CONTAINER_TYPES.contains(upper(node.getType()));
    }

    private static String upper(String value) {
        return value == null ? ""
                : value.toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank()
                ? null : value.trim();
    }

    private static boolean constantTimeEquals(
            String expected, String actual) {
        byte[] left = expected == null ? new byte[0]
                : expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = actual == null ? new byte[0]
                : actual.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(left, right);
    }

    private static String sha256(byte[] content) {
        try {
            byte[] digest = java.security.MessageDigest
                    .getInstance("SHA-256").digest(content);
            StringBuilder result =
                    new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "无法计算元数据内容摘要", exception);
        }
    }

    private static CountSummary count(
            MetadataSnapshot snapshot) {
        int databases = 0;
        int schemas = 0;
        for (MetadataSnapshot.DataSource source :
                snapshot.dataSources()) {
            databases += source.databases().size();
            for (MetadataSnapshot.Database database :
                    source.databases()) {
                schemas += database.schemas().size();
            }
        }
        return new CountSummary(databases, schemas);
    }

    private static List<TablePreview> preview(
            MetadataSnapshot snapshot) {
        List<TablePreview> result = new ArrayList<>();
        for (MetadataSnapshot.DataSource source :
                snapshot.dataSources()) {
            for (MetadataSnapshot.Database database :
                    source.databases()) {
                for (MetadataSnapshot.Schema schema :
                        database.schemas()) {
                    for (MetadataSnapshot.Table table :
                            schema.tables()) {
                        if (result.size() >= PREVIEW_TABLES) {
                            return List.copyOf(result);
                        }
                        result.add(new TablePreview(
                                database.name(), schema.name(),
                                table.name(), table.type(),
                                table.columns().stream()
                                        .map(MetadataSnapshot.Column::name)
                                        .limit(20).toList()));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    public record CaptureRequest(
            String grantedSourceName,
            String database,
            List<String> schemas,
            List<String> tables) {
    }

    public record CaptureResult(
            String id,
            String dataSourceId,
            String grantedSourceName,
            String database,
            List<String> schemas,
            List<String> tables,
            int databaseCount,
            int schemaCount,
            int tableCount,
            int columnCount,
            long approximateTokens,
            List<TablePreview> preview,
            String contentSha256,
            java.time.LocalDateTime expiresAt) {
    }

    public record TablePreview(
            String database, String schema,
            String table, String type,
            List<String> columns) {
    }

    public record RenderedSnapshot(
            byte[] content, String contentType,
            String fileName,
            String contentSha256,
            MetadataSnapshotSessionStore.SnapshotSession session) {
    }

    public record MetadataAudit(
            String snapshotId,
            MetadataSnapshotSessionStore.MapScope scope,
            String contentSha256) {
    }

    private record SnapshotBuild(
            MetadataSnapshot snapshot,
            int tableCount, int columnCount) {
    }

    private record CountSummary(
            int databaseCount, int schemaCount) {
    }
}
