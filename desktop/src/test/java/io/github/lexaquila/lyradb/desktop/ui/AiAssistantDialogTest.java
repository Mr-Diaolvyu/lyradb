package io.github.lexaquila.lyradb.desktop.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiAssistantDialogTest {

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
