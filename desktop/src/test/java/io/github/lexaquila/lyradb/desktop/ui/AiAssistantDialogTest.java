package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.ai.AiTask;
import io.github.lexaquila.lyradb.desktop.metadata.MetadataSelection;
import io.github.lexaquila.lyradb.metadata.snapshot.MetadataSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiAssistantDialogTest {

    @Test
    void shouldExposeDatabaseWorkAsDirectShortcuts() {
        assertThat(AiAssistantDialog.quickTasks()).containsExactly(
                AiTask.FIND_TABLE,
                AiTask.EXPLAIN_TABLE,
                AiTask.GENERATE,
                AiTask.OPTIMIZE,
                AiTask.FIX,
                AiTask.LINEAGE_IMPACT,
                AiTask.DATA_QUALITY);
        assertThat(AiAssistantDialog.quickTasks())
                .doesNotContain(AiTask.EXPLAIN, AiTask.REVIEW);
    }

    @Test
    void shouldSummarizeCurrentSqlWithoutHidingItsPresence() {
        assertThat(AiAssistantDialog.formatSqlSummary("  SELECT 1;  "))
                .isEqualTo("当前 SQL：已包含（9 字符）");
        assertThat(AiAssistantDialog.formatSqlSummary("  "))
                .isEqualTo("当前 SQL：未选择");
    }

    @Test
    void shouldSummarizeCurrentMetadataSelection() {
        MetadataSelection selection = new MetadataSelection(
                "connection-1", "maxcompute", MetadataSelection.Scope.TABLE,
                "dws_customer_label", "connection/schema/dws_customer_label",
                "TABLE");

        assertThat(AiAssistantDialog.formatSelectionSummary(selection))
                .isEqualTo("当前选择：表 · dws_customer_label");
        assertThat(AiAssistantDialog.formatSelectionSummary(null))
                .isEqualTo("当前选择：无");
    }

    @Test
    void metadataBasedTasksMustBeBlockedUntilAttachmentIsConfirmed() {
        assertThat(AiAssistantDialog.isRequiredMetadataMissing(
                AiTask.FIND_TABLE, false)).isTrue();
        assertThat(AiAssistantDialog.isRequiredMetadataMissing(
                AiTask.FIND_TABLE, true)).isFalse();
        assertThat(AiAssistantDialog.isRequiredMetadataMissing(
                AiTask.GENERATE, false)).isFalse();
    }

    @Test
    void findTableShouldBuildBoundedCandidatesFromAttachedSnapshot() {
        MetadataSnapshot.Table table = new MetadataSnapshot.Table(
                "dwd_visit", "TABLE", "项目客户到访明细",
                List.of(), List.of());
        MetadataSnapshot.Schema schema = new MetadataSnapshot.Schema(
                "", "", List.of(table));
        MetadataSnapshot.Database database = new MetadataSnapshot.Database(
                "old_project", "", List.of(schema));
        MetadataSnapshot.DataSource source = new MetadataSnapshot.DataSource(
                "source-1", "旧数仓", "MAXCOMPUTE", "",
                List.of(database));
        MetadataSnapshot snapshot = MetadataSnapshot.of(List.of(source));

        assertThat(AiAssistantDialog.catalogEntries(snapshot))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.path())
                            .isEqualTo("old_project.dwd_visit");
                    assertThat(entry.comment())
                            .isEqualTo("项目客户到访明细");
                });
    }

    @Test
    void shouldExtractSqlFromMarkdownFence() {
        assertThat(AiAssistantDialog.extractSql(
                "建议如下：\n```sql\nSELECT * FROM orders;\n```\n请核对"))
                .isEqualTo("SELECT * FROM orders;");
    }

    @Test
    void shouldAcceptPlainSqlWhenNoFenceExists() {
        assertThat(AiAssistantDialog.extractSql("  SELECT 1;  "))
                .isEqualTo("SELECT 1;");
        assertThat(AiAssistantDialog.extractSql(null)).isEmpty();
    }

    @Test
    void shouldRejectExplanatoryTextAndNonSqlCodeBlocks() {
        assertThat(AiAssistantDialog.extractSql(
                "建议先确认订单状态口径，然后再生成查询。"))
                .isEmpty();
        assertThat(AiAssistantDialog.extractSql(
                "```text\nSELECT * FROM orders;\n```"))
                .isEmpty();
    }

    @Test
    void shouldExtractOnlySupportedRedisCommands() {
        assertThat(AiAssistantDialog.extractCommand(
                "建议：\n```redis\nGET user:1\n```", "REDIS"))
                .isEqualTo("GET user:1");
        assertThat(AiAssistantDialog.extractCommand(
                "```redis\nMGET a b\n```", "REDIS"))
                .isEmpty();
    }

    @Test
    void shouldExtractMongoReadAndJsonDslCommands() {
        assertThat(AiAssistantDialog.extractCommand(
                "```mongodb\nsales.orders\n```", "MONGODB"))
                .isEqualTo("sales.orders");
        assertThat(AiAssistantDialog.extractCommand(
                "```json\n{\"op\":\"delete\",\"db\":\"sales\","
                        + "\"collection\":\"orders\",\"filter\":{\"id\":1}}\n```",
                "MONGODB"))
                .contains("\"op\":\"delete\"");
        assertThat(AiAssistantDialog.extractCommand(
                "```sql\nSELECT 1\n```", "MONGODB"))
                .isEmpty();
    }
}
