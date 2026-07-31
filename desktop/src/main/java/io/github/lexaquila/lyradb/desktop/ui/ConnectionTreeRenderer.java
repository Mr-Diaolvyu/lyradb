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
        setBackgroundSelectionColor(NativeTheme.ACCENT_SOFT);
        setTextNonSelectionColor(NativeTheme.FOREGROUND);
        setTextSelectionColor(NativeTheme.FOREGROUND);
        setBorderSelectionColor(NativeTheme.ACCENT);
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
                setText(item.connection.getName() + "  ·  "
                        + item.connection.getDbType());
                setIcon(LyraIcons.databaseEngine(
                        item.connection.getDbType(), 20, connected));
                setOpenIcon(getIcon());
                setClosedIcon(getIcon());
                if (!selected) {
                    setForeground(connected
                            ? NativeTheme.FOREGROUND : NativeTheme.MUTED);
                }
                setToolTipText(item.connection.getDbType()
                        + " · " + (connected ? "已连接" : "未连接"));
            } else if (item.node != null) {
                setText(item.node.getName());
                setIcon(LyraIcons.treeNode(
                        item.node.getType(), item.node.getProperties(), 16));
                setOpenIcon(getIcon());
                setClosedIcon(getIcon());
                if (!selected) {
                    setForeground(NativeTheme.FOREGROUND);
                }
                setToolTipText(item.node.getType() + " · " + item.node.getPath());
            }
        } else {
            setIcon(null);
            setOpenIcon(null);
            setClosedIcon(null);
            if (!selected) {
                setForeground(NativeTheme.MUTED);
            }
        }
        return this;
    }
}
