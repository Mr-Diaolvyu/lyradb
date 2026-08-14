package io.github.lexaquila.lyradb.desktop.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiTableSearchSupportTest {

    @Test
    void shouldMatchNameCommentNamespaceAndFullPath() {
        AiTableSearchSupport.CatalogEntry entry = entry(
                "dim_project", "项目基础信息",
                "old_jfdw_maxcompute", "warehouse/dim_project");

        assertThat(AiTableSearchSupport.matches("dim_project", entry)).isTrue();
        assertThat(AiTableSearchSupport.matches("项目", entry)).isTrue();
        assertThat(AiTableSearchSupport.matches("old_jfdw", entry)).isTrue();
        assertThat(AiTableSearchSupport.matches("warehouse/dim", entry)).isTrue();
        assertThat(AiTableSearchSupport.matches("订单", entry)).isFalse();
        assertThat(AiTableSearchSupport.localRecommendations(
                "帮我找项目相关的表", List.of(entry), 10))
                .singleElement()
                .extracting(AiTableSearchSupport.Recommendation::entry)
                .isEqualTo(entry);
    }

    @Test
    void shouldPrioritizeLocalMetadataMatchesAndKeepBoundedCandidates() {
        List<AiTableSearchSupport.CatalogEntry> entries = new ArrayList<>();
        for (int index = 0; index < 80; index++) {
            entries.add(entry("table_" + index, "普通目录对象 " + index,
                    "warehouse", "warehouse/table_" + index));
        }
        AiTableSearchSupport.CatalogEntry project = entry(
                "dim_project", "项目基础信息", "warehouse",
                "warehouse/dim_project");
        entries.add(project);

        AiTableSearchSupport.Prompt prompt =
                AiTableSearchSupport.preparePrompt("项目", entries, 2_000);

        assertThat(prompt.candidates()).isNotEmpty();
        assertThat(prompt.candidates().get(0).entry()).isEqualTo(project);
        assertThat(prompt.metadataContext())
                .contains("仅包含当前已加载的目录元数据")
                .contains("项目基础信息")
                .doesNotContain("SELECT *");
        assertThat(prompt.truncated()).isTrue();
        assertThat(prompt.metadataContext().length()).isLessThanOrEqualTo(2_100);
    }

    @Test
    void shouldParseOnlyRealCandidatesFromJsonRecommendation() {
        AiTableSearchSupport.CatalogEntry project = entry(
                "dim_project", "项目基础信息", "warehouse",
                "warehouse/dim_project");
        AiTableSearchSupport.CatalogEntry visit = entry(
                "dwd_visit", "客户到访明细", "warehouse",
                "warehouse/dwd_visit");
        AiTableSearchSupport.Prompt prompt =
                AiTableSearchSupport.preparePrompt(
                        "客户到访", List.of(project, visit));
        String visitId = prompt.candidates().stream()
                .filter(candidate -> candidate.entry().equals(visit))
                .findFirst().orElseThrow().candidateId();

        String response = """
                推荐如下：
                ```json
                {"recommendations":[
                  {"candidateId":"%s","reason":"注释明确包含客户到访"},
                  {"path":"warehouse/dim_project","reason":"可补充项目维度"},
                  {"candidateId":"T999","reason":"模型臆造对象"},
                  {"name":"dwd_visit","reason":"重复推荐"}
                ]}
                ```
                """.formatted(visitId);

        List<AiTableSearchSupport.Recommendation> result =
                AiTableSearchSupport.parseRecommendations(response, prompt);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).entry()).isEqualTo(visit);
        assertThat(result.get(0).reason()).contains("客户到访");
        assertThat(result.get(1).entry()).isEqualTo(project);
    }

    @Test
    void shouldProvideLocalFallbackWithAuditableReason() {
        AiTableSearchSupport.CatalogEntry entry = entry(
                "dwd_customer_visit", "客户到访明细",
                "warehouse", "warehouse/dwd_customer_visit");

        assertThat(AiTableSearchSupport.localRecommendations(
                "客户到访", List.of(entry), 10))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.entry()).isEqualTo(entry);
                    assertThat(result.reason()).contains("注释");
                });
    }

    private static AiTableSearchSupport.CatalogEntry entry(
            String name, String comment, String namespace, String path) {
        return new AiTableSearchSupport.CatalogEntry(
                name, "TABLE", namespace, comment, path);
    }
}
