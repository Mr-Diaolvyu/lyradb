package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.EnterprisePartitionPageView;
import io.github.lexaquila.lyradb.model.dto.PartitionMetadata;
import io.github.lexaquila.lyradb.model.dto.PartitionMetadataPage;
import io.github.lexaquila.lyradb.model.dto.TableCommentMetadata;
import io.github.lexaquila.lyradb.model.dto.TableInspection;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 元数据服务
 *
 * <p>
 * 提供数据库导航树、表结构元数据、DDL查询等功能。
 * 所有操作基于活跃的数据库连接，通过DatabaseDriver接口统一适配9种数据库。
 * </p>
 *
 * <p>
 * 导航树按数据库类型返回不同层级：
 * RDBMS→数据库→Schema→表/视图，MaxCompute→表→分区，
 * MongoDB→Database→Collection，Redis→DB索引→Key前缀→Key
 * </p>
 */
@Service
public class MetadataService {

    private static final Logger log = LoggerFactory.getLogger(MetadataService.class);
    private static final int MAX_PARTITION_PAGE_SIZE = 100;
    private static final int MAX_FILTERED_PARTITION_SCAN = 10_000;

    private final ConnectionService connectionService;

    public MetadataService(ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    /**
     * 获取导航树节点
     *
     * @param connectionId 连接ID
     * @param parentPath   父节点路径（null=顶层）
     * @return 子节点列表
     */
    public List<TreeNode> getTreeNodes(String connectionId, String parentPath) throws Exception {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        log.debug("获取树节点: connectionId={}, parentPath={}", connectionId, parentPath);
        try (ConnectionService.ActiveConnection.Lease ignored = active.acquire()) {
            return active.driver.getTreeNodes(active.connection, parentPath);
        }
    }

    /**
     * 获取表列元数据
     *
     * @param connectionId 连接ID
     * @param schemaName   Schema名
     * @param tableName    表名
     * @return 列信息列表
     */
    public List<ColumnMetadata> getTableColumns(String connectionId, String schemaName, String tableName)
            throws Exception {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        log.debug("获取表列元数据: connectionId={}, schema={}, table={}", connectionId, schemaName, tableName);
        try (ConnectionService.ActiveConnection.Lease ignored = active.acquire()) {
            return active.driver.getTableColumns(active.connection, schemaName, tableName);
        }
    }

    /**
     * 获取表DDL
     *
     * @param connectionId 连接ID
     * @param schemaName   Schema名
     * @param tableName    表名
     * @return DDL语句
     */
    public String getTableDDL(String connectionId, String schemaName, String tableName) throws Exception {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        log.debug("获取表DDL: connectionId={}, schema={}, table={}", connectionId, schemaName, tableName);
        try (ConnectionService.ActiveConnection.Lease ignored = active.acquire()) {
            return active.driver.getTableDDL(active.connection, schemaName, tableName);
        }
    }

    /**
     * 一次加载表工作台所需的数据预览、字段、索引约束和 DDL。
     *
     * <p>单个区域失败不会阻断其他区域，前端可在对应页签展示错误。</p>
     */
    public TableInspection inspectTable(
            String connectionId,
            String schemaName,
            String tableName,
            String objectType,
            int requestedLimit) throws Exception {
        return inspectTable(connectionId, schemaName, tableName,
                objectType, requestedLimit, true, null);
    }

    public TableInspection inspectTable(
            String connectionId,
            String schemaName,
            String tableName,
            String objectType,
            int requestedLimit,
            boolean includePreview,
            String partitionSpec) throws Exception {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("table 不能为空");
        }
        int limit = Math.max(1, Math.min(200, requestedLimit));
        ConnectionService.ActiveConnection active =
                connectionService.getActiveConnection(connectionId);
        TableInspection inspection = new TableInspection();
        inspection.setSchema(schemaName);
        inspection.setTable(tableName);
        inspection.setObjectType(
                objectType == null || objectType.isBlank()
                        ? "TABLE" : objectType.toUpperCase());
        String dbType = active.driver.getDriverInfo() == null
                ? "" : active.driver.getDriverInfo().getDbType();
        inspection.setDbType(dbType);
        boolean maxCompute = "MAXCOMPUTE".equalsIgnoreCase(dbType);
        boolean partitionMetadataFailed = false;

        log.debug("加载表工作台: connectionId={}, schema={}, table={}, limit={}",
                connectionId, schemaName, tableName, limit);
        try (ConnectionService.ActiveConnection.Lease ignored =
                     active.acquire()) {
            try {
                TableCommentMetadata comment =
                        active.driver.getTableCommentMetadata(
                                active.connection, schemaName, tableName);
                applyTableComment(inspection, comment);
            } catch (Exception exception) {
                inspection.setMetadataStatus("ERROR");
                inspection.setRemarksStatus("UNAVAILABLE");
                inspection.setMetadataReason(
                        "表注释读取失败: " + safeMessage(exception));
                inspection.addError("remarks", safeMessage(exception));
            }
            if (maxCompute) {
                try {
                    PartitionMetadataPage page =
                            active.driver.listTablePartitions(
                                    active.connection, schemaName,
                                    tableName, 0, 1);
                    inspection.setPartitioned(page.isPartitioned());
                    inspection.setPartitionColumns(page.getPartitionKeys());
                    if (!page.isPartitioned()
                            && !"COMPLETE".equals(page.getMetadataStatus())) {
                        partitionMetadataFailed = true;
                        inspection.addError("partitions",
                                page.getMetadataReason());
                    }
                } catch (Exception exception) {
                    partitionMetadataFailed = true;
                    inspection.addError(
                            "partitions", safeMessage(exception));
                }
            }
            try {
                inspection.setColumns(active.driver.getTableColumns(
                        active.connection, schemaName, tableName));
            } catch (Exception exception) {
                inspection.addError("columns", safeMessage(exception));
            }
            try {
                inspection.setConstraints(active.driver.getTableConstraints(
                        active.connection, schemaName, tableName));
            } catch (Exception exception) {
                inspection.addError("constraints", safeMessage(exception));
            }
            try {
                inspection.setDdl(active.driver.getTableDDL(
                        active.connection, schemaName, tableName));
            } catch (Exception exception) {
                inspection.addError("ddl", safeMessage(exception));
            }
            inspection.setPreviewRequiresPartition(maxCompute
                    && (inspection.isPartitioned()
                    || partitionMetadataFailed));
            if (includePreview) {
                if (maxCompute && partitionMetadataFailed) {
                    inspection.addError("preview",
                            "无法确认 MaxCompute 分区状态，为避免全表扫描已阻止数据预览");
                } else if (inspection.isPartitioned()
                        && (partitionSpec == null || partitionSpec.isBlank())) {
                    inspection.addError("preview",
                            "必须先选择一个完整分区，已阻止无分区预览");
                } else {
                    try {
                        String previewSql;
                        if (inspection.isPartitioned()) {
                            previewSql = active.driver.buildPartitionPreviewSql(
                                    active.connection, schemaName, tableName,
                                    partitionSpec.trim(), limit);
                            inspection.setSelectedPartition(
                                    partitionSpec.trim());
                        } else {
                            if (partitionSpec != null
                                    && !partitionSpec.isBlank()) {
                                throw new IllegalArgumentException(
                                        "非分区表不能指定 partitionSpec");
                            }
                            previewSql = active.driver.buildTablePreviewSql(
                                    active.connection, schemaName,
                                    tableName, limit);
                        }
                        inspection.setPreviewSql(previewSql);
                        inspection.setPreview(active.driver.executeQuery(
                                active.connection, previewSql, limit));
                    } catch (Exception exception) {
                        inspection.addError(
                                "preview", safeMessage(exception));
                    }
                }
            }
        }
        return inspection;
    }

