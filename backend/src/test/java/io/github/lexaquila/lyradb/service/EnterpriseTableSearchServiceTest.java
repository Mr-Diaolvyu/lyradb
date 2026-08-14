package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.dto.EnterpriseMetadataCatalog;
import io.github.lexaquila.lyradb.model.entity.AiProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnterpriseTableSearchServiceTest {

    private EnterpriseMetadataCatalogService catalogService;
    private AiProviderService providerService;
    private EnterpriseTableSearchService service;

    @BeforeEach
    void setUp() {
        catalogService = mock(EnterpriseMetadataCatalogService.class);
        providerService = mock(AiProviderService.class);
        service = new EnterpriseTableSearchService(
                catalogService, providerService,
                mock(AiFeatureGate.class), new ObjectMapper());
    }

    @Test
    void shouldRankChineseCommentBeforeUnrelatedTables() {
        var ranked = EnterpriseTableSearchService.rankLocally(
                catalog().getTables(), "帮我找项目相关的表");

        assertThat(ranked.get(0).table().getQualifiedName())
                .isEqualTo("dwd.dwd_project_info");
        assertThat(ranked.get(0).reason()).contains("中文注释");
    }

    @Test
    void shouldAcceptOnlyModelPathsFromAuthorizedCandidates()
            throws Exception {
        when(catalogService.catalog("旧数仓", false))
                .thenReturn(catalog());
        when(providerService.resolveDefault("workspace-1"))
                .thenReturn(new AiProviderConfig());
        when(providerService.chat(
                org.mockito.ArgumentMatchers.any(), anyList()))
                .thenReturn("""
                        ```json
                        {"recommendations":[
                          {"path":"dwd.dwd_project_info","reason":"项目主题主表","confidence":92},
                          {"path":"secret.hidden_table","reason":"越权对象","confidence":99}
                        ]}
                        ```
                        """);

        var result = service.search(
                "workspace-1", "旧数仓", "项目", 10);

        assertThat(result.mode()).isEqualTo("AI");
        assertThat(result.recommendations()).singleElement()
                .satisfies(item -> {
                    assertThat(item.path())
                            .isEqualTo("dwd.dwd_project_info");
                    assertThat(item.reason()).isEqualTo("项目主题主表");
                });
    }

    @Test
    void shouldFallBackToLocalMetadataWhenProviderFails()
            throws Exception {
        when(catalogService.catalog("旧数仓", false))
                .thenReturn(catalog());
        when(providerService.resolveDefault("workspace-1"))
                .thenThrow(new IllegalStateException("未配置"));

        var result = service.search(
                "workspace-1", "旧数仓", "客户", 5);

        assertThat(result.mode()).isEqualTo("LOCAL_FALLBACK");
        assertThat(result.message()).contains("AI 暂不可用");
        assertThat(result.recommendations()).first()
                .extracting("path")
                .isEqualTo("dim.dim_customer");
    }

    private static EnterpriseMetadataCatalog catalog() {
        EnterpriseMetadataCatalog catalog =
                new EnterpriseMetadataCatalog();
        catalog.setGrantedSourceName("旧数仓");
        catalog.setTables(List.of(
                table("dwd", "dwd_project_info", "项目基础信息明细表"),
                table("dim", "dim_customer", "客户维度表"),
                table("ads", "ads_dashboard", "经营看板汇总")));
        return catalog;
    }

    private static EnterpriseMetadataCatalog.Table table(
            String schema, String name, String remarks) {
        return new EnterpriseMetadataCatalog.Table(
                schema, schema, name, schema + "." + name,
                "TABLE", remarks);
    }
}
