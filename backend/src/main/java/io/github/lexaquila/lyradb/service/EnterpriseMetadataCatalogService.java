package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.EnterpriseMetadataCatalog;
import io.github.lexaquila.lyradb.model.dto.ErDiagram;
import io.github.lexaquila.lyradb.model.dto.TableConstraintMetadata;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业版元数据工作区服务。
 *
 * <p>先解析当前用户的逻辑授权，再读取真实连接。所有返回对象均经过 Schema、
 * 表白名单和黑名单三重过滤；缓存键包含授权安全指纹，授权变更后旧缓存不会命中。</p>
 */
@Service
public class EnterpriseMetadataCatalogService {

    static final int MAX_TABLES = 2_500;
    static final int MAX_ER_TABLES = 24;
    private static final long CACHE_TTL_MS = 5 * 60_000L;
    private static final Set<String> TABLE_TYPES =
            Set.of("TABLE", "VIEW", "COLLECTION");
    private static final Set<String> CONTAINER_TYPES =
            Set.of("DATABASE", "SCHEMA", "PROJECT");

    private final GrantService grantService;
    private final DataSourceService dataSourceService;
    private final SecurityUtil securityUtil;
    private final ApprovalSecurityContextService securityContextService;
    private final Map<String, CachedCatalog> cache =
            new ConcurrentHashMap<>();

    public EnterpriseMetadataCatalogService(
            GrantService grantService,
            DataSourceService dataSourceService,
            SecurityUtil securityUtil,
            ApprovalSecurityContextService securityContextService) {
        this.grantService = grantService;
        this.dataSourceService = dataSourceService;
        this.securityUtil = securityUtil;
        this.securityContextService = securityContextService;
    }

    /**
     * 返回当前用户可见的完整轻量目录。常规请求命中内存缓存，显式刷新才重新扫描。
     */
    public EnterpriseMetadataCatalog catalog(
            String grantedSourceName, boolean refresh) throws Exception {
        AccessContext access = requireAccess(grantedSourceName);
        String fingerprint =
                securityContextService.fingerprint(access.grant());
        CachedCatalog cached = cache.get(access.grant().getId());
        long now = System.currentTimeMillis();
        if (!refresh && cached != null
                && cached.fingerprint().equals(fingerprint)
                && now - cached.createdAt() < CACHE_TTL_MS) {
            return cached.catalog();
        }

        EnterpriseMetadataCatalog catalog =
                loadCatalog(access, fingerprint);
        cache.put(access.grant().getId(),
                new CachedCatalog(fingerprint, now, catalog));
        return catalog;
    }

    /**
     * 懒加载单表字段，用于补全和字段注释显示，不执行数据预览。
     */
    public List<ColumnMetadata> columns(
            String grantedSourceName, String namespace, String table)
            throws Exception {
        AccessContext access = requireAccess(grantedSourceName);
        AuthorizedTable authorized =
                authorizeTable(access.grant(), namespace, table);
        ConnectionService.ActiveConnection active =
                dataSourceService.resolveActiveConnection(
                        access.grant().getDataSourceId());
        try (ConnectionService.ActiveConnection.Lease ignored =
                     active.acquire()) {
            List<ColumnMetadata> columns =
                    active.driver.getTableColumns(
                            active.connection,
                            authorized.driverNamespace(), table.trim());
            return columns == null ? List.of() : List.copyOf(columns);
        }
    }

    /** 只读取用户明确选择的授权表，避免对整个 Schema 做 N+1 元数据扫描。 */
    public ErDiagram erDiagram(
            String grantedSourceName,
            String schema,
            List<String> tableNames) throws Exception {
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException(
                    "企业 ER 图必须明确选择 Schema");
        }
        List<String> requested = requestedErTables(tableNames);
        AccessContext access = requireAccess(grantedSourceName);
        EnterpriseMetadataCatalog catalog =
                catalog(grantedSourceName, false);
        Map<String, EnterpriseMetadataCatalog.Table> authorizedByName =
                new LinkedHashMap<>();
        catalog.getTables().stream()
                .filter(table -> schema.equalsIgnoreCase(table.getSchema()))
                .forEach(table -> authorizedByName.putIfAbsent(
                        table.getName().toLowerCase(Locale.ROOT), table));

