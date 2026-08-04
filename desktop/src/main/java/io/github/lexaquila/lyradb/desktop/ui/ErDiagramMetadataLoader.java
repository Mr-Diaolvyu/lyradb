package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.TableConstraintMetadata;
import io.github.lexaquila.lyradb.model.dto.TreeNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 将统一驱动元数据转换为桌面 ER 图模型。
 *
 * <p>这里不直接调用 JDBC DatabaseMetaData。Catalog、Schema、Project 的差异
 * 由驱动层统一解析，避免 MySQL 数据库名被误当成 Schema。普通数据库可加载
 * 所选范围内的完整表清单；MaxCompute 可加载用户选择的血缘根表。两种模式
 * 都只根据真实元数据生成连线。</p>
 */
final class ErDiagramMetadataLoader {

    static final int MAX_SELECTED_TABLES = 24;
    static final int MAX_SCOPE_TABLES = 2_000;
    private static final int MAX_VISIBLE_COLUMNS = 14;

    private ErDiagramMetadataLoader() {
    }

    static ErDiagramDialog.SchemaGraph skeleton(
            List<TableChoice> choices) {
        List<ErDiagramDialog.TableNode> tables = choices == null
                ? List.of()
                : choices.stream()
                        .distinct()
                        .limit(MAX_SCOPE_TABLES)
                        .map(choice -> new ErDiagramDialog.TableNode(
                                choice.displayNamespace(), choice.name(),
                                List.of()))
                        .toList();
        return new ErDiagramDialog.SchemaGraph(
                tables, List.of(),
                choices != null && choices.size() > MAX_SCOPE_TABLES);
    }

    static ErDiagramDialog.SchemaGraph load(
            MetadataSource source,
            List<TableChoice> selected,
            Map<String, TableMetadata> cache) throws Exception {
        List<TableChoice> safeSelection = selected == null
                ? List.of()
                : selected.stream()
                        .distinct()
                        .limit(MAX_SCOPE_TABLES)
                        .toList();
        if (safeSelection.isEmpty()) {
            return new ErDiagramDialog.SchemaGraph(
                    List.of(), List.of(), false);
        }

        Map<String, TableChoice> choicesByKey = new LinkedHashMap<>();
        Map<String, TableMetadata> metadataByKey = new LinkedHashMap<>();
        List<ErDiagramDialog.TableNode> tables = new ArrayList<>();
        for (TableChoice choice : safeSelection) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("ER 元数据加载已取消");
            }
            TableMetadata metadata = cache.get(choice.key());
            if (metadata == null) {
                metadata = new TableMetadata(
                        source.columns(choice.namespace(), choice.name()),
                        source.constraints(choice.namespace(), choice.name()));
                cache.put(choice.key(), metadata);
            }
            choicesByKey.put(choice.key(), choice);
            metadataByKey.put(choice.key(), metadata);

