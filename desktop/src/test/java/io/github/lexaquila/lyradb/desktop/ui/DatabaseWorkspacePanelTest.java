package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.model.dto.TreeNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseWorkspacePanelTest {

    @Test
    void shouldReadCommentFromSupportedMetadataKeys() {
        TreeNode remarks = TreeNode.of("1", "table_a", "TABLE", "table_a");
        remarks.getProperties().put("remarks", "项目主数据");
        TreeNode comment = TreeNode.of("2", "table_b", "TABLE", "table_b");
        comment.getProperties().put("comment", "客户到访明细");
        TreeNode description = TreeNode.of("3", "table_c", "TABLE", "table_c");
        description.getProperties().put("description", "订单汇总");

        assertThat(DatabaseWorkspacePanel.nodeComment(remarks))
                .isEqualTo("项目主数据");
        assertThat(DatabaseWorkspacePanel.nodeComment(comment))
                .isEqualTo("客户到访明细");
        assertThat(DatabaseWorkspacePanel.nodeComment(description))
                .isEqualTo("订单汇总");
    }

    @Test
    void shouldReturnEmptyCommentForMissingMetadata() {
        TreeNode node = TreeNode.of("1", "table_a", "TABLE", "table_a");

        assertThat(DatabaseWorkspacePanel.nodeComment(node)).isEmpty();
        assertThat(DatabaseWorkspacePanel.nodeComment(null)).isEmpty();
    }

    @Test
    void shouldDistinguishMissingCommentFromUnavailableMetadata() {
        TreeNode unavailable = TreeNode.of(
                "1", "table_a", "TABLE", "table_a");
        unavailable.getProperties().put(
                "metadataSource", "SHOW_TABLES");
        unavailable.getProperties().put(
                "metadataStatus", "FALLBACK");
        unavailable.getProperties().put(
                "metadataReason", "当前降级链未返回 remarks");

        assertThat(DatabaseWorkspacePanel.nodeCommentDisplay(unavailable))
                .isEqualTo("注释未获取 · 当前降级链未返回 remarks");
        assertThat(DatabaseWorkspacePanel.nodeCommentDisplay(
                TreeNode.of("2", "table_b", "TABLE", "table_b")))
                .isEqualTo("注释尚未获取");

        TreeNode confirmedEmpty = TreeNode.of(
                "3", "table_c", "TABLE", "table_c");
        confirmedEmpty.getProperties().put("remarksStatus", "EMPTY");
        TreeNode explicitlyUnavailable = TreeNode.of(
                "4", "table_d", "TABLE", "table_d");
        explicitlyUnavailable.getProperties().put(
                "remarksStatus", "UNAVAILABLE");
        explicitlyUnavailable.getProperties().put(
                "metadataReason", "权限不足");

        assertThat(DatabaseWorkspacePanel.nodeCommentDisplay(confirmedEmpty))
                .isEqualTo("未设置注释");
        assertThat(DatabaseWorkspacePanel.nodeCommentDisplay(
                explicitlyUnavailable))
                .isEqualTo("注释元数据不可用 · 权限不足");
    }
}