        List<EnterpriseMetadataCatalog.Table> selected = new ArrayList<>();
        for (String tableName : requested) {
            EnterpriseMetadataCatalog.Table table = authorizedByName.get(
                    tableName.toLowerCase(Locale.ROOT));
            if (table == null) {
                throw new AccessDeniedException(
                        "所选表不存在或不在当前授权范围内: " + tableName);
            }
            selected.add(table);
        }

        ErDiagram diagram = new ErDiagram();
        diagram.setSourceName(grantedSourceName);
        diagram.setDbType(access.dataSource().getDbType());
        diagram.setSchema(schema);
        diagram.setTruncated(false);
        Map<String, ErDiagram.Table> byName = new LinkedHashMap<>();
        ConnectionService.ActiveConnection active =
                dataSourceService.resolveActiveConnection(
                        access.grant().getDataSourceId());
        try (ConnectionService.ActiveConnection.Lease ignored =
                     active.acquire()) {
            for (EnterpriseMetadataCatalog.Table table : selected) {
                authorizeTable(access.grant(),
                        table.getNamespace(), table.getName());
                ErDiagram.Table node = new ErDiagram.Table(
                        table.getName(), table.getSchema());
                node.setRemarks(table.getRemarks());
                List<ColumnMetadata> columns = safeColumns(
                        active.driver.getTableColumns(
                                active.connection,
                                table.getNamespace(), table.getName()));
                for (ColumnMetadata column : columns) {
                    node.getColumns().add(column.getName());
                    node.getColumnDetails().add(new ErDiagram.Column(
                            column.getName(), column.getTypeName(),
                            column.getRemarks(), column.isPrimaryKey()));
                }
                byName.put(table.getName().toLowerCase(Locale.ROOT), node);
                diagram.getTables().add(node);
            }

            if ("MAXCOMPUTE".equalsIgnoreCase(
                    access.dataSource().getDbType())) {
                // 未接入作业血缘，不推测关系，也不逐表额外读取 DDL。
                return diagram;
            }
            for (EnterpriseMetadataCatalog.Table table : selected) {
                List<TableConstraintMetadata> constraints;
                try {
                    constraints = active.driver.getTableConstraints(
                            active.connection,
                            table.getNamespace(), table.getName());
                } catch (Exception ignoredConstraintFailure) {
                    continue;
                }
                if (constraints == null) {
                    continue;
                }
                for (TableConstraintMetadata constraint : constraints) {
                    if (!"FOREIGN_KEY".equalsIgnoreCase(constraint.getType())
                            || constraint.getReferencedTable() == null) {
                        continue;
                    }
                    String target = leafName(constraint.getReferencedTable());
                    if (!byName.containsKey(target.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    int count = Math.min(
                            constraint.getColumns().size(),
                            constraint.getReferencedColumns().size());
                    for (int index = 0; index < count; index++) {
                        diagram.getEdges().add(new ErDiagram.Edge(
                                table.getName(), target,
                                constraint.getColumns().get(index),
                                constraint.getReferencedColumns().get(index)));
                    }
                }
            }
        }
        return diagram;
    }

    private static List<String> requestedErTables(List<String> tableNames) {
        if (tableNames == null || tableNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "请至少选择 1 张表后再构建关系图");
        }
        Map<String, String> deduplicated = new LinkedHashMap<>();
        for (String tableName : tableNames) {
            if (tableName != null && !tableName.isBlank()) {
                String value = tableName.trim();
                deduplicated.putIfAbsent(
                        value.toLowerCase(Locale.ROOT), value);
            }
        }
        if (deduplicated.isEmpty()) {
            throw new IllegalArgumentException(
                    "请至少选择 1 张表后再构建关系图");
        }
        if (deduplicated.size() > MAX_ER_TABLES) {
            throw new IllegalArgumentException(
                    "关系图一次最多选择 " + MAX_ER_TABLES + " 张表");
        }
        return List.copyOf(deduplicated.values());
    }

