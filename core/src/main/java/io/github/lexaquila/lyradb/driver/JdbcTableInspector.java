package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.dto.TableConstraintMetadata;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JDBC 表工作台的安全 SQL 与结构元数据适配。
 */
final class JdbcTableInspector {

    static final int MAX_PREVIEW_ROWS = 1_000;

    private JdbcTableInspector() {
    }

    static String previewSql(
            Connection connection,
            String dbType,
            String namespace,
            String table,
            int requestedLimit) throws Exception {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        int limit = Math.max(1, Math.min(requestedLimit, MAX_PREVIEW_ROWS));
        String quote = AbstractJdbcDriver.identifierQuote(
                connection.getMetaData());
        String tableRef = qualifiedIdentifier(namespace, table, quote);
        String normalizedType = dbType == null
                ? "" : dbType.trim().toUpperCase(Locale.ROOT);
        return switch (normalizedType) {
            case "MSSQL" ->
                    "SELECT TOP (" + limit + ") * FROM " + tableRef;
            case "ORACLE" ->
                    "SELECT * FROM " + tableRef + " WHERE ROWNUM <= " + limit;
            default ->
                    "SELECT * FROM " + tableRef + " LIMIT " + limit;
        };
    }

    static List<TableConstraintMetadata> constraints(
            Connection connection,
            String catalog,
            String schema,
            String table) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        Map<String, MutableConstraint> constraints = new LinkedHashMap<>();

        try (ResultSet keys = metadata.getPrimaryKeys(catalog, schema, table)) {
            while (keys.next()) {
                String name = fallbackName(
                        keys.getString("PK_NAME"), "PRIMARY");
                MutableConstraint constraint = constraints.computeIfAbsent(
                        "PRIMARY_KEY:" + name,
                        ignored -> new MutableConstraint(
                                name, "PRIMARY_KEY", null));
                constraint.addColumn(
                        keys.getShort("KEY_SEQ"),
                        keys.getString("COLUMN_NAME"));
            }
        }

        try (ResultSet indexes = metadata.getIndexInfo(
                catalog, schema, table, false, true)) {
            while (indexes.next()) {
                if (indexes.getShort("TYPE")
                        == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                String name = indexes.getString("INDEX_NAME");
                String column = indexes.getString("COLUMN_NAME");
                if (name == null || name.isBlank()
                        || column == null || column.isBlank()) {
                    continue;
                }
                boolean unique = !indexes.getBoolean("NON_UNIQUE");
                String type = unique ? "UNIQUE_INDEX" : "INDEX";
                MutableConstraint constraint = constraints.computeIfAbsent(
                        type + ":" + name,
                        ignored -> new MutableConstraint(name, type, null));
                constraint.addColumn(
                        indexes.getShort("ORDINAL_POSITION"), column);
            }
        }

        try (ResultSet foreignKeys = metadata.getImportedKeys(
                catalog, schema, table)) {
            while (foreignKeys.next()) {
                String name = fallbackName(
                        foreignKeys.getString("FK_NAME"), "FOREIGN_KEY");
                String reference = qualifiedReference(
                        foreignKeys.getString("PKTABLE_CAT"),
                        foreignKeys.getString("PKTABLE_SCHEM"),
                        foreignKeys.getString("PKTABLE_NAME"));
                MutableConstraint constraint = constraints.computeIfAbsent(
                        "FOREIGN_KEY:" + name,
                        ignored -> new MutableConstraint(
                                name, "FOREIGN_KEY", reference));
                short sequence = foreignKeys.getShort("KEY_SEQ");
                constraint.addColumn(
                        sequence, foreignKeys.getString("FKCOLUMN_NAME"));
                constraint.addReferencedColumn(
                        sequence, foreignKeys.getString("PKCOLUMN_NAME"));
            }
        }

        Set<String> primaryColumns = constraints.values().stream()
                .filter(value -> "PRIMARY_KEY".equals(value.type))
                .flatMap(value -> value.columns.values().stream())
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));

        return constraints.values().stream()
                .filter(value -> !isDuplicatePrimaryIndex(
                        value, primaryColumns))
                .sorted(Comparator.comparingInt(
                                (MutableConstraint value) ->
                                        typeRank(value.type))
                        .thenComparing(value -> value.name,
                                String.CASE_INSENSITIVE_ORDER))
                .map(MutableConstraint::toMetadata)
                .toList();
    }

    private static boolean isDuplicatePrimaryIndex(
            MutableConstraint value, Set<String> primaryColumns) {
        return "UNIQUE_INDEX".equals(value.type)
                && !primaryColumns.isEmpty()
                && new LinkedHashSet<>(value.columns.values())
                        .equals(primaryColumns);
    }

    private static int typeRank(String type) {
        return switch (type) {
            case "PRIMARY_KEY" -> 0;
            case "FOREIGN_KEY" -> 1;
            case "UNIQUE_INDEX" -> 2;
            default -> 3;
        };
    }

    private static String fallbackName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String qualifiedReference(
            String catalog, String schema, String table) {
        List<String> parts = new ArrayList<>();
        if (catalog != null && !catalog.isBlank()) {
            parts.add(catalog);
        }
        if (schema != null && !schema.isBlank()
                && (parts.isEmpty()
                || !schema.equalsIgnoreCase(parts.get(parts.size() - 1)))) {
            parts.add(schema);
        }
        if (table != null && !table.isBlank()) {
            parts.add(table);
        }
        return String.join(".", parts);
    }

    private static String qualifiedIdentifier(
            String namespace, String table, String quote) {
        List<String> parts = new ArrayList<>();
        if (namespace != null && !namespace.isBlank()) {
            for (String part : namespace.split("/")) {
                if (!part.isBlank()) {
                    parts.add(AbstractJdbcDriver.quoteIdentifier(part, quote));
                }
            }
        }
        parts.add(AbstractJdbcDriver.quoteIdentifier(table, quote));
        return String.join(".", parts);
    }

    private static final class MutableConstraint {
        private final String name;
        private final String type;
        private final String referencedTable;
        private final Map<Integer, String> columns = new LinkedHashMap<>();
        private final Map<Integer, String> referencedColumns =
                new LinkedHashMap<>();

        private MutableConstraint(
                String name, String type, String referencedTable) {
            this.name = name;
            this.type = type;
            this.referencedTable = referencedTable;
        }

        private void addColumn(int position, String column) {
            if (column != null && !column.isBlank()) {
                columns.putIfAbsent(Math.max(1, position), column);
            }
        }

        private void addReferencedColumn(int position, String column) {
            if (column != null && !column.isBlank()) {
                referencedColumns.putIfAbsent(
                        Math.max(1, position), column);
            }
        }

        private TableConstraintMetadata toMetadata() {
            TableConstraintMetadata result = new TableConstraintMetadata();
            result.setName(name);
            result.setType(type);
            result.setColumns(columns.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .toList());
            result.setReferencedTable(referencedTable);
            result.setReferencedColumns(
                    referencedColumns.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(Map.Entry::getValue)
                            .toList());
            return result;
        }
    }
}
