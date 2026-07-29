package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.DesktopRuntime;
import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;
import io.github.lexaquila.lyradb.model.dto.TreeNode;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * LyraDB 个人版原生主窗口。
 */
public final class MainFrame extends JFrame {

    private static final String PLACEHOLDER = "正在等待展开…";

    private final DesktopRuntime runtime;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("连接");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree connectionTree = new JTree(treeModel);
    private final JTabbedPane workspaces = new JTabbedPane();
    private final CardLayout navigatorLayout = new CardLayout();
    private final JPanel navigatorCards = new JPanel(navigatorLayout);
    private final JLabel connectionCount = UiKit.badge(
            "0", NativeTheme.MUTED, NativeTheme.SURFACE_ALT);
    private final JLabel statusLabel =
            new JLabel("个人版 · 原生 C/S · 数据库直连 · AI 本地配置");
    private final Map<String, SqlWorkspacePanel> workspaceByConnection = new HashMap<>();
    private boolean shuttingDown;

    public MainFrame(DesktopRuntime runtime) {
        super("LyraDB " + io.github.lexaquila.lyradb.desktop.NativeDesktopApplication.VERSION
                + " · 个人版");
        this.runtime = runtime;
        setIconImage(LyraIcons.applicationImage());
        buildUi();
        refreshConnections();
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                shutdown();
            }
        });
        setMinimumSize(new Dimension(1180, 720));
        setSize(1440, 900);
        setLocationByPlatform(true);
    }

    private void buildUi() {
        setJMenuBar(createMenuBar());
        add(createToolbar(), BorderLayout.NORTH);
        getContentPane().setBackground(NativeTheme.BACKGROUND);

        connectionTree.setRootVisible(false);
        connectionTree.setShowsRootHandles(true);
        connectionTree.setBorder(BorderFactory.createEmptyBorder(6, 4, 8, 4));
        connectionTree.setRowHeight(29);
        connectionTree.setCellRenderer(new ConnectionTreeRenderer(runtime));
        connectionTree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event)
                    throws ExpandVetoException {
                DefaultMutableTreeNode node =
                        (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                if (needsLoading(node)) {
                    loadChildren(node);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
                // 无需处理。
            }
        });
        connectionTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isRightMouseButton(event)) {
                    int row = connectionTree.getClosestRowForLocation(event.getX(), event.getY());
                    connectionTree.setSelectionRow(row);
                    showTreePopup(event.getX(), event.getY());
                } else if (event.getClickCount() == 2) {
                    openSelected();
                }
            }
        });

        JPanel navigator = createNavigator();
        workspaces.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        workspaces.addTab("开始", new WelcomePanel(
                this::newConnection, this::openAiSettings, this::openAiAssistant));

        JSplitPane split =
                new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navigator, workspaces);
        split.setDividerLocation(282);
        split.setDividerSize(1);
        split.setResizeWeight(0);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);

        add(createStatusBar(), BorderLayout.SOUTH);
    }

    private JPanel createNavigator() {
        JPanel navigator = new JPanel(new BorderLayout());
        navigator.setBackground(NativeTheme.SURFACE);
        navigator.setBorder(BorderFactory.createMatteBorder(
                0, 0, 0, 1, NativeTheme.BORDER_SOFT));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(NativeTheme.SURFACE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        0, 0, 1, 0, NativeTheme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(12, 14, 12, 10)));
        JPanel titleGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        titleGroup.setOpaque(false);
        JLabel title = new JLabel("数据库导航器");
        title.setFont(NativeTheme.FONT_TITLE);
        title.setForeground(NativeTheme.FOREGROUND);
        titleGroup.add(title);
        titleGroup.add(connectionCount);
        header.add(titleGroup, BorderLayout.CENTER);
        JButton add = UiKit.iconButton(
                LyraIcons.of(LyraIcons.Kind.ADD_DATABASE, NativeTheme.ACCENT_LIGHT),
                "新建数据库连接");
        add.addActionListener(event -> newConnection());
        header.add(add, BorderLayout.EAST);
        navigator.add(header, BorderLayout.NORTH);

        navigatorCards.setBackground(NativeTheme.SURFACE);
        navigatorCards.add(createNavigatorEmpty(), "empty");
        navigatorCards.add(UiKit.scroll(connectionTree), "tree");
        navigator.add(navigatorCards, BorderLayout.CENTER);
        return navigator;
    }

    private JPanel createNavigatorEmpty() {
        JPanel empty = new JPanel();
        empty.setBackground(NativeTheme.SURFACE);
        empty.setBorder(BorderFactory.createEmptyBorder(32, 24, 32, 24));
        empty.setLayout(new BoxLayout(empty, BoxLayout.Y_AXIS));
        JLabel icon = new JLabel(LyraIcons.of(
                LyraIcons.Kind.DATABASE, 34, NativeTheme.MUTED));
        icon.setAlignmentX(CENTER_ALIGNMENT);
        JLabel title = new JLabel("尚未创建连接");
        title.setAlignmentX(CENTER_ALIGNMENT);
        title.setFont(NativeTheme.FONT_TITLE);
        title.setForeground(NativeTheme.FOREGROUND);
        JLabel note = new JLabel("连接信息与凭据仅保存在本机");
        note.setAlignmentX(CENTER_ALIGNMENT);
        note.setFont(NativeTheme.FONT_CAPTION);
        note.setForeground(NativeTheme.MUTED);
        JButton create = UiKit.button("创建第一个连接",
                LyraIcons.of(LyraIcons.Kind.ADD_DATABASE),
                UiKit.ButtonStyle.PRIMARY);
        create.setAlignmentX(CENTER_ALIGNMENT);
        create.addActionListener(event -> newConnection());
        empty.add(Box.createVerticalGlue());
        empty.add(icon);
        empty.add(Box.createVerticalStrut(14));
        empty.add(title);
        empty.add(Box.createVerticalStrut(6));
        empty.add(note);
        empty.add(Box.createVerticalStrut(18));
        empty.add(create);
        empty.add(Box.createVerticalGlue());
        return empty;
    }

    private JPanel createStatusBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setBackground(NativeTheme.SURFACE);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        1, 0, 0, 0, NativeTheme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        left.setOpaque(false);
        JLabel indicator = new JLabel("●");
        indicator.setFont(indicator.getFont().deriveFont(Font.PLAIN, 10F));
        indicator.setForeground(NativeTheme.SUCCESS);
        statusLabel.setFont(NativeTheme.FONT_CAPTION);
        statusLabel.setForeground(NativeTheme.MUTED);
        left.add(indicator);
        left.add(statusLabel);
        bar.add(left, BorderLayout.CENTER);
        JLabel mode = new JLabel("本地原生  ·  AI 可用");
        mode.setFont(NativeTheme.FONT_CAPTION_BOLD);
        mode.setForeground(NativeTheme.ACCENT_LIGHT);
        bar.add(mode, BorderLayout.EAST);
        return bar;
    }

    private JToolBar createToolbar() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBackground(NativeTheme.SURFACE);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        0, 0, 1, 0, NativeTheme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        toolbar.add(toolButton("新建连接", LyraIcons.Kind.ADD_DATABASE,
                UiKit.ButtonStyle.PRIMARY, this::newConnection));
        toolbar.add(Box.createHorizontalStrut(5));
        toolbar.add(toolButton("连接", LyraIcons.Kind.CONNECT,
                UiKit.ButtonStyle.TOOLBAR, this::connectSelected));
        toolbar.add(toolButton("断开", LyraIcons.Kind.DISCONNECT,
                UiKit.ButtonStyle.TOOLBAR, this::disconnectSelected));
        toolbar.addSeparator();
        toolbar.add(toolButton("SQL 编辑器", LyraIcons.Kind.SQL,
                UiKit.ButtonStyle.TOOLBAR, this::openSqlWorkspace));
        toolbar.add(toolButton("刷新", LyraIcons.Kind.REFRESH,
                UiKit.ButtonStyle.TOOLBAR, this::refreshSelected));
        toolbar.addSeparator();
        toolbar.add(toolButton("AI 助手", LyraIcons.Kind.AI,
                UiKit.ButtonStyle.TOOLBAR, this::openAiAssistant));
        toolbar.add(toolButton("AI 设置", LyraIcons.Kind.SETTINGS,
                UiKit.ButtonStyle.TOOLBAR, this::openAiSettings));
        toolbar.addSeparator();
        toolbar.add(toolButton("ER 图", LyraIcons.Kind.ER,
                UiKit.ButtonStyle.TOOLBAR, this::openErDiagram));
        return toolbar;
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu file = new JMenu("文件");
        file.add(item("新建连接", KeyStroke.getKeyStroke(
                KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), this::newConnection));
        file.add(item("新建 SQL 编辑器", KeyStroke.getKeyStroke(
                KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK), this::openSqlWorkspace));
        file.addSeparator();
        file.add(item("退出", null, this::shutdown));

        JMenu database = new JMenu("数据库");
        database.add(item("连接", null, this::connectSelected));
        database.add(item("断开", null, this::disconnectSelected));
        database.add(item("编辑连接", null, this::editSelected));
        database.add(item("删除连接", null, this::deleteSelected));
        database.add(item("刷新", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0),
                this::refreshSelected));

        JMenu ai = new JMenu("AI");
        ai.add(item("数据库助手", KeyStroke.getKeyStroke(
                KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                this::openAiAssistant));
        ai.add(item("Provider / API Key 设置", null, this::openAiSettings));

        JMenu tools = new JMenu("工具");
        tools.add(item("ER 关系图", null, this::openErDiagram));

        JMenu help = new JMenu("帮助");
        help.add(item("关于 LyraDB", null, () -> JOptionPane.showMessageDialog(this,
                """
                        LyraDB %s 个人版
                        原生 Java 桌面客户端（Swing）

                        架构：本地 C/S 数据库直连
                        AI：个人版可用，本地加密配置
                        企业版：独立部署的 B/S 管理平台

                        本进程不包含浏览器或 WebView。
                        """.formatted(
                        io.github.lexaquila.lyradb.desktop.NativeDesktopApplication.VERSION),
                "关于", JOptionPane.INFORMATION_MESSAGE)));

        menuBar.add(file);
        menuBar.add(database);
        menuBar.add(ai);
        menuBar.add(tools);
        menuBar.add(help);
        menuBar.add(Box.createHorizontalGlue());
        menuBar.add(UiKit.badge("个人版 · 原生",
                NativeTheme.ACCENT_LIGHT, NativeTheme.ACCENT_SOFT));
        menuBar.add(Box.createHorizontalStrut(8));
        return menuBar;
    }

    private void refreshConnections() {
        root.removeAllChildren();
        for (DesktopConnection connection : runtime.stateStore().listConnections()) {
            DefaultMutableTreeNode node =
                    new DefaultMutableTreeNode(BrowserItem.connection(connection));
            node.add(new DefaultMutableTreeNode(PLACEHOLDER));
            root.add(node);
        }
        treeModel.reload();
        connectionCount.setText("  " + root.getChildCount() + "  ");
        navigatorLayout.show(navigatorCards,
                root.getChildCount() == 0 ? "empty" : "tree");
        status("已加载 " + root.getChildCount() + " 个本地连接配置");
    }

    private void newConnection() {
        DesktopConnection saved = ConnectionDialog.show(this, runtime, null);
        if (saved != null) {
            refreshConnections();
            status("已保存连接：" + saved.getName());
        }
    }

    private void editSelected() {
        String id = selectedConnectionId();
        if (id == null) {
            status("请先选择连接");
            return;
        }
        if (runtime.connectionManager().isConnected(id)) {
            boolean transactionOpen =
                    runtime.connectionManager().inTransaction(id);
            int result = JOptionPane.showConfirmDialog(this,
                    "编辑连接前需要断开当前会话。"
                            + (transactionOpen
                            ? "\n当前存在未提交事务，将先显式回滚。" : "")
                            + "\n是否继续？",
                    "编辑连接", JOptionPane.YES_NO_OPTION);
            if (result != JOptionPane.YES_OPTION) {
                return;
            }
            runAsync("正在安全断开数据库连接…",
                    () -> {
                        runtime.connectionManager().disconnect(id);
                        return Boolean.TRUE;
                    },
                    ignored -> showEditDialog(id));
            return;
        }
        showEditDialog(id);
    }

    private void showEditDialog(String id) {
        DesktopConnection current = runtime.stateStore().findConnection(id).orElse(null);
        DesktopConnection saved = ConnectionDialog.show(this, runtime, current);
        if (saved != null) {
            refreshConnections();
            status("已更新连接：" + saved.getName());
        }
    }

    private void deleteSelected() {
        String id = selectedConnectionId();
        if (id == null) {
            status("请先选择连接");
            return;
        }
        DesktopConnection connection = runtime.stateStore().findConnection(id).orElse(null);
        if (connection == null) {
            return;
        }
        boolean transactionOpen =
                runtime.connectionManager().isConnected(id)
                        && runtime.connectionManager().inTransaction(id);
        int result = JOptionPane.showConfirmDialog(this,
                "确定删除连接配置“" + connection.getName()
                        + "”吗？\n数据库本身不会被删除。"
                        + (transactionOpen
                        ? "\n\n警告：当前存在未提交事务，删除前将显式回滚。" : ""),
                "删除连接", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result == JOptionPane.YES_OPTION) {
            runAsync("正在删除本地连接配置…",
                    () -> {
                        runtime.connectionManager().disconnect(id);
                        runtime.stateStore().deleteConnection(id);
                        return Boolean.TRUE;
                    },
                    ignored -> {
                        SqlWorkspacePanel panel = workspaceByConnection.remove(id);
                        if (panel != null) {
                            workspaces.remove(panel);
                        }
                        refreshConnections();
                    });
        }
    }

    private void connectSelected() {
        String id = selectedConnectionId();
        if (id != null) {
            connectAsync(id, () -> {
                refreshConnectionNode(id);
                status("数据库连接成功");
            });
        } else {
            status("请先选择连接");
        }
    }

    private void disconnectSelected() {
        String id = selectedConnectionId();
        if (id == null) {
            status("请先选择连接");
            return;
        }
        if (runtime.connectionManager().isConnected(id)
                && runtime.connectionManager().inTransaction(id)) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "当前连接存在未提交事务。LyraDB 将先显式回滚再断开，是否继续？",
                    "未提交事务", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }
        runAsync("正在安全回滚并断开数据库连接…",
                () -> {
                    runtime.connectionManager().disconnect(id);
                    return Boolean.TRUE;
                },
                ignored -> {
                    refreshConnectionNode(id);
                    status("连接已安全断开");
                });
    }

    private void openSqlWorkspace() {
        String id = selectedConnectionId();
        if (id == null) {
            status("请先选择数据库连接");
            return;
        }
        connectAsync(id, () -> openWorkspace(id, null));
    }

    private SqlWorkspacePanel openWorkspace(String connectionId, String initialSql) {
        SqlWorkspacePanel existing = workspaceByConnection.get(connectionId);
        if (existing == null) {
            DesktopConnection connection =
                    runtime.stateStore().findConnection(connectionId).orElseThrow();
            existing = new SqlWorkspacePanel(runtime, connectionId,
                    connection.getName(), connection.getDbType(), this::status);
            workspaceByConnection.put(connectionId, existing);
            workspaces.addTab(connection.getName() + " · SQL", existing);
        }
        if (initialSql != null) {
            existing.replaceSql(initialSql);
        }
        workspaces.setSelectedComponent(existing);
        return existing;
    }

    private void openAiSettings() {
        new AiSettingsDialog(this, runtime).setVisible(true);
    }

    private void openAiAssistant() {
        SqlWorkspacePanel workspace = activeWorkspace();
        Supplier<String> sql = workspace == null ? () -> "" : workspace::currentSql;
        String targetConnectionId = workspace == null
                ? selectedConnectionId() : workspace.connectionId();
        String targetDbType = workspace == null
                ? runtime.stateStore().findConnection(targetConnectionId)
                        .map(DesktopConnection::getDbType)
                        .orElse("未知")
                : workspace.dbType();
        Supplier<String> dbType = () -> targetDbType;
        java.util.function.Consumer<String> insert = value -> {
            if (workspace != null) {
                workspace.insertSql(value);
            } else {
                if (targetConnectionId == null) {
                    JOptionPane.showMessageDialog(this,
                            "请先选择连接并打开 SQL 编辑器。",
                            "没有 SQL 编辑器", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                connectAsync(targetConnectionId,
                        () -> openWorkspace(targetConnectionId, value));
            }
        };
        new AiAssistantDialog(this, runtime, sql, dbType, insert).setVisible(true);
    }

    private void openErDiagram() {
        String id = selectedConnectionId();
        if (id == null && activeWorkspace() != null) {
            id = activeWorkspace().connectionId();
        }
        if (id == null) {
            status("请先选择 JDBC 数据库连接");
            return;
        }
        String connectionId = id;
        connectAsync(connectionId, () -> {
            String schema = JOptionPane.showInputDialog(this,
                    "输入 Schema（留空表示当前目录）：", "");
            if (schema != null) {
                new ErDiagramDialog(this, runtime, connectionId, schema).setVisible(true);
            }
        });
    }

    private void openSelected() {
        DefaultMutableTreeNode node = selectedNode();
        if (node == null || !(node.getUserObject() instanceof BrowserItem item)) {
            return;
        }
        if (item.connectionRoot) {
            openSqlWorkspace();
            return;
        }
        if (item.node != null
                && ("TABLE".equals(item.node.getType())
                || "VIEW".equals(item.node.getType()))) {
            connectAsync(item.connectionId, () -> openDdl(item));
        } else if (item.node != null && item.node.isHasChildren()) {
            TreePath path = new TreePath(node.getPath());
            connectionTree.expandPath(path);
        }
    }

    private void openDdl(BrowserItem item) {
        String path = item.node.getPath();
        String[] parts = path == null ? new String[0] : path.split("/");
        String table = parts.length == 0 ? item.node.getName() : parts[parts.length - 1];
        String schema = namespaceFromPath(parts);
        runAsync("正在读取表 DDL…",
                () -> runtime.connectionManager().ddl(
                        item.connectionId, schema, table),
                ddl -> openWorkspace(item.connectionId, ddl));
    }

    private void refreshSelected() {
        DefaultMutableTreeNode node = selectedNode();
        if (node == null) {
            refreshConnections();
            return;
        }
        if (node.getUserObject() instanceof BrowserItem item) {
            node.removeAllChildren();
            node.add(new DefaultMutableTreeNode(PLACEHOLDER));
            treeModel.reload(node);
            if (runtime.connectionManager().isConnected(item.connectionId)) {
                loadChildren(node);
            }
        }
    }

    private void loadChildren(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof BrowserItem item)) {
            return;
        }
        connectAsync(item.connectionId, () -> runAsync(
                "正在读取数据库元数据…",
                () -> loadObjectChildren(item),
                children -> {
                    node.removeAllChildren();
                    for (TreeNode child : children) {
                        DefaultMutableTreeNode childNode =
                                new DefaultMutableTreeNode(
                                        BrowserItem.node(item.connectionId, child));
                        if (child.isHasChildren()) {
                            childNode.add(new DefaultMutableTreeNode(PLACEHOLDER));
                        }
                        node.add(childNode);
                    }
                    treeModel.reload(node);
                    connectionTree.expandPath(new TreePath(node.getPath()));
                    status("已加载 " + children.size() + " 个对象");
                }));
    }

    private List<TreeNode> loadObjectChildren(BrowserItem item) throws Exception {
        if (item.node == null) {
            return runtime.connectionManager().tree(item.connectionId, null);
        }
        String type = item.node.getType();
        if (!"TABLE".equals(type) && !"VIEW".equals(type)) {
            return runtime.connectionManager().tree(
                    item.connectionId, item.node.getPath());
        }
        String path = item.node.getPath();
        String[] parts = path == null ? new String[0] : path.split("/");
        String table = parts.length == 0
                ? item.node.getName() : parts[parts.length - 1];
        String schema = namespaceFromPath(parts);
        var columns = runtime.connectionManager().columns(
                item.connectionId, schema, table);
        List<TreeNode> result = new java.util.ArrayList<>();
        for (var column : columns) {
            TreeNode node = TreeNode.of(
                    path + "/" + column.getName(),
                    column.getName() + " : " + column.getTypeName(),
                    "COLUMN", path + "/" + column.getName());
            node.setIconType("column");
            node.setHasChildren(false);
            node.getProperties().put("primaryKey", column.isPrimaryKey());
            node.getProperties().put("nullable", column.isNullable());
            result.add(node);
        }
        return result;
    }

    private static String namespaceFromPath(String[] parts) {
        if (parts == null || parts.length < 2) {
            return null;
        }
        return String.join("/",
                java.util.Arrays.copyOf(parts, parts.length - 1));
    }

    private void connectAsync(String connectionId, Runnable after) {
        if (runtime.connectionManager().isConnected(connectionId)) {
            after.run();
            return;
        }
        runAsync("正在加载驱动并连接数据库…",
                () -> {
                    runtime.connectionManager().connect(connectionId);
                    return Boolean.TRUE;
                },
                ignored -> {
                    refreshConnectionNode(connectionId);
                    after.run();
                });
    }

    private <T> void runAsync(String busyMessage,
            CheckedSupplier<T> action, java.util.function.Consumer<T> success) {
        status(busyMessage);
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return action.get();
            }

            @Override
            protected void done() {
                try {
                    success.accept(get());
                } catch (Exception exception) {
                    Throwable cause = rootCause(exception);
                    status("操作失败：" + cause.getMessage());
                    JOptionPane.showMessageDialog(MainFrame.this,
                            cause.getMessage(), "操作失败", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void refreshConnectionNode(String connectionId) {
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode node =
                    (DefaultMutableTreeNode) root.getChildAt(i);
            if (node.getUserObject() instanceof BrowserItem item
                    && item.connectionId.equals(connectionId)) {
                node.removeAllChildren();
                node.add(new DefaultMutableTreeNode(PLACEHOLDER));
                treeModel.reload(node);
                return;
            }
        }
    }

    private boolean needsLoading(DefaultMutableTreeNode node) {
        return node.getChildCount() == 1
                && PLACEHOLDER.equals(((DefaultMutableTreeNode)
                node.getChildAt(0)).getUserObject());
    }

    private void showTreePopup(int x, int y) {
        JPopupMenu popup = new JPopupMenu();
        popup.add(item("打开 SQL 编辑器", null, this::openSqlWorkspace));
        popup.add(item("连接", null, this::connectSelected));
        popup.add(item("断开", null, this::disconnectSelected));
        popup.add(item("刷新", null, this::refreshSelected));
        popup.addSeparator();
        popup.add(item("编辑连接", null, this::editSelected));
        popup.add(item("删除连接", null, this::deleteSelected));
        popup.show(connectionTree, x, y);
    }

    private DefaultMutableTreeNode selectedNode() {
        TreePath path = connectionTree.getSelectionPath();
        return path == null ? null
                : (DefaultMutableTreeNode) path.getLastPathComponent();
    }

    private String selectedConnectionId() {
        DefaultMutableTreeNode node = selectedNode();
        if (node != null && node.getUserObject() instanceof BrowserItem item) {
            return item.connectionId;
        }
        SqlWorkspacePanel workspace = activeWorkspace();
        return workspace == null ? null : workspace.connectionId();
    }

    private SqlWorkspacePanel activeWorkspace() {
        return workspaces.getSelectedComponent() instanceof SqlWorkspacePanel workspace
                ? workspace : null;
    }

    private void status(String message) {
        statusLabel.setText(message);
    }

    private void shutdown() {
        if (shuttingDown) {
            return;
        }
        int transactionCount = uncommittedTransactionCount();
        if (transactionCount > 0) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "检测到 " + transactionCount + " 个未提交事务。"
                            + "\n退出前将逐一显式回滚并安全断开，是否继续？",
                    "退出 LyraDB", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }
        shuttingDown = true;
        setEnabled(false);
        status("正在安全回滚事务并关闭连接…");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                runtime.close();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    dispose();
                    System.exit(0);
                } catch (Exception exception) {
                    shuttingDown = false;
                    setEnabled(true);
                    Throwable cause = rootCause(exception);
                    status("退出失败：" + cause.getMessage());
                    JOptionPane.showMessageDialog(MainFrame.this,
                            cause.getMessage(), "退出失败",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private int uncommittedTransactionCount() {
        int count = 0;
        for (DesktopConnection connection : runtime.stateStore().listConnections()) {
            if (runtime.connectionManager().isConnected(connection.getId())
                    && runtime.connectionManager().inTransaction(connection.getId())) {
                count++;
            }
        }
        return count;
    }

    private static JButton toolButton(String text, LyraIcons.Kind icon,
            UiKit.ButtonStyle style, Runnable action) {
        JButton button = UiKit.button(text, LyraIcons.of(icon), style);
        button.addActionListener(event -> action.run());
        return button;
    }

    private static JMenuItem item(String text, KeyStroke keyStroke, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        if (keyStroke != null) {
            item.setAccelerator(keyStroke);
        }
        item.addActionListener(event -> action.run());
        return item;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    static final class BrowserItem {
        final String connectionId;
        final DesktopConnection connection;
        final TreeNode node;
        final boolean connectionRoot;

        private BrowserItem(String connectionId, DesktopConnection connection,
                TreeNode node, boolean connectionRoot) {
            this.connectionId = connectionId;
            this.connection = connection;
            this.node = node;
            this.connectionRoot = connectionRoot;
        }

        static BrowserItem connection(DesktopConnection connection) {
            return new BrowserItem(connection.getId(), connection, null, true);
        }

        static BrowserItem node(String connectionId, TreeNode node) {
            return new BrowserItem(connectionId, null, node, false);
        }

        @Override
        public String toString() {
            return connectionRoot ? connection.getName()
                    + "  [" + connection.getDbType() + "]"
                    : node.getName();
        }
    }
}
