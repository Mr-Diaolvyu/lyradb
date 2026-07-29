package io.github.lexaquila.lyradb.desktop.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlWorkspacePanelTest {

    @Test
    void shouldNeutralizeSpreadsheetFormulasInCsvExport() {
        assertThat(SqlWorkspacePanel.protectSpreadsheetFormula("=1+1"))
                .isEqualTo("'=1+1");
        assertThat(SqlWorkspacePanel.protectSpreadsheetFormula("  @SUM(A1:A2)"))
                .isEqualTo("'  @SUM(A1:A2)");
        assertThat(SqlWorkspacePanel.protectSpreadsheetFormula("-42"))
                .isEqualTo("'-42");
        assertThat(SqlWorkspacePanel.protectSpreadsheetFormula("普通文本"))
                .isEqualTo("普通文本");
        assertThat(SqlWorkspacePanel.protectSpreadsheetFormula("\t=cmd"))
                .isEqualTo("'\t=cmd");
        assertThat(SqlWorkspacePanel.protectSpreadsheetFormula("\ufeff\u00a0@cmd"))
                .isEqualTo("'\ufeff\u00a0@cmd");
    }

    @Test
    void shouldQuoteCsvAndEscapeEmbeddedQuotes() {
        assertThat(SqlWorkspacePanel.csv("a\"b"))
                .isEqualTo("\"a\"\"b\"");
        assertThat(SqlWorkspacePanel.csv("+cmd"))
                .isEqualTo("\"'+cmd\"");
    }

    @Test
    void shouldWriteUtf8BomCsvFromStableSnapshot(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("result.csv");

        SqlWorkspacePanel.writeCsv(target,
                List.of("名称", "公式"),
                List.of(
                        List.of("a,b", "=1+1"),
                        List.of("换\n行", "普通文本")));

        String content = Files.readString(target);
        assertThat(content).startsWith("\ufeff\"名称\",\"公式\"");
        assertThat(content).contains("\"a,b\",\"'=1+1\"");
        assertThat(content).contains("\"换\n行\",\"普通文本\"");
    }

    @Test
    void shouldEllipsizeLongConnectionNamesWithoutSplittingUnicode() {
        assertThat(SqlWorkspacePanel.ellipsize("生产主库", 8))
                .isEqualTo("生产主库");
        String longName = "生产环境订单分析数据库连接😀备用节点";
        String shortened = SqlWorkspacePanel.ellipsize(longName, 12);
        assertThat(shortened).endsWith("…");
        assertThat(shortened.codePointCount(0, shortened.length())).isEqualTo(12);
    }
}
