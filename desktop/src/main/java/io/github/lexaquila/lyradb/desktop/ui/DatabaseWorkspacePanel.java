package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.DesktopRuntime;
import io.github.lexaquila.lyradb.desktop.ai.AiTableSearchSupport;
import io.github.lexaquila.lyradb.desktop.ai.AiTableSearchSupport.CatalogEntry;
import io.github.lexaquila.lyradb.desktop.ai.AiTableSearchSupport.Prompt;
import io.github.lexaquila.lyradb.desktop.ai.AiTableSearchSupport.Recommendation;
import io.github.lexaquila.lyradb.desktop.ai.AiTask;
import io.github.lexaquila.lyradb.desktop.model.AiProfile;
import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;
import io.github.lexaquila.lyradb.model.dto.TreeNode;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 数据库工作区：展示安全的连接摘要，并渐进加载库、Schema、表和视图目录。
 */
final class DatabaseWorkspacePanel extends JPanel {

    private static final int MAX_OBJECTS = 2_500;
    private static final int MAX_DEPTH = 3;
    private static final Set<String> EXPANDABLE_TYPES = Set.of(
            "DATABASE", "SCHEMA", "PROJECT", "CATALOG", "DB_INDEX");
    private static final Set<String> OPENABLE_TYPES = Set.of(
            "TABLE", "VIEW", "COLLECTION");

    private final DesktopRuntime runtime;
    private final DesktopConnection connection;
    private final Consumer<String> statusSink;
    private final Consumer<TreeNode> tableOpener;
    private final Runnable sqlOpener;
    private final Runnable erOpener;
    private final ObjectTableModel model = new ObjectTableModel();
    private final JTable objectTable = new JTable(model);
    private final TableRowSorter<ObjectTableModel> sorter =
            new TableRowSorter<>(model);
    private final JTextField search = new JTextField();
    private final JLabel state = new JLabel("准备读取数据库目录");
    private final JLabel count = UiKit.badge(
            "0 个对象", NativeTheme.ACCENT_LIGHT, NativeTheme.ACCENT_SOFT);
    private final JProgressBar progress = new JProgressBar();
    private final JButton refresh = UiKit.button(
            "刷新目录", LyraIcons.of(LyraIcons.Kind.REFRESH),
            UiKit.ButtonStyle.SECONDARY);
    private final JButton aiFind = UiKit.button(
            "AI 找表", LyraIcons.of(LyraIcons.Kind.AI),
            UiKit.ButtonStyle.SECONDARY);
    private SwingWorker<LoadSummary, DatabaseObject> worker;
    private SwingWorker<List<Recommendation>, Void> aiWorker;
    private long generation;
    private long aiGeneration;
    private boolean catalogLoading;
    private boolean aiSearching;

    DatabaseWorkspacePanel(
            DesktopRuntime runtime,
            DesktopConnection connection,
            Consumer<String> statusSink,
            Consumer<TreeNode> tableOpener,
            Runnable sqlOpener,
            Runnable erOpener) {
        super(new BorderLayout(0, 10));
        this.runtime = runtime;
        this.connection = connection;
        this.statusSink = statusSink;
        this.tableOpener = tableOpener;
        this.sqlOpener = sqlOpener;
        this.erOpener = erOpener;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(createHeader(), BorderLayout.NORTH);
        add(createCatalog(), BorderLayout.CENTER);
        refresh.addActionListener(event -> refresh());
        aiFind.addActionListener(event -> findWithAi());
        refresh();
    }

    String connectionId() {
        return connection.getId();
    }

    void disposeWorkspace() {
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
        cancelAiSearch(false);
    }