    private EnterpriseMetadataCatalog loadCatalog(
            AccessContext access, String expectedFingerprint)
            throws Exception {
        Grant grant = access.grant();
        EnterpriseMetadataCatalog result =
                new EnterpriseMetadataCatalog();
        result.setGrantedSourceName(grant.getGrantedSourceName());
        result.setDbType(access.dataSource().getDbType());

        Map<String, EnterpriseMetadataCatalog.Table> tables =
                new LinkedHashMap<>();
        ConnectionService.ActiveConnection active =
                dataSourceService.resolveActiveConnection(
                        grant.getDataSourceId());
        try (ConnectionService.ActiveConnection.Lease ignored =
                     active.acquire()) {
            List<TreeNode> roots = safeNodes(
                    active.driver.getTreeNodes(
                            active.connection, null));
            for (TreeNode root : roots) {
                if (tables.size() >= MAX_TABLES) {
                    result.setTruncated(true);
                    break;
                }
                if (isTable(root)) {
                    addRootTable(grant, root, tables);
                } else if (isContainer(root)
                        && mayTraverseRoot(
                        access.dataSource().getDbType(), grant, root)) {
                    collectChildren(active, grant, root, tables);
                }
            }
        }

        Grant fresh = grantService.getByIdForUser(
                grant.getId(), access.user().getId(),
                grant.getWorkspaceId());
        if (!expectedFingerprint.equals(
                securityContextService.fingerprint(fresh))) {
            throw new AccessDeniedException(
                    "元数据读取期间授权发生变化，请重试");
        }

        List<EnterpriseMetadataCatalog.Table> ordered =
                new ArrayList<>(tables.values());
        ordered.sort(Comparator
                .comparing(EnterpriseMetadataCatalog.Table::getSchema,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(
                        EnterpriseMetadataCatalog.Table::getName,
                        String.CASE_INSENSITIVE_ORDER));
        result.setTables(ordered);
        result.setSchemas(ordered.stream()
                .map(EnterpriseMetadataCatalog.Table::getSchema)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList());
        result.setRefreshedAt(System.currentTimeMillis());
        if (ordered.size() >= MAX_TABLES) {
            result.setTruncated(true);
        }
        return result;
    }

    private void collectChildren(
            ConnectionService.ActiveConnection active,
            Grant grant, TreeNode root,
            Map<String, EnterpriseMetadataCatalog.Table> tables)
            throws Exception {
        List<TreeNode> children = safeNodes(
                active.driver.getTreeNodes(
                        active.connection, containerPath(root)));
        for (TreeNode child : children) {
            if (tables.size() >= MAX_TABLES) {
                return;
            }
            if (isTable(child)) {
                String schema = root.getName();
                addTable(grant, schema, containerPath(root),
                        child, tables);
                continue;
            }
            if (!isContainer(child)) {
                continue;
            }
            String database = "DATABASE".equals(
                    upper(root.getType())) ? root.getName() : "";
            if (!schemaAuthorized(
                    grant, database, child.getName())) {
                continue;
            }
            List<TreeNode> nested = safeNodes(
                    active.driver.getTreeNodes(
                            active.connection,
                            containerPath(child)));
            for (TreeNode table : nested) {
                if (tables.size() >= MAX_TABLES) {
                    return;
                }
                if (isTable(table)) {
                    String schema = database.isBlank()
                            ? child.getName()
                            : database + "." + child.getName();
                    addTable(grant, schema,
                            containerPath(child), table, tables);
                }
            }
        }
    }

    private static void addRootTable(
            Grant grant, TreeNode node,
            Map<String, EnterpriseMetadataCatalog.Table> tables) {
        String owner = tableOwnerPath(node);
        String schema = owner == null
                ? firstConcreteSchema(grant)
                : normalizeNamespace(owner);
        addTable(grant, schema,
                owner == null ? schema : owner,
                node, tables);
    }

    private static void addTable(
            Grant grant, String schema, String namespace,
            TreeNode node,
            Map<String, EnterpriseMetadataCatalog.Table> tables) {
        if (node == null || node.getName() == null
                || node.getName().isBlank()) {
            return;
        }
        AuthorizedTable authorized = findAuthorizedTable(
                grant, schema, namespace, node.getName());
        if (authorized == null) {
            return;
        }
        String qualified = authorized.schema()
                + "." + node.getName();
        String remarks = node.getProperties() == null ? null
                : text(node.getProperties().get("remarks"));
        EnterpriseMetadataCatalog.Table table =
                new EnterpriseMetadataCatalog.Table(
                        authorized.schema(),
                        authorized.driverNamespace(),
                        node.getName(), qualified,
                        upper(node.getType()), remarks);
        tables.putIfAbsent(
                qualified.toLowerCase(Locale.ROOT), table);
    }

    private static AuthorizedTable authorizeTable(
            Grant grant, String namespace, String table) {
        if (namespace == null || namespace.isBlank()
                || table == null || table.isBlank()) {
            throw new IllegalArgumentException(
                    "企业元数据读取必须指定 Schema 和表名");
        }
        AuthorizedTable authorized = findAuthorizedTable(
                grant, namespace, namespace, table);
        if (authorized == null) {
            throw new AccessDeniedException(
                    "Schema 或表不在授权范围内");
        }
        return authorized;
    }

    /**
     * 完整命名空间和末级 Schema 都只作为候选；候选 Schema 与同候选下的完整
     * 表白名单必须同时命中，避免多库同名 Schema 造成授权串用。
     */
    private static AuthorizedTable findAuthorizedTable(
            Grant grant, String logicalNamespace,
            String driverNamespace, String table) {
        String normalized =
                normalizeNamespace(logicalNamespace);
        String safeTable = table == null
                ? "" : table.trim();
        if (normalized.isBlank() || safeTable.isBlank()) {
            return null;
        }
        LinkedHashSet<String> schemaCandidates =
                new LinkedHashSet<>();
        schemaCandidates.add(normalized);
        int separator = normalized.lastIndexOf('.');
        if (separator >= 0
                && separator + 1 < normalized.length()) {
            schemaCandidates.add(
                    normalized.substring(separator + 1));
        }
        Set<String> allowedSchemas =
                SqlParseUtil.splitCsv(
                        grant.getAllowedSchemas());
        for (String schema : schemaCandidates) {
            String qualified = schema + "." + safeTable;
            if (SqlParseUtil.matchAny(schema, allowedSchemas)
                    && tableAuthorized(grant, qualified)) {
                return new AuthorizedTable(
                        blankToNull(driverNamespace),
                        schema, safeTable);
            }
        }
        return null;
    }

    private AccessContext requireAccess(String grantedSourceName) {
        if (grantedSourceName == null
                || grantedSourceName.isBlank()) {
            throw new IllegalArgumentException(
                    "grantedSourceName 必填");
        }
        User user = securityUtil.requireCurrentUser();
        String workspace =
                securityUtil.requireCurrentWorkspace();
        Grant grant = grantService.resolveForUser(
                user.getId(), workspace,
                grantedSourceName.trim());
        DataSource dataSource =
                dataSourceService.getEntity(
                        grant.getDataSourceId());
        if (!workspace.equals(grant.getWorkspaceId())
                || !workspace.equals(
                dataSource.getWorkspaceId())) {
            throw new AccessDeniedException(
                    "逻辑数据源不属于当前工作空间");
        }
        return new AccessContext(user, grant, dataSource);
    }

    private static boolean mayTraverseRoot(
            String dbType, Grant grant, TreeNode node) {
        String type = upper(node.getType());
        if ("SCHEMA".equals(type)
                || "PROJECT".equals(type)) {
            return schemaAuthorized(
                    grant, "", node.getName());
        }
        if (!"DATABASE".equals(type)) {
            return false;
        }
        if ("MSSQL".equalsIgnoreCase(dbType)
                || "SQLSERVER".equalsIgnoreCase(dbType)) {
            return sqlServerDatabaseMayContainAuthorizedSchema(
                    grant, node.getName());
        }
        return schemaAuthorized(
                grant, node.getName(), "");
    }

    private static boolean sqlServerDatabaseMayContainAuthorizedSchema(
            Grant grant, String database) {
        for (String pattern :
                SqlParseUtil.splitCsv(
                        grant.getAllowedSchemas())) {
            String normalized =
                    normalizeNamespace(pattern);
            int separator = normalized.indexOf('.');
            if (separator < 0) {
                return true;
            }
            if (SqlParseUtil.matchAny(
                    database,
                    Set.of(normalized.substring(
                            0, separator)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean schemaAuthorized(
            Grant grant, String database, String schema) {
        Set<String> allowed =
                SqlParseUtil.splitCsv(
                        grant.getAllowedSchemas());
        if (allowed.isEmpty()) {
            return false;
        }
        LinkedHashSet<String> candidates =
                new LinkedHashSet<>();
        String normalizedSchema =
                normalizeNamespace(schema);
        String normalizedDatabase =
                normalizeNamespace(database);
        if (!normalizedSchema.isBlank()) {
            candidates.add(normalizedSchema);
            if (!normalizedDatabase.isBlank()) {
                candidates.add(normalizedDatabase
                        + "." + normalizedSchema);
            }
        } else if (!normalizedDatabase.isBlank()) {
            candidates.add(normalizedDatabase);
        }
        return candidates.stream().anyMatch(
                value -> SqlParseUtil.matchAny(
                        value, allowed));
    }

    private static boolean tableAuthorized(
            Grant grant, String qualified) {
        Set<String> allowed =
                SqlParseUtil.splitCsv(
                        grant.getAllowedTables());
        Set<String> blocked =
                SqlParseUtil.splitCsv(
                        grant.getBlockedTables());
        return SqlParseUtil.matchAny(qualified, allowed)
                && !SqlParseUtil.matchAny(
                qualified, blocked);
    }

    private static String firstConcreteSchema(
            Grant grant) {
        return SqlParseUtil.splitCsv(
                        grant.getAllowedSchemas())
                .stream()
                .filter(value -> !value.contains("*"))
                .findFirst().orElse("");
    }

    private static List<TreeNode> safeNodes(
            List<TreeNode> nodes) {
        return nodes == null ? List.of() : nodes;
    }

    private static List<ColumnMetadata> safeColumns(
            List<ColumnMetadata> columns) {
        return columns == null ? List.of() : columns;
    }

    private static boolean isTable(TreeNode node) {
        return node != null
                && TABLE_TYPES.contains(
                upper(node.getType()));
    }

    private static boolean isContainer(TreeNode node) {
        return node != null
                && CONTAINER_TYPES.contains(
                upper(node.getType()));
    }

    private static String containerPath(TreeNode node) {
        if (node == null) {
            return null;
        }
        String path = blankToNull(node.getPath());
        return path == null
                ? blankToNull(node.getName()) : path;
    }

    private static String tableOwnerPath(TreeNode node) {
        String path = node == null
                ? null : blankToNull(node.getPath());
        if (path == null) {
            return null;
        }
        int separator = path.lastIndexOf('/');
        if (separator <= 0) {
            return null;
        }
        String leaf = path.substring(separator + 1);
        return leaf.equalsIgnoreCase(node.getName())
                ? path.substring(0, separator) : null;
    }

    private static String normalizeNamespace(
            String value) {
        return value == null ? ""
                : value.trim()
                .replace('/', '.');
    }

    private static String leafName(String value) {
        if (value == null) {
            return "";
        }
        String normalized =
                normalizeNamespace(value);
        int separator =
                normalized.lastIndexOf('.');
        return separator < 0 ? normalized
                : normalized.substring(separator + 1);
    }

    private static String text(Object value) {
        return value == null ? null
                : value.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank()
                ? null : value.trim();
    }

    private static String upper(String value) {
        return value == null ? ""
                : value.toUpperCase(Locale.ROOT);
    }

    private record AccessContext(
            User user, Grant grant,
            DataSource dataSource) {
    }

    private record CachedCatalog(
            String fingerprint, long createdAt,
            EnterpriseMetadataCatalog catalog) {
    }

    private record AuthorizedTable(
            String driverNamespace,
            String schema, String table) {
    }
}
