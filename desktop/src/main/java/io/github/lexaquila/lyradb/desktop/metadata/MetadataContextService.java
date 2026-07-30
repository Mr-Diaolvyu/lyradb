package io.github.lexaquila.lyradb.desktop.metadata;

import io.github.lexaquila.lyradb.desktop.db.NativeConnectionManager;
import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;
import io.github.lexaquila.lyradb.metadata.snapshot.MetadataSnapshot;
import io.github.lexaquila.lyradb.metadata.snapshot.MetadataSnapshotRenderer;
import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.TreeNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * 从当前导航树选择采集只读结构元数据。
 *
 * <p>仅调用 tree/columns 元数据接口，不执行查询、不读取数据行。MongoDB 只
 * 采集数据库和集合名称；不会调用字段推断接口，因为字段推断依赖文档采样。</p>
 */
public final class MetadataContextService {

    private static final int MAX_TABLES = 500;
    private static final int MAX_COLUMNS = 20_000;

    private final NativeConnectionManager connectionManager;
    private final MetadataSnapshotRenderer renderer;

    public MetadataContextService(NativeConnectionManager connectionManager,
            MetadataSnapshotRenderer renderer) {
        this.connectionManager =
                java.util.Objects.requireNonNull(connectionManager);
        this.renderer = java.util.Objects.requireNonNull(renderer);
    }

    public MetadataCapture collect(MetadataSelection selection,
            BooleanSupplier cancelled) throws Exception {
        if (selection == null) {
            throw new IllegalArgumentException("请先在数据库导航器中选择数据库、Schema 或表");
        }
        rejectUnsupportedDrivers(selection.dbType());
        boolean includeColumns = !isMongo(selection.dbType());
        checkCancelled(cancelled);
        DesktopConnection connection =
                connectionManager.requireSaved(selection.connectionId());
        if (!connectionManager.isConnected(selection.connectionId())) {
            connectionManager.connect(selection.connectionId());
        }

        List<MetadataSnapshot.Schema> schemas;
        String databaseName;
        switch (selection.scope()) {
            case TABLE -> {
                MetadataTableLocation location =
                        MetadataTableLocation.resolve(selection.dbType(),
                                connection.getName(), selection.path());
                TreeNode selectedTable = TreeNode.of(
                        selection.path(), selection.name(),
                        selection.nodeType(), selection.path());
                selectedTable.setHasChildren(false);
                MetadataSnapshot.Table table = loadTable(
                        selection.connectionId(), selectedTable,
                        selection.dbType(), includeColumns, cancelled);
                schemas = List.of(new MetadataSnapshot.Schema(
                        location.schemaName(), "", List.of(table)));
                databaseName = location.databaseName();
            }
            case SCHEMA -> {
                List<MetadataSnapshot.Table> tables =
                        loadTables(selection.connectionId(),
                                selection.path(), selection.dbType(),
                                includeColumns,
                                cancelled);
                schemas = List.of(new MetadataSnapshot.Schema(
                        selection.name(), "", tables));
                databaseName = parentName(selection.path(), connection.getName());
            }
            case DATABASE -> {
                DatabaseContent content = loadDatabase(
                        selection.connectionId(), selection.path(),
                        selection.dbType(), includeColumns,
                        cancelled);
                schemas = content.schemas();
                databaseName = selection.name();
            }
            default -> throw new IllegalStateException("未知元数据范围");
        }

        MetadataSnapshot.Database database =
                new MetadataSnapshot.Database(databaseName, "", schemas);
        MetadataSnapshot.DataSource dataSource =
                new MetadataSnapshot.DataSource(
                        connection.getId(), connection.getName(),
                        connection.getDbType(), "", List.of(database));
        MetadataSnapshot snapshot = MetadataSnapshot.of(List.of(dataSource));
        int tableCount = schemas.stream().mapToInt(schema ->
                schema.tables().size()).sum();
        int columnCount = schemas.stream()
                .flatMap(schema -> schema.tables().stream())
                .mapToInt(table -> table.columns().size())
                .sum();
        if (columnCount > MAX_COLUMNS) {
            throw new IllegalArgumentException(
                    "当前范围包含超过 " + MAX_COLUMNS
                            + " 个列定义，请选择更小的范围");
        }
        long tokens = renderer.estimateMarkdownTokens(snapshot);
        return new MetadataCapture(selection, snapshot, Instant.now(),
                tableCount, columnCount, tokens);
    }