    private JPanel createHeader() {
        JPanel header = UiKit.glass(new BorderLayout(16, 0), 14);
        header.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 14));

        JLabel icon = new JLabel(LyraIcons.databaseEngine(
                connection.getDbType(), 42,
                runtime.connectionManager().isConnected(connection.getId())));
        header.add(icon, BorderLayout.WEST);

        JPanel identity = new JPanel();
        identity.setOpaque(false);
        identity.setLayout(new javax.swing.BoxLayout(
                identity, javax.swing.BoxLayout.Y_AXIS));
        JLabel title = new JLabel(connection.getName());
        title.setFont(NativeTheme.FONT_TITLE.deriveFont(Font.BOLD, 18F));
        title.setForeground(NativeTheme.FOREGROUND);
        JLabel subtitle = new JLabel(connectionSummary());
        subtitle.setFont(NativeTheme.FONT_CAPTION);
        subtitle.setForeground(NativeTheme.MUTED);
        identity.add(title);
        identity.add(javax.swing.Box.createVerticalStrut(4));
        identity.add(subtitle);
        identity.add(javax.swing.Box.createVerticalStrut(8));
        JPanel badges = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        badges.setOpaque(false);
        badges.add(UiKit.badge(connection.getDbType(),
                NativeTheme.ACCENT_LIGHT, NativeTheme.ACCENT_SOFT));
        badges.add(UiKit.badge("已连接",
                NativeTheme.SUCCESS, NativeTheme.SURFACE_ALT));
        badges.add(count);
        identity.add(badges);
        header.add(identity, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(
                FlowLayout.RIGHT, 7, 0));
        actions.setOpaque(false);
        JButton sql = UiKit.button(
                "打开 SQL", LyraIcons.of(LyraIcons.Kind.SQL),
                UiKit.ButtonStyle.PRIMARY);
        sql.addActionListener(event -> sqlOpener.run());
        JButton er = UiKit.button(
                "ER 图", LyraIcons.of(LyraIcons.Kind.ER),
                UiKit.ButtonStyle.SECONDARY);
        er.addActionListener(event -> erOpener.run());
        actions.add(refresh);
        actions.add(er);
        actions.add(sql);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private JPanel createCatalog() {
        JPanel catalog = UiKit.glass(new BorderLayout(), 14);

        JPanel toolbar = new JPanel(new BorderLayout(12, 0));
        toolbar.setOpaque(false);
        toolbar.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JPanel status = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        status.setOpaque(false);
        state.setFont(NativeTheme.FONT_CAPTION);
        state.setForeground(NativeTheme.MUTED);
        progress.setIndeterminate(true);
        progress.setPreferredSize(new Dimension(76, 5));
        progress.setBorderPainted(false);
        progress.setVisible(false);
        status.add(progress);
        status.add(state);
        toolbar.add(status, BorderLayout.WEST);

        search.putClientProperty("JTextField.placeholderText",
                "搜索对象名、中文注释、所属库 / Schema 或完整路径");
        search.putClientProperty("JTextField.leadingIcon",
                LyraIcons.of(LyraIcons.Kind.SEARCH, NativeTheme.MUTED));
        search.putClientProperty("JTextField.showClearButton", true);
        search.setPreferredSize(new Dimension(400, 34));
        search.setToolTipText("输入业务词可即时搜索；按 Enter 使用 AI 智能推荐");
        search.getDocument().addDocumentListener(
                new javax.swing.event.DocumentListener() {
                    @Override public void insertUpdate(
                            javax.swing.event.DocumentEvent event) {
                        applyFilter();
                    }
                    @Override public void removeUpdate(
                            javax.swing.event.DocumentEvent event) {
                        applyFilter();
                    }
                    @Override public void changedUpdate(
                            javax.swing.event.DocumentEvent event) {
                        applyFilter();
                    }
                });
        search.addActionListener(event -> findWithAi());
        aiFind.setToolTipText(
                "仅发送当前已加载的目录元数据，不发送业务明细；快捷键 Alt+I");
        aiFind.setMnemonic(java.awt.event.KeyEvent.VK_I);
        JPanel searchActions = new JPanel(new FlowLayout(
                FlowLayout.RIGHT, 8, 0));
        searchActions.setOpaque(false);
        searchActions.add(search);
        searchActions.add(aiFind);
        toolbar.add(searchActions, BorderLayout.EAST);
        catalog.add(toolbar, BorderLayout.NORTH);

        objectTable.setRowSorter(sorter);
        objectTable.setFillsViewportHeight(true);
        objectTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        objectTable.setRowHeight(32);
        objectTable.setShowVerticalLines(false);
        objectTable.getTableHeader().setReorderingAllowed(false);
        objectTable.getColumnModel().getColumn(0).setPreferredWidth(270);
        objectTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        objectTable.getColumnModel().getColumn(2).setPreferredWidth(260);
        objectTable.getColumnModel().getColumn(3).setPreferredWidth(260);
        objectTable.getColumnModel().getColumn(4).setPreferredWidth(310);
        objectTable.getColumnModel().getColumn(5).setPreferredWidth(360);
        objectTable.getColumnModel().getColumn(1)
                .setCellRenderer(new TypeRenderer());
        objectTable.getColumnModel().getColumn(3)
                .setCellRenderer(new MetadataCommentRenderer());
        objectTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    openSelected();
                }
            }
        });
        objectTable.getInputMap().put(
                javax.swing.KeyStroke.getKeyStroke(
                        java.awt.event.KeyEvent.VK_ENTER, 0),
                "openDatabaseObject");
        objectTable.getActionMap().put(
                "openDatabaseObject", new javax.swing.AbstractAction() {
                    @Override
                    public void actionPerformed(
                            java.awt.event.ActionEvent event) {
                        openSelected();
                    }
                });

        JScrollPane scroll = UiKit.scroll(objectTable);
        scroll.setBorder(BorderFactory.createMatteBorder(
                1, 0, 0, 0, NativeTheme.BORDER_SOFT));
        catalog.add(scroll, BorderLayout.CENTER);
        return catalog;
    }

    void refresh() {
        long request = ++generation;
        cancelAiSearch(false);
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
        runtime.connectionManager()
                .invalidateMetadata(connection.getId());
        model.clear();
        count.setText("  0 个对象  ");
        setLoading(true, "正在读取数据库目录，已加载结果会立即显示…");
        statusSink.accept("正在渐进加载数据库工作区："
                + connection.getName());
        long started = System.currentTimeMillis();

        worker = new SwingWorker<>() {
            @Override
            protected LoadSummary doInBackground() throws Exception {
                Set<String> visited = new HashSet<>();
                int loaded = loadLevel(null, "", 0, visited);
                return new LoadSummary(
                        loaded, loaded >= MAX_OBJECTS,
                        System.currentTimeMillis() - started);
            }

            private int loadLevel(
                    String parentPath, String parentLabel, int depth,
                    Set<String> visited) throws Exception {
                if (isCancelled() || depth > MAX_DEPTH
                        || visited.size() >= MAX_OBJECTS) {
                    return visited.size();
                }
                List<TreeNode> nodes = runtime.connectionManager().tree(
                        connection.getId(), parentPath);
                for (TreeNode node : nodes) {
                    if (isCancelled() || visited.size() >= MAX_OBJECTS) {
                        break;
                    }
                    if (node == null || node.getName() == null
                            || node.getName().isBlank()) {
                        continue;
                    }
                    String nodePath = node.getPath();
                    if (nodePath == null || nodePath.isBlank()) {
                        nodePath = parentPath == null
                                || parentPath.isBlank()
                                ? node.getName()
                                : parentPath + "/" + node.getName();
                        node.setPath(nodePath);
                    }
                    String key = node.getType() + ":" + nodePath;
                    if (!visited.add(key)) {
                        continue;
                    }
                    String namespace = parentLabel == null
                            ? "" : parentLabel;
                    String displayNamespace = namespace.isBlank()
                            ? fallbackNamespace(node) : namespace;
                    publish(new DatabaseObject(node, displayNamespace));
                    String type = normalizeType(node.getType());
                    if (node.isHasChildren()
                            && depth < MAX_DEPTH
                            && EXPANDABLE_TYPES.contains(type)) {
                        String nextLabel = namespace.isBlank()
                                ? node.getName()
                                : namespace + "." + node.getName();
                        loadLevel(nodePath, nextLabel,
                                depth + 1, visited);
                    }
                }
                return visited.size();
            }

            @Override
            protected void process(List<DatabaseObject> chunks) {
                if (request != generation) {
                    return;
                }
                model.addAll(chunks);
                count.setText("  " + model.getRowCount()
                        + " 个对象  ");
                state.setText("正在加载…已发现 "
                        + model.getRowCount() + " 个对象");
            }

            @Override
            protected void done() {
                if (isCancelled() || request != generation) {
                    return;
                }
                try {
                    LoadSummary summary = get();
                    String suffix = summary.truncated()
                            ? "，已达到安全上限" : "";
                    setLoading(false, "目录加载完成："
                            + summary.count() + " 个对象，"
                            + summary.elapsedMs() + " ms" + suffix);
                    statusSink.accept("数据库工作区已加载："
                            + connection.getName() + " · "
                            + summary.count() + " 个对象");
                } catch (Exception exception) {
                    setLoading(false, "目录加载失败："
                            + safeMessage(exception));
                    statusSink.accept("数据库目录加载失败："
                            + connection.getName());
                }
            }
        };
        worker.execute();
    }

    private void applyFilter() {
        cancelAiSearch(true);
        model.clearRecommendations();
        sorter.setSortKeys(List.of());
        applyPlainFilter();
    }

    private void applyPlainFilter() {
        String keyword = search.getText().trim();
        if (keyword.isEmpty()) {
            sorter.setRowFilter(null);
            if (!catalogLoading && !aiSearching) {
                state.setText("目录加载完成，可按对象名、注释、所属库或路径搜索");
                state.setForeground(NativeTheme.MUTED);
            }
            return;
        }
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(
                    Entry<? extends ObjectTableModel,
                            ? extends Integer> entry) {
                DatabaseObject value = model.objectAt(
                        entry.getIdentifier());
                return AiTableSearchSupport.matches(
                        keyword, value.catalogEntry());
            }
        });
        if (!catalogLoading && !aiSearching) {
            state.setText("本地即时搜索：匹配 "
                    + objectTable.getRowCount() + " 个对象");
            state.setForeground(NativeTheme.MUTED);
        }
    }

    private void findWithAi() {
        String query = search.getText().trim();
        if (query.isEmpty()) {
            state.setText("请先输入表名、中文业务名称或业务描述");
            state.setForeground(NativeTheme.WARNING);
            search.requestFocusInWindow();
            return;
        }
        if (catalogLoading) {
            state.setText("目录仍在加载，请稍候再使用 AI 找表");
            state.setForeground(NativeTheme.WARNING);
            return;
        }

        List<CatalogEntry> entries = model.catalogEntries();
        if (entries.isEmpty()) {
            state.setText("当前已加载目录中没有可推荐的表或视图");
            state.setForeground(NativeTheme.WARNING);
            return;
        }
        List<Recommendation> local =
                AiTableSearchSupport.localRecommendations(
                        query, entries, 30);
        AiProfile profile = runtime.stateStore().getAiProfile();
        if (!profile.isConfigured()) {
            showLocalFallback(local,
                    "AI 模型未配置，已保留本地目录搜索；可在“模型设置”中配置");
            return;
        }

        Prompt prompt = AiTableSearchSupport.preparePrompt(query, entries);
        if (prompt.candidates().isEmpty()) {
            showLocalFallback(local, "没有可发送给 AI 的目录候选，已保留本地搜索");
            return;
        }
        cancelAiSearch(false);
        long request = ++aiGeneration;
        if (!local.isEmpty()) {
            showRecommendations(local);
        }
        setAiSearching(true, "AI 正在基于 "
                + prompt.candidates().size()
                + " 个目录候选推荐表…仅发送元数据，不发送业务明细");
        statusSink.accept("AI 正在智能找表：" + connection.getName());

        aiWorker = new SwingWorker<>() {
            @Override
            protected List<Recommendation> doInBackground()
                    throws Exception {
                String response = runtime.aiClient().complete(
                        profile,
                        AiTask.FIND_TABLE,
                        AiTableSearchSupport.requestText(query, prompt),
                        connection.getDbType(),
                        prompt.metadataContext(),
                        "");
                return AiTableSearchSupport.parseRecommendations(
                        response, prompt);
            }

            @Override
            protected void done() {
                if (isCancelled() || request != aiGeneration) {
                    return;
                }
                aiWorker = null;
                try {
                    List<Recommendation> recommendations = get();
                    if (recommendations.isEmpty()) {
                        showLocalFallback(local,
                                "AI 未返回可匹配的目录对象，已降级为本地搜索");
                        statusSink.accept("AI 找表无有效推荐，已使用本地结果");
                        return;
                    }
                    showRecommendations(recommendations);
                    String truncated = prompt.truncated()
                            ? "；候选目录已在本地按相关度安全截断" : "";
                    setAiSearching(false, "AI 推荐完成："
                            + recommendations.size() + " 个对象" + truncated);
                    state.setForeground(NativeTheme.SUCCESS);
                    statusSink.accept("AI 找表完成：推荐 "
                            + recommendations.size() + " 个对象");
                } catch (Exception exception) {
                    showLocalFallback(local, "AI 请求失败（"
                            + shortMessage(exception)
                            + "），已降级为本地搜索");
                    statusSink.accept("AI 找表失败，已保留本地目录搜索");
                }
            }
        };
        aiWorker.execute();
    }

    private void showLocalFallback(
            List<Recommendation> local, String message) {
        if (local == null || local.isEmpty()) {
            model.clearRecommendations();
            sorter.setSortKeys(List.of());
            applyPlainFilter();
        } else {
            showRecommendations(local);
        }
        setAiSearching(false, message);
        state.setForeground(NativeTheme.WARNING);
    }

    private void showRecommendations(
            List<Recommendation> recommendations) {
        model.setRecommendations(recommendations);
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(
                    Entry<? extends ObjectTableModel,
                            ? extends Integer> entry) {
                return model.hasRecommendation(entry.getIdentifier());
            }
        });
        sorter.setSortKeys(List.of(
                new RowSorter.SortKey(5, SortOrder.ASCENDING)));
        if (objectTable.getRowCount() > 0) {
            objectTable.setRowSelectionInterval(0, 0);
        }
    }

    private void cancelAiSearch(boolean showLocalStatus) {
        boolean running = aiWorker != null && !aiWorker.isDone();
        ++aiGeneration;
        if (running) {
            aiWorker.cancel(true);
        }
        aiWorker = null;
        if (running) {
            setAiSearching(false, showLocalStatus
                    ? "已停止 AI 推荐，当前为本地即时搜索"
                    : "AI 推荐已取消");
        }
    }

    private void setAiSearching(boolean searching, String message) {
        aiSearching = searching;
        progress.setVisible(searching || catalogLoading);
        refresh.setEnabled(!searching && !catalogLoading);
        aiFind.setEnabled(!searching && !catalogLoading);
        aiFind.setText(searching ? "推荐中…" : "AI 找表");
        state.setText(message);
        state.setForeground(searching
                ? NativeTheme.WARNING : NativeTheme.MUTED);
    }

    private void openSelected() {
        int viewRow = objectTable.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        DatabaseObject selected = model.objectAt(
                objectTable.convertRowIndexToModel(viewRow));
        if (OPENABLE_TYPES.contains(
                normalizeType(selected.node().getType()))) {
            tableOpener.accept(selected.node());
        }
    }

    private void setLoading(boolean loading, String message) {
        catalogLoading = loading;
        progress.setVisible(loading || aiSearching);
        refresh.setEnabled(!loading && !aiSearching);
        aiFind.setEnabled(!loading && !aiSearching);
        state.setText(message);
        state.setForeground(loading
                ? NativeTheme.WARNING : NativeTheme.MUTED);
    }

    private String fallbackNamespace(TreeNode node) {
        Map<String, Object> properties = node.getProperties();
        if (properties != null) {
            String catalog = property(properties, "catalog");
            String schema = property(properties, "schema");
            if (!catalog.isBlank() && !schema.isBlank()) {
                return catalog + "." + schema;
            }
            if (!schema.isBlank()) {
                return schema;
            }
            if (!catalog.isBlank()) {
                return catalog;
            }
        }
        Map<String, Object> params = connection.getParams();
        for (String key : List.of(
                "project", "database", "serviceName", "databaseIndex")) {
            String value = property(params, key);
            if (!value.isBlank()
                    && !connection.getCredentialKeys().contains(key)) {
                return value;
            }
        }
        return "";
    }

    private static String property(
            Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private String connectionSummary() {
        List<String> values = new ArrayList<>();
        Map<String, Object> params = connection.getParams();
        for (String key : List.of(
                "host", "port", "database", "serviceName",
                "endpoint", "project", "filePath", "databaseIndex")) {
            Object value = params.get(key);
            if (value != null && !value.toString().isBlank()
                    && !connection.getCredentialKeys().contains(key)) {
                values.add(key + "=" + value);
            }
        }
        if (!connection.getGroup().isBlank()) {
            values.add("分组=" + connection.getGroup());
        }
        return values.isEmpty()
                ? "连接参数由本机加密保存，敏感字段不会在工作区展示"
                : String.join("  ·  ", values);
    }

    private static String normalizeType(String value) {
        return value == null ? ""
                : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String safeMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null
                ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static String shortMessage(Throwable throwable) {
        String message = safeMessage(throwable)
                .replaceAll("\\s+", " ").trim();
        return message.length() <= 120
                ? message : message.substring(0, 120) + "…";
    }

    private record LoadSummary(
            int count, boolean truncated, long elapsedMs) {
    }

    private record DatabaseObject(TreeNode node, String namespace) {
        private CatalogEntry catalogEntry() {
            return new CatalogEntry(
                    node.getName(), node.getType(), namespace,
                    nodeComment(node), node.getPath());
        }
    }

    static String nodeComment(TreeNode node) {
        if (node == null || node.getProperties() == null) {
            return "";
        }
        for (String key : List.of("remarks", "comment", "description")) {
            Object value = node.getProperties().get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString().trim();
            }
        }
        return "";
    }

    static String nodeCommentDisplay(TreeNode node) {
        String comment = nodeComment(node);
        if (!comment.isBlank()) {
            return comment;
        }
        String remarksStatus = nodeProperty(node, "remarksStatus")
                .toUpperCase(Locale.ROOT);
        String reason = nodeProperty(node, "metadataReason");
        if ("EMPTY".equals(remarksStatus)) {
            return "未设置注释";
        }
        if ("UNAVAILABLE".equals(remarksStatus)) {
            return reason.isBlank()
                    ? "注释元数据不可用"
                    : "注释元数据不可用 · " + reason;
        }
        if ("AVAILABLE".equals(remarksStatus)) {
            return "注释状态异常：标记可用但内容为空";
        }
        if (!reason.isBlank()) {
            return "注释未获取 · " + reason;
        }
        String status = nodeProperty(node, "metadataStatus");
        String source = nodeProperty(node, "metadataSource");
        if (!status.isBlank() && !source.isBlank()) {
            return "注释未返回（" + source + " · " + status + "）";
        }
        if (!status.isBlank()) {
            return "注释未返回（" + status + "）";
        }
        if (!source.isBlank()) {
            return "注释未返回（" + source + "）";
        }
        return "注释尚未获取";
    }

    static String nodeProperty(TreeNode node, String key) {
        if (node == null || node.getProperties() == null) {
            return "";
        }
        Object value = node.getProperties().get(key);
        return value == null ? "" : value.toString().trim();
    }

    private static final class ObjectTableModel
            extends AbstractTableModel {
        private static final List<String> COLUMNS = List.of(
                "对象名", "类型", "所属库 / Schema", "注释", "完整路径",
                "推荐理由");
        private final List<DatabaseObject> objects = new ArrayList<>();
        private final Map<String, RecommendationDisplay> recommendations =
                new LinkedHashMap<>();

        private void clear() {
            int previous = objects.size();
            objects.clear();
            recommendations.clear();
            if (previous > 0) {
                fireTableRowsDeleted(0, previous - 1);
            }
        }

        private void addAll(List<DatabaseObject> values) {
            if (values == null || values.isEmpty()) {
                return;
            }
            int start = objects.size();
            objects.addAll(values);
            fireTableRowsInserted(start, objects.size() - 1);
        }

        private DatabaseObject objectAt(int row) {
            return objects.get(row);
        }

        private List<CatalogEntry> catalogEntries() {
            return objects.stream()
                    .filter(object -> OPENABLE_TYPES.contains(
                            normalizeType(object.node().getType())))
                    .map(DatabaseObject::catalogEntry)
                    .toList();
        }

        private void clearRecommendations() {
            if (recommendations.isEmpty()) {
                return;
            }
            recommendations.clear();
            fireTableDataChanged();
        }

        private void setRecommendations(List<Recommendation> values) {
            recommendations.clear();
            if (values != null) {
                int rank = 1;
                for (Recommendation recommendation : values) {
                    if (recommendation == null
                            || recommendations.containsKey(
                            recommendation.entry().key())) {
                        continue;
                    }
                    recommendations.put(recommendation.entry().key(),
                            new RecommendationDisplay(
                                    rank++, recommendation.reason()));
                }
            }
            fireTableDataChanged();
        }

        private boolean hasRecommendation(int row) {
            return recommendations.containsKey(
                    objects.get(row).catalogEntry().key());
        }

        @Override public int getRowCount() {
            return objects.size();
        }

        @Override public int getColumnCount() {
            return COLUMNS.size();
        }

        @Override public String getColumnName(int column) {
            return COLUMNS.get(column);
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 5
                    ? RecommendationDisplay.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            DatabaseObject object = objects.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> object.node().getName();
                case 1 -> object.node().getType();
                case 2 -> object.namespace().isBlank()
                        ? "当前连接" : object.namespace();
                case 3 -> nodeCommentDisplay(object.node());
                case 4 -> object.node().getPath();
                case 5 -> recommendations.getOrDefault(
                        object.catalogEntry().key(),
                        RecommendationDisplay.EMPTY);
                default -> "";
            };
        }
    }

    private record RecommendationDisplay(
            int rank, String reason)
            implements Comparable<RecommendationDisplay> {

        private static final RecommendationDisplay EMPTY =
                new RecommendationDisplay(Integer.MAX_VALUE, "");

        @Override
        public int compareTo(RecommendationDisplay other) {
            return Comparator.comparingInt(RecommendationDisplay::rank)
                    .thenComparing(RecommendationDisplay::reason)
                    .compare(this, other);
        }

        @Override
        public String toString() {
            return reason == null || reason.isBlank()
                    ? "" : rank + ". " + reason;
        }
    }

    private static final class TypeRenderer
            extends DefaultTableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected,
                boolean focused, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                    table, value, selected, focused, row, column);
            String type = value == null ? "" : value.toString();
            label.setText(switch (normalizeType(type)) {
                case "DATABASE" -> "数据库";
                case "SCHEMA" -> "Schema";
                case "TABLE" -> "表";
                case "VIEW" -> "视图";
                case "COLLECTION" -> "集合";
                default -> type;
            });
            label.setIcon(LyraIcons.treeNode(
                    type, Map.of(), 15));
            label.setFont(label.getFont().deriveFont(Font.BOLD, 11F));
            return label;
        }
    }

    private static final class MetadataCommentRenderer
            extends DefaultTableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected,
                boolean focused, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                    table, value, selected, focused, row, column);
            int modelRow = table.convertRowIndexToModel(row);
            ObjectTableModel tableModel = (ObjectTableModel) table.getModel();
            TreeNode node = tableModel.objectAt(modelRow).node();
            List<String> details = new ArrayList<>();
            String source = nodeProperty(node, "metadataSource");
            String status = nodeProperty(node, "metadataStatus");
            String remarksStatus = nodeProperty(node, "remarksStatus");
            String reason = nodeProperty(node, "metadataReason");
            if (!source.isBlank()) {
                details.add("来源：" + source);
            }
            if (!status.isBlank()) {
                details.add("状态：" + status);
            }
            if (!remarksStatus.isBlank()) {
                details.add("注释状态：" + remarksStatus);
            }
            if (!reason.isBlank()) {
                details.add("说明：" + reason);
            }
            label.setToolTipText(details.isEmpty()
                    ? String.valueOf(value)
                    : String.join(" · ", details));
            return label;
        }
    }
}
