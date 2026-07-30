package io.github.lexaquila.lyradb.metadata.snapshot;

import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.TreeNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 不含数据行的数据库元数据快照。
 *
 * <p>层级固定为数据源 → 数据库 → Schema → 表 → 字段；主键按表保存，
 * 备注可以出现在每一层。所有集合和字符串均有硬上限，避免异常元数据导致
 * 无界内存消耗。</p>
 */
public record MetadataSnapshot(
        int formatVersion,
        String capturedAt,
        List<DataSource> dataSources) {

    public static final int FORMAT_VERSION = 1;
    public static final int MAX_DATA_SOURCES = 128;
    public static final int MAX_DATABASES_PER_SOURCE = 512;
    public static final int MAX_SCHEMAS_PER_DATABASE = 2_048;
    public static final int MAX_TABLES_PER_SCHEMA = 10_000;
    public static final int MAX_COLUMNS_PER_TABLE = 4_096;
    public static final int MAX_PRIMARY_KEY_COLUMNS = 4_096;
    public static final int MAX_TOTAL_ELEMENTS = 250_000;
    public static final int MAX_IDENTIFIER_LENGTH = 1_024;
    public static final int MAX_TEXT_LENGTH = 4_096;
    public static final int MAX_TEXT_UTF8_BYTES = 4 * 1_024;
    public static final int MAX_TOTAL_UTF8_BYTES = 16 * 1_024 * 1_024;

    private static final Comparator<String> TEXT_ORDER =
            Comparator.comparing((String value) -> value.toLowerCase(Locale.ROOT))
                    .thenComparing(Comparator.naturalOrder());

    public MetadataSnapshot {
        if (formatVersion != FORMAT_VERSION) {
            throw new IllegalArgumentException("不支持的元数据快照版本");
        }
        try {
            capturedAt = Instant.parse(
                    Objects.requireNonNull(capturedAt, "capturedAt"))
                    .toString();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("元数据快照生成时间无效", exception);
        }
        dataSources = sortedCopy(dataSources, MAX_DATA_SOURCES,
                Comparator.comparing(DataSource::name, TEXT_ORDER),
                "数据源数量超过上限");
        validateTotalElements(dataSources);
        validateTotalUtf8Bytes(capturedAt, dataSources);
    }

    public static MetadataSnapshot of(List<DataSource> dataSources) {
        return new MetadataSnapshot(FORMAT_VERSION, Instant.now().toString(), dataSources);
    }

    /**
     * 数据源快照。dbType 与现有 DriverInfo/DesktopConnection 的 dbType 对齐。
     */
    public record DataSource(
            String id,
            String name,
            String dbType,
            String remarks,
            List<Database> databases) {

        public DataSource {
            id = optionalIdentifier(id);
            name = requiredIdentifier(name, "数据源名称");
            dbType = requiredIdentifier(dbType, "数据库类型")
                    .toUpperCase(Locale.ROOT);
            remarks = text(remarks);
            databases = sortedCopy(databases, MAX_DATABASES_PER_SOURCE,
                    Comparator.comparing(Database::name, TEXT_ORDER),
                    "单个数据源的数据库数量超过上限");
        }

        public static DataSource fromTreeNode(
                TreeNode node, String dbType, List<Database> databases) {
            Objects.requireNonNull(node, "node");
            return new DataSource(node.getId(), node.getName(), dbType,
                    MetadataSnapshot.remarks(node), databases);
        }
    }

    /**
     * 数据库、Catalog、MaxCompute Project 或等价命名空间。
     */
    public record Database(
            String name,
            String remarks,
            List<Schema> schemas) {

        public Database {
            name = requiredIdentifier(name, "数据库名称");
            remarks = text(remarks);
            schemas = sortedCopy(schemas, MAX_SCHEMAS_PER_DATABASE,
                    Comparator.comparing(Schema::name, TEXT_ORDER),
                    "单个数据库的 Schema 数量超过上限");
        }

        public static Database fromTreeNode(
                TreeNode node, List<Schema> schemas) {
            Objects.requireNonNull(node, "node");
            return new Database(node.getName(), MetadataSnapshot.remarks(node), schemas);
        }
    }

    /**
     * Schema。空名称表示驱动没有独立 Schema 层。
     */
    public record Schema(
            String name,
            String remarks,
            List<Table> tables) {

        public Schema {
            name = optionalIdentifier(name);
            remarks = text(remarks);
            tables = sortedCopy(tables, MAX_TABLES_PER_SCHEMA,
                    Comparator.comparing(Table::name, TEXT_ORDER)
                            .thenComparing(Table::type, TEXT_ORDER),
                    "单个 Schema 的表数量超过上限");
        }

        public static Schema fromTreeNode(TreeNode node, List<Table> tables) {
            Objects.requireNonNull(node, "node");
            return new Schema(node.getName(), MetadataSnapshot.remarks(node), tables);
        }
    }

    /**
     * 表、视图或集合元数据。
     */
    public record Table(
            String name,
            String type,
            String remarks,
            List<Column> columns,
            List<String> primaryKeyColumns) {

        public Table {
            name = requiredIdentifier(name, "表名称");
            type = optionalIdentifier(type).toUpperCase(Locale.ROOT);
            if (type.isEmpty()) {
                type = "TABLE";
            }
            remarks = text(remarks);
            columns = limitedCopy(columns, MAX_COLUMNS_PER_TABLE,
                    "单张表的字段数量超过上限");
            primaryKeyColumns = distinctIdentifiers(
                    primaryKeyColumns, MAX_PRIMARY_KEY_COLUMNS,
                    "单张表的主键字段数量超过上限");
            validatePrimaryKeys(columns, primaryKeyColumns);
        }

        public static Table fromColumnMetadata(
                String tableName, String tableType, String remarks,
                List<ColumnMetadata> columns) {
            List<ColumnMetadata> safe =
                    columns == null ? List.of() : List.copyOf(columns);
            List<Column> snapshotColumns = safe.stream()
                    .map(Column::from)
                    .toList();
            List<String> primaryKeys = safe.stream()
                    .filter(ColumnMetadata::isPrimaryKey)
                    .map(ColumnMetadata::getName)
                    .toList();
            return new Table(tableName, tableType, remarks,
                    snapshotColumns, primaryKeys);
        }

        public static Table fromTreeNode(
                TreeNode node, List<ColumnMetadata> columns) {
            Objects.requireNonNull(node, "node");
            return fromColumnMetadata(node.getName(), node.getType(),
                    MetadataSnapshot.remarks(node), columns);
        }
    }

    /**
     * 字段结构，不含任何实际数据值。
     */
    public record Column(
            String name,
            String dataType,
            String typeName,
            boolean nullable,
            String defaultValue,
            String remarks) {

        public Column {
            name = requiredIdentifier(name, "字段名称");
            dataType = optionalIdentifier(dataType);
            typeName = optionalIdentifier(typeName);
            if (dataType.isEmpty() && typeName.isEmpty()) {
                throw new IllegalArgumentException("字段数据类型不能为空");
            }
            defaultValue = text(defaultValue);
            remarks = text(remarks);
        }

        public static Column from(ColumnMetadata source) {
            Objects.requireNonNull(source, "source");
            return new Column(source.getName(), source.getDataType(),
                    source.getTypeName(), source.isNullable(),
                    source.getDefaultValue(), source.getRemarks());
        }
    }

    private static void validatePrimaryKeys(
            List<Column> columns, List<String> primaryKeys) {
        Set<String> names = new LinkedHashSet<>();
        for (Column column : columns) {
            names.add(column.name().toLowerCase(Locale.ROOT));
        }
        for (String primaryKey : primaryKeys) {
            if (!names.contains(primaryKey.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("主键字段必须存在于字段列表");
            }
        }
    }

    private static List<String> distinctIdentifiers(
            List<String> source, int maximum, String message) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        if (source.size() > maximum) {
            throw new IllegalArgumentException(message);
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String value : source) {
            distinct.add(requiredIdentifier(value, "主键字段名称"));
        }
        return List.copyOf(distinct);
    }

    private static <T> List<T> limitedCopy(
            List<T> source, int maximum, String message) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        if (source.size() > maximum) {
            throw new IllegalArgumentException(message);
        }
        List<T> copy = new ArrayList<>(source.size());
        for (T value : source) {
            copy.add(Objects.requireNonNull(value, "元数据集合不能包含 null"));
        }
        return List.copyOf(copy);
    }

    private static <T> List<T> sortedCopy(
            List<T> source, int maximum, Comparator<T> comparator, String message) {
        List<T> copy = new ArrayList<>(limitedCopy(source, maximum, message));
        copy.sort(comparator);
        return List.copyOf(copy);
    }

    private static String requiredIdentifier(String value, String label) {
        String cleaned = optionalIdentifier(value);
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return cleaned;
    }

    private static String optionalIdentifier(String value) {
        String cleaned = Objects.requireNonNullElse(value, "").trim();
        if (cleaned.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException("元数据标识长度超过上限");
        }
        return cleaned;
    }

    private static String text(String value) {
        String cleaned = Objects.requireNonNullElse(value, "");
        if (cleaned.length() > MAX_TEXT_LENGTH
                || utf8Length(cleaned, MAX_TEXT_UTF8_BYTES)
                > MAX_TEXT_UTF8_BYTES) {
            throw new IllegalArgumentException("元数据文本长度超过上限");
        }
        return cleaned;
    }

    private static String remarks(TreeNode node) {
        Map<String, Object> properties = node.getProperties();
        if (properties == null || properties.isEmpty()) {
            return "";
        }
        for (String key : List.of("remarks", "comment", "description")) {
            Object value = properties.get(key);
            if (value != null) {
                return text(String.valueOf(value));
            }
        }
        return "";
    }

    private static void requireWithinTotalLimit(long total) {
        if (total > MAX_TOTAL_ELEMENTS) {
            throw new IllegalArgumentException(
                    "\u5143\u6570\u636e\u5feb\u7167\u603b\u5143\u7d20\u6570\u91cf\u8d85\u8fc7\u4e0a\u9650");
        }
    }

    private static void validateTotalElements(List<DataSource> sources) {
        long total = sources.size();
        requireWithinTotalLimit(total);
        for (DataSource source : sources) {
            total += source.databases().size();
            requireWithinTotalLimit(total);
            for (Database database : source.databases()) {
                total += database.schemas().size();
                requireWithinTotalLimit(total);
                for (Schema schema : database.schemas()) {
                    total += schema.tables().size();
                    requireWithinTotalLimit(total);
                    for (Table table : schema.tables()) {
                        total += table.columns().size();
                        total += table.primaryKeyColumns().size();
                        if (total > MAX_TOTAL_ELEMENTS) {
                            throw new IllegalArgumentException(
                                    "元数据快照总元素数量超过上限");
                        }
                    }
                }
            }
        }
    }

    private static void validateTotalUtf8Bytes(
            String capturedAt, List<DataSource> sources) {
        long total = 0;
        total = addUtf8Bytes(total, capturedAt);
        for (DataSource source : sources) {
            total = addUtf8Bytes(total, source.id());
            total = addUtf8Bytes(total, source.name());
            total = addUtf8Bytes(total, source.dbType());
            total = addUtf8Bytes(total, source.remarks());
            for (Database database : source.databases()) {
                total = addUtf8Bytes(total, database.name());
                total = addUtf8Bytes(total, database.remarks());
                for (Schema schema : database.schemas()) {
                    total = addUtf8Bytes(total, schema.name());
                    total = addUtf8Bytes(total, schema.remarks());
                    for (Table table : schema.tables()) {
                        total = addUtf8Bytes(total, table.name());
                        total = addUtf8Bytes(total, table.type());
                        total = addUtf8Bytes(total, table.remarks());
                        for (Column column : table.columns()) {
                            total = addUtf8Bytes(total, column.name());
                            total = addUtf8Bytes(total, column.dataType());
                            total = addUtf8Bytes(total, column.typeName());
                            total = addUtf8Bytes(total, column.defaultValue());
                            total = addUtf8Bytes(total, column.remarks());
                        }
                        for (String primaryKey : table.primaryKeyColumns()) {
                            total = addUtf8Bytes(total, primaryKey);
                        }
                    }
                }
            }
        }
    }

    private static long addUtf8Bytes(long current, String value) {
        long remaining = MAX_TOTAL_UTF8_BYTES - current;
        long valueBytes = utf8Length(
                Objects.requireNonNullElse(value, ""), remaining);
        if (remaining < 0 || valueBytes > remaining) {
            throw new IllegalArgumentException(
                    "\u5143\u6570\u636e\u5feb\u7167\u7d2f\u8ba1 UTF-8 "
                            + "\u6587\u672c\u8d85\u8fc7 16 MiB \u4e0a\u9650");
        }
        return current + valueBytes;
    }

    private static long utf8Length(CharSequence value, long limit) {
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x7F) {
                bytes++;
            } else if (character <= 0x7FF) {
                bytes += 2;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
            if (bytes > limit) {
                return bytes;
            }
        }
        return bytes;
    }
}