    public EnterprisePartitionPageView listTablePartitions(
            String connectionId,
            String schemaName,
            String tableName,
            int requestedOffset,
            int requestedLimit,
            String requestedFilter) throws Exception {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("table 不能为空");
        }
        int offset = Math.max(0, requestedOffset);
        int limit = Math.max(1,
                Math.min(MAX_PARTITION_PAGE_SIZE, requestedLimit));
        String filter = requestedFilter == null
                ? "" : requestedFilter.trim();
        if (filter.length() > 200) {
            throw new IllegalArgumentException(
                    "分区筛选内容不能超过 200 个字符");
        }
        ConnectionService.ActiveConnection active =
                connectionService.getActiveConnection(connectionId);
        String dbType = active.driver.getDriverInfo() == null
                ? "" : active.driver.getDriverInfo().getDbType();
        if (!"MAXCOMPUTE".equalsIgnoreCase(dbType)) {
            throw new IllegalArgumentException(
                    "分区浏览当前仅适用于 MaxCompute 数据源");
        }
        try (ConnectionService.ActiveConnection.Lease ignored =
                     active.acquire()) {
            if (filter.isBlank()) {
                PartitionMetadataPage page =
                        active.driver.listTablePartitions(
                                active.connection, schemaName, tableName,
                                offset, limit);
                List<String> items = partitionSpecs(page.getItems());
                String suggested = page.getOffset() == 0
                        && "PARTITION_SPEC_DESC".equals(page.getOrdering())
                        && !items.isEmpty() ? items.get(0) : null;
                return partitionView(page, items, false,
                        suggested, page.getOrdering(), filter);
            }
            return filteredPartitionPage(active, schemaName,
                    tableName, offset, limit, filter);
        }
    }

    private EnterprisePartitionPageView filteredPartitionPage(
            ConnectionService.ActiveConnection active,
            String schemaName,
            String tableName,
            int offset,
            int limit,
            String filter) throws Exception {
        String keyword = filter.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        PartitionMetadataPage evidence = null;
        int cursor = 0;
        int scanned = 0;
        boolean upstreamHasMore = true;
        while (upstreamHasMore
                && scanned < MAX_FILTERED_PARTITION_SCAN
                && matches.size() <= offset + limit) {
            int chunkSize = Math.min(200,
                    MAX_FILTERED_PARTITION_SCAN - scanned);
            PartitionMetadataPage page =
                    active.driver.listTablePartitions(
                            active.connection, schemaName, tableName,
                            cursor, chunkSize);
            if (evidence == null) evidence = page;
            List<PartitionMetadata> items = page.getItems() == null
                    ? List.of() : page.getItems();
            for (PartitionMetadata item : items) {
                String specification = item == null ? "" : item.getSpec();
                if (specification != null
                        && specification.toLowerCase(Locale.ROOT)
                        .contains(keyword)) {
                    matches.add(specification);
                }
            }
            int read = items.size();
            scanned += read;
            cursor += read;
            upstreamHasMore = page.isHasMore();
            if (read == 0) break;
        }
        if (evidence == null) {
            evidence = new PartitionMetadataPage();
            evidence.setSchema(schemaName);
            evidence.setTable(tableName);
        }
        int from = Math.min(offset, matches.size());
        int to = Math.min(matches.size(), from + limit);
        List<String> visible = List.copyOf(matches.subList(from, to));
        boolean truncated = upstreamHasMore
                && scanned >= MAX_FILTERED_PARTITION_SCAN;
        boolean hasMore = matches.size() > to;
        String suggested = offset == 0
                && "PARTITION_SPEC_DESC".equals(evidence.getOrdering())
                && !visible.isEmpty() ? visible.get(0) : null;
        return new EnterprisePartitionPageView(
                evidence.getPartitionKeys(), visible,
                offset, limit, hasMore, truncated,
                suggested, evidence.getOrdering(), filter,
                evidence.getMetadataSource(),
                evidence.getMetadataStatus(),
                truncated
                        ? "筛选最多扫描前 "
                        + MAX_FILTERED_PARTITION_SCAN
                        + " 个分区，结果可能不完整"
                        : evidence.getMetadataReason());
    }

    private static EnterprisePartitionPageView partitionView(
            PartitionMetadataPage page,
            List<String> items,
            boolean truncated,
            String suggested,
            String ordering,
            String filter) {
        return new EnterprisePartitionPageView(
                page.getPartitionKeys(), items,
                page.getOffset(), page.getLimit(),
                page.isHasMore(), truncated,
                suggested, ordering, filter,
                page.getMetadataSource(),
                page.getMetadataStatus(),
                page.getMetadataReason());
    }

    private static List<String> partitionSpecs(
            List<PartitionMetadata> items) {
        if (items == null) return List.of();
        return items.stream()
                .filter(Objects::nonNull)
                .map(PartitionMetadata::getSpec)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private static void applyTableComment(
            TableInspection inspection,
            TableCommentMetadata comment) {
        if (comment == null) {
            inspection.setMetadataStatus("PARTIAL");
            inspection.setRemarksStatus("UNAVAILABLE");
            inspection.setMetadataReason("驱动未返回表注释元数据");
            return;
        }
        inspection.setTableComment(comment.getRemarks());
        inspection.setMetadataSource(comment.getMetadataSource());
        inspection.setMetadataStatus(comment.getMetadataStatus());
        inspection.setMetadataReason(comment.getMetadataReason());
        inspection.setRemarksStatus(comment.getRemarksStatus());
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 600 ? message : message.substring(0, 600);
    }

    /**
     * 获取数据库列表
     *
     * <p>
     * 返回当前连接下可用的数据库（Catalog）名称列表。
     * 通过导航树根节点的 DATABASE 类型节点提取。
     * </p>
     *
     * @param connectionId 连接ID
     * @return 数据库名称列表
     */
    public List<String> getDatabases(String connectionId) throws Exception {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        log.debug("获取数据库列表: connectionId={}", connectionId);
        try (ConnectionService.ActiveConnection.Lease ignored = active.acquire()) {
            List<TreeNode> rootNodes = active.driver.getTreeNodes(active.connection, null);
            return rootNodes.stream()
                    .filter(n -> "DATABASE".equals(n.getType()))
                    .map(TreeNode::getName)
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    /**
     * 搜索导航树节点
     *
     * <p>JDBC 驱动直接使用 DatabaseMetaData 搜索未展开的数据库、Schema、
     * 表和视图；不再递归扫描整棵导航树。</p>
     *
     * @param connectionId 连接ID
     * @param keyword      搜索关键字
     * @param type         可选节点类型过滤
     * @return 匹配的节点列表
     */
    public List<TreeNode> searchNodes(String connectionId, String keyword, String type) throws Exception {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        log.debug("搜索节点: connectionId={}, keyword={}, type={}", connectionId, keyword, type);

        try (ConnectionService.ActiveConnection.Lease ignored = active.acquire()) {
            if (keyword == null || keyword.isBlank()) {
                return List.of();
            }
            List<TreeNode> results = active.driver.searchTreeNodes(
                    active.connection, keyword, 100);
            if (type == null || type.isBlank()) {
                return results;
            }
            return results.stream()
                    .filter(node -> type.equalsIgnoreCase(node.getType()))
                    .toList();
        }
    }

    /**
     * 获取驱动能力声明
     */
    public io.github.lexaquila.lyradb.model.entity.DriverCapability getCapabilities(String connectionId) {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        return active.driver.getCapabilities();
    }

    /** 单次 ER 图最多加载的表数量，防止超大库拖垮响应 */
    private static final int MAX_ER_TABLES = 300;

    /**
     * 获取 ER 图数据（表节点 + 基于外键的关系边）
     *
     * <p>
     * 仅 JDBC 类型数据库有效（NoSQL 无外键概念，返回空边）。
     * </p>
     *
     * @param connectionId 连接ID
     * @param schema       可选，限定 schema/database；缺省时回退到连接当前所在库
     */
    public io.github.lexaquila.lyradb.model.dto.ErDiagram getErDiagram(String connectionId, String schema) {
        io.github.lexaquila.lyradb.model.dto.ErDiagram er = new io.github.lexaquila.lyradb.model.dto.ErDiagram();
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        try (ConnectionService.ActiveConnection.Lease ignored = active.acquire()) {
        Object conn = active.connection;

        if (!(conn instanceof java.sql.Connection jdbcConn)) {
            // NoSQL：ER 不适用
            return er;
        }

        try {
            java.sql.DatabaseMetaData md = jdbcConn.getMetaData();
            // MySQL/MariaDB 以 catalog 表示数据库（schemaPattern 被驱动忽略），其余库以 schema 表示；
            // 未指定时回退到连接当前库，避免 null 触发全库扫描导致前端 60s 超时
            String product = md.getDatabaseProductName() == null ? "" : md.getDatabaseProductName().toLowerCase();
            boolean useCatalog = product.contains("mysql") || product.contains("mariadb");
            String scope = (schema != null && !schema.isBlank()) ? schema : currentScope(jdbcConn, useCatalog);
            String catalog = useCatalog ? scope : null;
            String schemaPattern = useCatalog ? null : scope;

            // 表节点（封顶 MAX_ER_TABLES，防止超大库响应超时）
            java.util.Map<String, io.github.lexaquila.lyradb.model.dto.ErDiagram.Table> byName = new java.util.LinkedHashMap<>();
            try (java.sql.ResultSet rs = md.getTables(catalog, schemaPattern, "%",
                    new String[] { "TABLE", "VIEW" })) {
                while (rs.next() && byName.size() < MAX_ER_TABLES) {
                    String tName = rs.getString("TABLE_NAME");
                    String tSchema = rs.getString("TABLE_SCHEM");
                    io.github.lexaquila.lyradb.model.dto.ErDiagram.Table t = new io.github.lexaquila.lyradb.model.dto.ErDiagram.Table(
                            tName, tSchema);
                    byName.put(tName, t);
                    er.getTables().add(t);
                }
            }

            // 一次性批量取列并按表名分组，避免逐表 N+1 元数据往返
            try (java.sql.ResultSet cols = md.getColumns(catalog, schemaPattern, "%", "%")) {
                while (cols.next()) {
                    io.github.lexaquila.lyradb.model.dto.ErDiagram.Table t = byName.get(cols.getString("TABLE_NAME"));
                    if (t != null) {
                        t.getColumns().add(cols.getString("COLUMN_NAME"));
                    }
                }
            }

            // 外键边（JDBC 无批量接口，只能逐表查询；表数已封顶可控）
            for (io.github.lexaquila.lyradb.model.dto.ErDiagram.Table t : er.getTables()) {
                try (java.sql.ResultSet fks = md.getImportedKeys(catalog, schemaPattern, t.getName())) {
                    while (fks.next()) {
                        String source = t.getName();
                        String sourceCol = fks.getString("FKCOLUMN_NAME");
                        String target = fks.getString("PKTABLE_NAME");
                        String targetCol = fks.getString("PKCOLUMN_NAME");
                        if (source != null && target != null) {
                            er.getEdges().add(new io.github.lexaquila.lyradb.model.dto.ErDiagram.Edge(
                                    source, target, sourceCol, targetCol));
                        }
                    }
                } catch (Exception e) {
                    // 部分库不支持 getImportedKeys，忽略单表失败
                }
            }
        } catch (java.sql.SQLException e) {
            log.warn("获取 ER 图失败: {}", e.getMessage());
        }

        return er;
        }
    }

    /**
     * 取连接当前所在库（MySQL 系用 catalog，其余用 schema），获取失败返回 null（退化为全量）。
     */
    private String currentScope(java.sql.Connection c, boolean useCatalog) {
        try {
            String v = useCatalog ? c.getCatalog() : c.getSchema();
            return (v == null || v.isBlank()) ? null : v;
        } catch (Exception e) {
            return null;
        }
    }
}