            Set<String> primaryKeys = primaryKeys(metadata);
            List<ErDiagramDialog.ColumnNode> columns = metadata.columns().stream()
                    .limit(MAX_VISIBLE_COLUMNS)
                    .map(column -> new ErDiagramDialog.ColumnNode(
                            column.getName(),
                            column.getTypeName(),
                            column.isPrimaryKey()
                                    || primaryKeys.contains(normalize(column.getName())),
                            column.getRemarks()))
                    .toList();
            tables.add(new ErDiagramDialog.TableNode(
                    choice.displayNamespace(), choice.name(), columns));
        }

        List<ErDiagramDialog.Relation> relations = new ArrayList<>();
        Set<String> relationKeys = new LinkedHashSet<>();
        for (Map.Entry<String, TableMetadata> entry : metadataByKey.entrySet()) {
            TableChoice fromChoice = choicesByKey.get(entry.getKey());
            for (TableConstraintMetadata constraint : entry.getValue().constraints()) {
                if (!"FOREIGN_KEY".equalsIgnoreCase(constraint.getType())
                        || constraint.getReferencedTable() == null
                        || constraint.getReferencedTable().isBlank()) {
                    continue;
                }
                TableChoice toChoice = resolveReferencedChoice(
                        safeSelection, fromChoice,
                        constraint.getReferencedTable());
                if (toChoice == null) {
                    continue;
                }
                List<String> fromColumns = constraint.getColumns();
                List<String> toColumns = constraint.getReferencedColumns();
                int pairs = Math.max(1,
                        Math.min(fromColumns.size(), toColumns.size()));
                for (int index = 0; index < pairs; index++) {
                    String fromColumn = valueAt(fromColumns, index);
                    String toColumn = valueAt(toColumns, index);
                    String relationKey = fromChoice.key() + "\u0000"
                            + toChoice.key() + "\u0000" + fromColumn
                            + "\u0000" + toColumn;
                    if (relationKeys.add(relationKey)) {
                        relations.add(new ErDiagramDialog.Relation(
                                graphKey(fromChoice), graphKey(toChoice),
                                fromColumn, toColumn));
                    }
                }
            }
        }
        return new ErDiagramDialog.SchemaGraph(
                List.copyOf(tables), List.copyOf(relations),
                selected != null && selected.size() > MAX_SCOPE_TABLES);
    }

    static TableChoice fromNode(
            TreeNode node,
            String queryNamespace,
            String displayScope,
            String dbType) {
        String type = node.getType() == null
                ? "TABLE" : node.getType().toUpperCase(Locale.ROOT);
        String catalog = property(node, "catalog");
        String schema = property(node, "schema");
        String normalizedType = dbType == null
                ? "" : dbType.toUpperCase(Locale.ROOT);

        String namespace;
        String displayNamespace;
        if ("MAXCOMPUTE".equals(normalizedType)) {
            namespace = null;
            displayNamespace = blankToNull(displayScope);
        } else if ("MSSQL".equals(normalizedType)) {
            namespace = join(catalog, schema);
            if (namespace == null) {
                namespace = blankToNull(queryNamespace);
            }
            displayNamespace = namespace;
        } else if ("MYSQL".equals(normalizedType)) {
            namespace = firstNonBlank(catalog, queryNamespace);
            displayNamespace = namespace;
        } else {
            namespace = firstNonBlank(schema, queryNamespace);
            displayNamespace = namespace;
        }
        return new TableChoice(
                node.getName(), namespace, displayNamespace,
                type, node.getPath());
    }

    private static Set<String> primaryKeys(TableMetadata metadata) {
        Set<String> primaryKeys = new HashSet<>();
        for (TableConstraintMetadata constraint : metadata.constraints()) {
            if ("PRIMARY_KEY".equalsIgnoreCase(constraint.getType())) {
                constraint.getColumns().stream()
                        .map(ErDiagramMetadataLoader::normalize)
                        .forEach(primaryKeys::add);
            }
        }
        return primaryKeys;
    }

    private static TableChoice resolveReferencedChoice(
            List<TableChoice> choices,
            TableChoice from,
            String referencedTable) {
        String normalizedReference = normalizeIdentifier(referencedTable);
        for (TableChoice choice : choices) {
            if (choice.namespaceEquals(from)
                    && normalizeIdentifier(choice.name())
                            .equals(normalizedReference)) {
                return choice;
            }
        }
        for (TableChoice choice : choices) {
            if (normalizeIdentifier(choice.name())
                    .equals(normalizedReference)) {
                return choice;
            }
        }
        return null;
    }

    private static String graphKey(TableChoice choice) {
        return ErDiagramDialog.key(
                choice.displayNamespace(), choice.name());
    }

    private static String normalizeIdentifier(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("`", "")
                .replace("\"", "")
                .replace("[", "")
                .replace("]", "")
                .trim();
        String[] parts = normalized.split("[./]");
        return normalize(parts.length == 0
                ? normalized : parts[parts.length - 1]);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String valueAt(List<String> values, int index) {
        return index >= 0 && index < values.size()
                ? values.get(index) : "?";
    }

    private static String property(TreeNode node, String key) {
        Object value = node.getProperties() == null
                ? null : node.getProperties().get(key);
        return value == null ? null : blankToNull(value.toString());
    }

    private static String firstNonBlank(String first, String second) {
        String value = blankToNull(first);
        return value == null ? blankToNull(second) : value;
    }

    private static String join(String first, String second) {
        String left = blankToNull(first);
        String right = blankToNull(second);
        if (left == null) {
            return right;
        }
        return right == null ? left : left + "/" + right;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @FunctionalInterface
    interface MetadataOperation<T> {
        T apply(String namespace, String table) throws Exception;
    }

    record MetadataSource(
            MetadataOperation<List<ColumnMetadata>> columnsOperation,
            MetadataOperation<List<TableConstraintMetadata>>
                    constraintsOperation) {
        List<ColumnMetadata> columns(String namespace, String table)
                throws Exception {
            List<ColumnMetadata> value = columnsOperation.apply(
                    namespace, table);
            return value == null ? List.of() : List.copyOf(value);
        }

        List<TableConstraintMetadata> constraints(
                String namespace, String table) throws Exception {
            List<TableConstraintMetadata> value = constraintsOperation.apply(
                    namespace, table);
            return value == null ? List.of() : List.copyOf(value);
        }
    }

    record TableChoice(
            String name,
            String namespace,
            String displayNamespace,
            String objectType,
            String path) {
        TableChoice {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("表名不能为空");
            }
            name = name.trim();
            objectType = objectType == null || objectType.isBlank()
                    ? "TABLE" : objectType.toUpperCase(Locale.ROOT);
        }

        String key() {
            return (namespace == null ? "" : namespace.toLowerCase(Locale.ROOT))
                    + "\u0000" + name.toLowerCase(Locale.ROOT);
        }

        String label() {
            return displayNamespace == null || displayNamespace.isBlank()
                    ? name : displayNamespace.replace('/', '.') + "." + name;
        }

        boolean namespaceEquals(TableChoice other) {
            return normalize(namespace).equals(normalize(other.namespace));
        }
    }

    record TableMetadata(
            List<ColumnMetadata> columns,
            List<TableConstraintMetadata> constraints) {
        TableMetadata {
            columns = columns == null ? List.of() : List.copyOf(columns);
            constraints = constraints == null
                    ? List.of() : List.copyOf(constraints);
        }
    }
}
