package io.github.lexaquila.lyradb.metadata.snapshot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataSnapshotRendererTest {

    private final MetadataSnapshotRenderer renderer =
            new MetadataSnapshotRenderer();

    @Test
    void shouldRenderStructureOnlyDeclarationCountsAndEscapedMarkdown() {
        MetadataSnapshot snapshot = sampleSnapshot();

        String markdown = renderer.toMarkdown(snapshot);

        assertThat(markdown)
                .contains("\u672c\u6587\u6863\u4ec5\u5305\u542b\u7ed3\u6784\u5143\u6570\u636e")
                .contains("\u4e0d\u5305\u542b\u4efb\u4f55\u4e1a\u52a1\u6570\u636e\u884c")
                .contains("生成时间：")
                .contains("\u6570\u636e\u6e90\u6570\u91cf\uff1a1")
                .contains("\u6570\u636e\u5e93\u6570\u91cf\uff1a1")
                .contains("Schema \u6570\u91cf\uff1a1")
                .contains("\u8868/\u89c6\u56fe\u6570\u91cf\uff1a1")
                .contains("\u5b57\u6bb5\u6570\u91cf\uff1a1")
                .contains("user\\|id")
                .contains("&lt;important&gt;<br>next");
    }

    @Test
    void shouldRenderUtf8JsonWithoutBusinessRows() {
        byte[] json = renderer.toJsonUtf8(sampleSnapshot());
        String value = new String(json, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(value)
                .contains("capturedAt", "MYSQL", "customer", "user|id")
                .doesNotContain("\"rows\"", "\"data\"");
    }

    @Test
    void shouldEstimateUtf8TokensConservatively() {
        assertThat(ApproximateTokenEstimator.estimate("")).isZero();
        assertThat(ApproximateTokenEstimator.estimate("abc")).isEqualTo(1);
        assertThat(ApproximateTokenEstimator.estimate("\u4e2da")).isEqualTo(2);
        assertThat(renderer.estimateMarkdownTokens(sampleSnapshot()))
                .isGreaterThan(0);
    }

    @Test
    void shouldRejectPrimaryKeyThatIsNotAColumn() {
        assertThatThrownBy(() -> new MetadataSnapshot.Table(
                "customer", "TABLE", "", List.of(
                        new MetadataSnapshot.Column(
                                "id", "BIGINT", "BIGINT", false, "", "")),
                List.of("missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\u4e3b\u952e\u5b57\u6bb5");
    }

    @Test
    void shouldRejectTotalLimitEvenWhenSchemasContainNoTables() {
        List<MetadataSnapshot.Schema> schemas = IntStream.range(
                        0, MetadataSnapshot.MAX_SCHEMAS_PER_DATABASE)
                .mapToObj(index -> new MetadataSnapshot.Schema(
                        "schema_" + index, "", List.of()))
                .toList();
        List<MetadataSnapshot.Database> databases = IntStream.range(0, 123)
                .mapToObj(index -> new MetadataSnapshot.Database(
                        "database_" + index, "", schemas))
                .toList();

        assertThatThrownBy(() -> MetadataSnapshot.of(List.of(
                new MetadataSnapshot.DataSource(
                        "source", "source", "MYSQL", "", databases))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\u603b\u5143\u7d20\u6570\u91cf\u8d85\u8fc7\u4e0a\u9650");
    }

    @Test
    void shouldAcceptTextAtUtf8ByteLimit() {
        String value = "x".repeat(MetadataSnapshot.MAX_TEXT_UTF8_BYTES);

        MetadataSnapshot.Column column = new MetadataSnapshot.Column(
                "payload", "VARCHAR", "VARCHAR", true, value, "");

        assertThat(column.defaultValue()).hasSize(
                MetadataSnapshot.MAX_TEXT_UTF8_BYTES);
    }

    @Test
    void shouldRejectTextBeyondUtf8ByteLimit() {
        String value = "x".repeat(MetadataSnapshot.MAX_TEXT_UTF8_BYTES + 1);

        assertThatThrownBy(() -> new MetadataSnapshot.Column(
                "payload", "VARCHAR", "VARCHAR", true, value, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\u5143\u6570\u636e\u6587\u672c");
    }

    @Test
    void shouldRejectMultibyteTextBeyondUtf8ByteLimit() {
        String value = "\u4e2d".repeat(
                MetadataSnapshot.MAX_TEXT_UTF8_BYTES / 3 + 1);

        assertThat(value).hasSizeLessThan(MetadataSnapshot.MAX_TEXT_LENGTH);
        assertThatThrownBy(() -> new MetadataSnapshot.Column(
                "payload", "VARCHAR", "VARCHAR", true, value, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\u5143\u6570\u636e\u6587\u672c");
    }

    @Test
    void shouldAcceptSnapshotBelowCumulativeUtf8ByteLimit() {
        String value = "x".repeat(MetadataSnapshot.MAX_TEXT_UTF8_BYTES);

        assertThat(snapshotWithRepeatedColumnText(2_000, value))
                .isNotNull();
    }

    @Test
    void shouldRejectSnapshotBeyondCumulativeUtf8ByteLimit() {
        String value = "x".repeat(MetadataSnapshot.MAX_TEXT_UTF8_BYTES);

        assertThatThrownBy(() -> snapshotWithRepeatedColumnText(2_050, value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\u7d2f\u8ba1 UTF-8");
    }

    @Test
    void shouldSortSourcesAndTablesDeterministically() {
        MetadataSnapshot.Table b = table("b_table");
        MetadataSnapshot.Table a = table("a_table");
        MetadataSnapshot snapshot = MetadataSnapshot.of(List.of(
                source("z_source", List.of(b, a)),
                source("a_source", List.of(b, a))));

        assertThat(snapshot.dataSources())
                .extracting(MetadataSnapshot.DataSource::name)
                .containsExactly("a_source", "z_source");
        assertThat(snapshot.dataSources().get(0).databases().get(0)
                .schemas().get(0).tables())
                .extracting(MetadataSnapshot.Table::name)
                .containsExactly("a_table", "b_table");
    }

    private static MetadataSnapshot snapshotWithRepeatedColumnText(
            int columnCount, String value) {
        List<MetadataSnapshot.Column> columns = IntStream.range(0, columnCount)
                .mapToObj(index -> new MetadataSnapshot.Column(
                        "column_" + index, "VARCHAR", "VARCHAR", true,
                        value, value))
                .toList();
        MetadataSnapshot.Table table = new MetadataSnapshot.Table(
                "wide_table", "TABLE", "", columns, List.of());
        return MetadataSnapshot.of(List.of(source("main", List.of(table))));
    }

    private static MetadataSnapshot sampleSnapshot() {
        MetadataSnapshot.Column column = new MetadataSnapshot.Column(
                "user|id", "BIGINT", "BIGINT", false, "",
                "<important>\nnext");
        MetadataSnapshot.Table table = new MetadataSnapshot.Table(
                "customer", "TABLE", "", List.of(column),
                List.of("user|id"));
        return MetadataSnapshot.of(List.of(source("main", List.of(table))));
    }

    private static MetadataSnapshot.DataSource source(
            String name, List<MetadataSnapshot.Table> tables) {
        MetadataSnapshot.Schema schema = new MetadataSnapshot.Schema(
                "public", "", tables);
        MetadataSnapshot.Database database = new MetadataSnapshot.Database(
                "app", "", List.of(schema));
        return new MetadataSnapshot.DataSource(
                name, name, "MYSQL", "", List.of(database));
    }

    private static MetadataSnapshot.Table table(String name) {
        return new MetadataSnapshot.Table(name, "TABLE", "", List.of(
                new MetadataSnapshot.Column(
                        "id", "BIGINT", "BIGINT", false, "", "")),
                List.of("id"));
    }
}
