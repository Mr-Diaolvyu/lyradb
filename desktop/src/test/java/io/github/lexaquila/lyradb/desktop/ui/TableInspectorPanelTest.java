package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.model.dto.TableCommentMetadata;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TableInspectorPanelTest {

    @Test
    void shouldDistinguishConfirmedEmptyFromUnavailableComment() {
        TableCommentMetadata empty = comment(
                null, "TENANT_INFORMATION_SCHEMA", "COMPLETE",
                "EMPTY", "权威元数据确认未设置");
        TableCommentMetadata unavailable = comment(
                null, "SHOW_TABLES", "FALLBACK",
                "UNAVAILABLE", "降级接口没有 remarks 字段");

        TableInspectorPanel.CommentPresentation emptyView =
                TableInspectorPanel.commentPresentation(
                        TableInspectorPanel.MetadataHint.empty(),
                        empty, null);
        TableInspectorPanel.CommentPresentation unavailableView =
                TableInspectorPanel.commentPresentation(
                        TableInspectorPanel.MetadataHint.empty(),
                        unavailable, null);

        assertThat(emptyView.headerText()).contains("未设置");
        assertThat(emptyView.unavailable()).isFalse();
        assertThat(unavailableView.headerText())
                .contains("元数据不可用")
                .contains("降级接口没有 remarks 字段");
        assertThat(unavailableView.unavailable()).isTrue();
    }

    @Test
    void shouldKeepCatalogCommentWhenSingleTableRefreshFails() {
        TableInspectorPanel.MetadataHint hint =
                new TableInspectorPanel.MetadataHint(
                        "客户到访明细", "PROJECT_INFORMATION_SCHEMA",
                        "COMPLETE", "目录已返回注释", "AVAILABLE", true);

        TableInspectorPanel.CommentPresentation presentation =
                TableInspectorPanel.commentPresentation(
                        hint, null, "单表元数据接口超时");

        assertThat(presentation.headerText()).contains("客户到访明细");
        assertThat(presentation.details())
                .contains("沿用目录注释")
                .contains("单表元数据接口超时");
    }

    @Test
    void shouldFailClosedUnlessMaxComputePartitionStateIsConfirmed() {
        assertThat(TableInspectorPanel.previewAllowed(
                true, true, true, null)).isFalse();
        assertThat(TableInspectorPanel.previewAllowed(
                true, true, true, "  ")).isFalse();
        assertThat(TableInspectorPanel.previewAllowed(
                true, true, true,
                "ds=20260814/region=hangzhou")).isTrue();
        assertThat(TableInspectorPanel.previewAllowed(
                true, true, false, null)).isTrue();
        assertThat(TableInspectorPanel.previewAllowed(
                true, true, null,
                "ds=20260814/region=hangzhou")).isFalse();
        assertThat(TableInspectorPanel.previewAllowed(
                false, true, null, null)).isTrue();
    }

    @Test
    void shouldCaptureNodeMetadataEvidence() {
        TreeNode node = TreeNode.of("1", "visit_detail", "TABLE",
                "visit_detail");
        node.getProperties().put("remarks", "到访明细");
        node.getProperties().put(
                "metadataSource", "TENANT_INFORMATION_SCHEMA");
        node.getProperties().put("metadataStatus", "COMPLETE");
        node.getProperties().put("remarksStatus", "AVAILABLE");
        node.getProperties().put("partitioned", true);

        TableInspectorPanel.MetadataHint hint =
                TableInspectorPanel.MetadataHint.fromNode(node);

        assertThat(hint.comment()).isEqualTo("到访明细");
        assertThat(hint.metadataSource())
                .isEqualTo("TENANT_INFORMATION_SCHEMA");
        assertThat(hint.partitioned()).isTrue();
    }

    private static TableCommentMetadata comment(
            String remarks,
            String source,
            String status,
            String remarksStatus,
            String reason) {
        TableCommentMetadata metadata = new TableCommentMetadata();
        metadata.setRemarks(remarks);
        metadata.setMetadataSource(source);
        metadata.setMetadataStatus(status);
        metadata.setRemarksStatus(remarksStatus);
        metadata.setMetadataReason(reason);
        return metadata;
    }
}
