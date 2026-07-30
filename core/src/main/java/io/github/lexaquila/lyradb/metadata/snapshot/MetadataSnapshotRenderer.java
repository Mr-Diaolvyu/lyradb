package io.github.lexaquila.lyradb.metadata.snapshot;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 元数据快照的确定性 UTF-8 JSON 与 Markdown 渲染器。
 */
public final class MetadataSnapshotRenderer {

    public static final int MAX_RENDERED_BYTES = 64 * 1024 * 1024;

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public String toJson(MetadataSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            String rendered = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(snapshot);
            validateRenderedSize(rendered);
            return rendered;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("元数据快照无法渲染为 JSON");
        }
    }

    public byte[] toJsonUtf8(MetadataSnapshot snapshot) {
        byte[] utf8 = toJson(snapshot).getBytes(StandardCharsets.UTF_8);
        validateRenderedSize(utf8.length);
        return utf8;
    }

    public String toMarkdown(MetadataSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        StringBuilder output = new StringBuilder();
        long databaseCount = snapshot.dataSources().stream()
                .mapToLong(source -> source.databases().size()).sum();
        long schemaCount = snapshot.dataSources().stream()
                .flatMap(source -> source.databases().stream())
                .mapToLong(database -> database.schemas().size()).sum();
        long tableCount = snapshot.dataSources().stream()
                .flatMap(source -> source.databases().stream())
                .flatMap(database -> database.schemas().stream())
                .mapToLong(schema -> schema.tables().size()).sum();
        long columnCount = snapshot.dataSources().stream()
                .flatMap(source -> source.databases().stream())
                .flatMap(database -> database.schemas().stream())
                .flatMap(schema -> schema.tables().stream())
                .mapToLong(table -> table.columns().size()).sum();
        output.append("# LyraDB 数据库元数据快照\n\n")
                .append("> \u672c\u6587\u6863\u4ec5\u5305\u542b\u7ed3\u6784\u5143\u6570\u636e\uff0c\u4e0d\u5305\u542b\u4efb\u4f55\u4e1a\u52a1\u6570\u636e\u884c\u3002\n\n")
                .append("- 格式版本：").append(snapshot.formatVersion()).append('\n')
                .append("- 生成时间：").append(snapshot.capturedAt())
                .append('\n')
                .append("- 数据源数量：").append(snapshot.dataSources().size())
                .append("\n- \u6570\u636e\u5e93\u6570\u91cf\uff1a").append(databaseCount)
                .append("\n- Schema \u6570\u91cf\uff1a").append(schemaCount)
                .append("\n- \u8868/\u89c6\u56fe\u6570\u91cf\uff1a").append(tableCount)
                .append("\n- \u5b57\u6bb5\u6570\u91cf\uff1a").append(columnCount)
                .append("\n\n");

        for (MetadataSnapshot.DataSource source : snapshot.dataSources()) {
            output.append("## 数据源：").append(inline(source.name())).append('\n')
                    .append("\n- 标识：").append(display(source.id()))
                    .append("\n- 数据库类型：").append(display(source.dbType()))
                    .append("\n- 备注：").append(display(source.remarks()))
                    .append("\n\n");
            for (MetadataSnapshot.Database database : source.databases()) {
                output.append("### 数据库：")
                        .append(inline(database.name())).append('\n')
                        .append("\n- 备注：").append(display(database.remarks()))
                        .append("\n\n");
                for (MetadataSnapshot.Schema schema : database.schemas()) {
                    output.append("#### Schema：")
                            .append(schema.name().isBlank()
                                    ? "（默认）" : inline(schema.name()))
                            .append('\n')
                            .append("\n- 备注：").append(display(schema.remarks()))
                            .append("\n\n");
                    for (MetadataSnapshot.Table table : schema.tables()) {
                        appendTable(output, table);
                    }
                }
            }
        }
        String rendered = output.toString();
        validateRenderedSize(rendered);
        return rendered;
    }

    public byte[] toMarkdownUtf8(MetadataSnapshot snapshot) {
        byte[] utf8 = toMarkdown(snapshot).getBytes(StandardCharsets.UTF_8);
        validateRenderedSize(utf8.length);
        return utf8;
    }

    public long estimateJsonTokens(MetadataSnapshot snapshot) {
        return ApproximateTokenEstimator.estimateUtf8(toJsonUtf8(snapshot));
    }

    public long estimateMarkdownTokens(MetadataSnapshot snapshot) {
        return ApproximateTokenEstimator.estimateUtf8(toMarkdownUtf8(snapshot));
    }

    private static void appendTable(
            StringBuilder output, MetadataSnapshot.Table table) {
        output.append("##### ").append(inline(table.type()))
                .append("：").append(inline(table.name())).append('\n')
                .append("\n- 备注：").append(display(table.remarks()))
                .append("\n- 主键：");
        if (table.primaryKeyColumns().isEmpty()) {
            output.append('—');
        } else {
            output.append(table.primaryKeyColumns().stream()
                    .map(MetadataSnapshotRenderer::inline)
                    .collect(Collectors.joining("、")));
        }
        output.append("\n\n")
                .append("| # | 字段 | 数据类型 | 可空 | 主键 | 默认值 | 备注 |\n")
                .append("|---:|---|---|:---:|:---:|---|---|\n");

        Set<String> primaryKeys = table.primaryKeyColumns().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        List<MetadataSnapshot.Column> columns = table.columns();
        for (int index = 0; index < columns.size(); index++) {
            MetadataSnapshot.Column column = columns.get(index);
            String type = !column.typeName().isBlank()
                    ? column.typeName() : column.dataType();
            output.append("| ").append(index + 1)
                    .append(" | ").append(cell(column.name()))
                    .append(" | ").append(cell(type))
                    .append(" | ").append(column.nullable() ? "是" : "否")
                    .append(" | ").append(primaryKeys.contains(
                            column.name().toLowerCase(Locale.ROOT)) ? "是" : "否")
                    .append(" | ").append(cellOrDash(column.defaultValue()))
                    .append(" | ").append(cellOrDash(column.remarks()))
                    .append(" |\n");
        }
        output.append('\n');
    }

    /**
     * 对 Markdown 表格与行内文本中的结构字符进行转义。
     */
    static String escapeMarkdown(String value) {
        String normalized = Objects.requireNonNullElse(value, "")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        StringBuilder escaped = new StringBuilder(normalized.length() + 16);
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '|' -> escaped.append("\\|");
                case '\n' -> escaped.append("<br>");
                case '`' -> escaped.append("\\`");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static String inline(String value) {
        return escapeMarkdown(value);
    }

    private static String cell(String value) {
        return escapeMarkdown(value);
    }

    private static String cellOrDash(String value) {
        return value == null || value.isBlank() ? "—" : cell(value);
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "—" : inline(value);
    }

    private static void validateRenderedSize(String rendered) {
        long byteLength = utf8Length(rendered);
        validateRenderedSize(byteLength);
    }

    private static void validateRenderedSize(long byteLength) {
        if (byteLength > MAX_RENDERED_BYTES) {
            throw new IllegalArgumentException("元数据快照渲染结果超过允许上限");
        }
    }

    private static long utf8Length(CharSequence value) {
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
            if (bytes > MAX_RENDERED_BYTES) {
                return bytes;
            }
        }
        return bytes;
    }
}