    private DatabaseContent loadDatabase(String connectionId,
            String databasePath, String dbType,
            boolean includeColumns,
            BooleanSupplier cancelled) throws Exception {
        List<TreeNode> children = connectionManager.tree(connectionId, databasePath);
        List<MetadataSnapshot.Schema> schemas = new ArrayList<>();
        List<MetadataSnapshot.Table> directTables = new ArrayList<>();
        for (TreeNode child : children) {
            checkCancelled(cancelled);
            if (isTable(child)) {
                directTables.add(loadTable(
                        connectionId, child, dbType,
                        includeColumns, cancelled));
            } else if ("SCHEMA".equals(normalizeType(child.getType()))) {
                schemas.add(new MetadataSnapshot.Schema(
                        child.getName(), remarks(child),
                        loadTables(connectionId, child.getPath(),
                                dbType, includeColumns, cancelled)));
            }
            ensureTableLimit(countTables(schemas) + directTables.size());
        }
        if (!directTables.isEmpty() || schemas.isEmpty()) {
            schemas.add(0, new MetadataSnapshot.Schema("", "", directTables));
        }
        return new DatabaseContent(List.copyOf(schemas));
    }

    private List<MetadataSnapshot.Table> loadTables(String connectionId,
            String parentPath, String dbType,
            boolean includeColumns,
            BooleanSupplier cancelled) throws Exception {
        List<TreeNode> children = connectionManager.tree(connectionId, parentPath);
        List<MetadataSnapshot.Table> result = new ArrayList<>();
        for (TreeNode child : children) {
            checkCancelled(cancelled);
            if (isTable(child)) {
                result.add(loadTable(
                        connectionId, child, dbType,
                        includeColumns, cancelled));
                ensureTableLimit(result.size());
            }
        }
        return List.copyOf(result);
    }

    private MetadataSnapshot.Table loadTable(String connectionId,
            TreeNode table, String dbType,
            boolean includeColumns,
            BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        String[] parts = splitPath(table.getPath());
        String tableName = parts.length == 0
                ? table.getName() : parts[parts.length - 1];
        if (!includeColumns) {
            return MetadataSnapshot.Table.fromTreeNode(table, List.of());
        }
        String namespace = MetadataTableLocation.resolve(
                dbType, "", table.getPath()).metadataNamespace();
        List<ColumnMetadata> columns = connectionManager.columns(
                connectionId, emptyAsNull(namespace), tableName);
        if (columns.size() > MetadataSnapshot.MAX_COLUMNS_PER_TABLE) {
            throw new IllegalArgumentException(
                    "表“" + tableName + "”的列数量超过快照上限");
        }
        return MetadataSnapshot.Table.fromTreeNode(table, columns);
    }

    private static int countTables(List<MetadataSnapshot.Schema> schemas) {
        return schemas.stream().mapToInt(schema -> schema.tables().size()).sum();
    }

    private static void ensureTableLimit(int count) {
        if (count > MAX_TABLES) {
            throw new IllegalArgumentException(
                    "当前范围包含超过 " + MAX_TABLES
                            + " 个表，请选择更小的 Schema 或单表");
        }
    }

    private static boolean isTable(TreeNode node) {
        String type = normalizeType(node == null ? null : node.getType());
        return "TABLE".equals(type) || "VIEW".equals(type)
                || "COLLECTION".equals(type);
    }

    private static void rejectUnsupportedDrivers(String dbType) {
        String type = normalizeType(dbType);
        if ("REDIS".equals(type)) {
            throw new IllegalArgumentException(
                    "Redis 不提供可采集的表/列结构元数据");
        }
    }

    private static boolean isMongo(String dbType) {
        return "MONGODB".equals(normalizeType(dbType));
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted()
                || cancelled != null && cancelled.getAsBoolean()) {
            throw new CancellationException("元数据采集已取消");
        }
    }

    private static String remarks(TreeNode node) {
        if (node == null || node.getProperties() == null) {
            return "";
        }
        for (String key : List.of("remarks", "comment", "description")) {
            Object value = node.getProperties().get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private static String[] splitPath(String path) {
        return path == null || path.isBlank() ? new String[0] : path.split("/");
    }

    private static String parentName(String path, String fallback) {
        String[] parts = splitPath(path);
        return parts.length >= 2 ? parts[0] : fallback;
    }

    private static String emptyAsNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record DatabaseContent(List<MetadataSnapshot.Schema> schemas) {
    }
}
