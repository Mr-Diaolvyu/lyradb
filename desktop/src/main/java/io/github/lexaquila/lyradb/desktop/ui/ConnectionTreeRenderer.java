package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.DesktopRuntime;

import javax.swing.JTree;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.Component;

/**
 * 在导航树中显示连接状态与对象类型。
 */
final class ConnectionTreeRenderer extends DefaultTreeCellRenderer {

    private final DesktopRuntime runtime;

    ConnectionTreeRenderer(DesktopRuntime runtime) {
        this.runtime = runtime;
        setBackgroundNonSelectionColor(NativeTheme.SURFACE);
        setBackgroundSelectionColor(NativeTheme.ACCENT.darker());
        setTextNonSelectionColor(NativeTheme.FOREGROUND);
        setTextSelectionColor(java.awt.Color.WHITE);
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value,
            boolean selected, boolean expanded, boolean leaf,
            int row, boolean focused) {
        super.getTreeCellRendererComponent(tree, value, selected, expanded,
                leaf, row, focused);
        if (value instanceof javax.swing.tree.DefaultMutableTreeNode treeNode
                && treeNode.getUserObject() instanceof MainFrame.BrowserItem item) {
            if (item.connectionRoot) {
                boolean connected =
                        runtime.connectionManager().isConnected(item.connectionId);
                setText((connected ? "●  " : "○  ") + item);
                if (!selected) {
                    setForeground(connected ? NativeTheme.SUCCESS : NativeTheme.MUTED);
                }
                setToolTipText(connected ? "已连接（原生直连）" : "未连接");
            } else if (item.node != null) {
                setText(icon(item.node.getType()) + "  " + item.node.getName());
                setToolTipText(item.node.getType() + " · " + item.node.getPath());
            }
        }
        return this;
    }

    private static String icon(String type) {
        if (type == null) {
            return "•";
        }
        return switch (type) {
            case "DATABASE" -> "▣";
            case "SCHEMA" -> "◇";
            case "TABLE" -> "▦";
            case "VIEW" -> "◫";
            case "PROCEDURE", "FUNCTION" -> "ƒ";
            case "TRIGGER" -> "⚡";
            case "COLLECTION" -> "▤";
            case "KEY" -> "🔑";
            default -> "•";
        };
    }
}
