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
}
