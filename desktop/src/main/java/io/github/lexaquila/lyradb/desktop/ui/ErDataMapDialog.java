package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.DesktopRuntime;
import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;
import io.github.lexaquila.lyradb.model.dto.TreeNode;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 搜索优先、按表加载的 ER / MaxCompute 数据地图。
 *
 * <p>一个画布明确绑定一个数据源和命名空间。普通数据库自动展示所选范围内
 * 的全部表与视图，并先绘制结构骨架、再在后台补齐字段和真实外键；MaxCompute
 * 使用用户选择的根表，通过 DataWorks OpenAPI 探查真实表血缘或字段血缘，
 * 不根据名称或 SQL 文本生成推测连线。</p>
 */
final class ErDataMapDialog extends JDialog {

    private static final String CURRENT_SCOPE = "当前命名空间";
    private static final Set<String> SUPPORTED_OBJECT_TYPES =
            Set.of("TABLE", "VIEW");

    private static final Set<String> SCOPE_OBJECT_TYPES = Set.of(
            "DATABASE", "SCHEMA", "PROJECT", "CATALOG", "FOLDER");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final DesktopRuntime runtime;
    private final JComboBox<ConnectionChoice> sourceSelector =
            new JComboBox<>();
    private final JComboBox<ScopeChoice> scopeSelector = new JComboBox<>();
    private final JComboBox<FieldDisplayMode> fieldDisplayMode =
            new JComboBox<>(FieldDisplayMode.values());
    private final JTextField graphFilter = new JTextField();
    private final JLabel status = new JLabel("正在读取数据源范围…");
    private final ErDiagramDialog.GraphPanel graph =
            new ErDiagramDialog.GraphPanel();
    private final JScrollPane scroll = UiKit.scroll(graph);
    private final JButton chooseTables = UiKit.button(
            "选择表", LyraIcons.of(LyraIcons.Kind.SEARCH),
            UiKit.ButtonStyle.PRIMARY);
    private final JButton clearTables = UiKit.button(
            "清空", LyraIcons.of(LyraIcons.Kind.CLOSE),
            UiKit.ButtonStyle.GHOST);
    private final JComboBox<DataWorksLineageService.EntityKind> lineageKind =
            new JComboBox<>(DataWorksLineageService.EntityKind.values());
    private final JComboBox<DataWorksLineageService.Direction>
            lineageDirection = new JComboBox<>(
            DataWorksLineageService.Direction.values());
    private final JComboBox<DataWorksLineageService.ProbePolicy>
            lineagePolicy = new JComboBox<>(
            DataWorksLineageService.ProbePolicy.values());
    private final JComboBox<ColumnChoice> lineageColumn = new JComboBox<>();
    private final JPanel lineageBar = new JPanel(new FlowLayout(
            FlowLayout.LEFT, 8, 0));
    private final JLabel lineageKindLabel = label("血缘");
    private final JLabel lineageDirectionLabel = label("方向");
    private final JLabel lineagePolicyLabel = label("探查时机");
    private final JLabel lineageColumnLabel = label("根字段");
    private final JButton probeLineage = UiKit.button(
            "探查血缘", LyraIcons.of(LyraIcons.Kind.REFRESH),
            UiKit.ButtonStyle.PRIMARY);
    private final JButton refresh = UiKit.button(
            "刷新", LyraIcons.of(LyraIcons.Kind.REFRESH),
            UiKit.ButtonStyle.SECONDARY);
    private final JButton export = UiKit.button(
            "导出 PNG", LyraIcons.of(LyraIcons.Kind.EXPORT),
            UiKit.ButtonStyle.SECONDARY);
    private final Map<String, ErDiagramMetadataLoader.TableChoice>
            selectedTables = new LinkedHashMap<>();
    private final Map<String, ErDiagramMetadataLoader.TableMetadata>
            metadataCache = new ConcurrentHashMap<>();

    private SwingWorker<?, ?> worker;
    private Timer periodicProbeTimer;
    private long generation;
    private boolean updatingSelectors;
    private boolean updatingLineageControls;
    private ErDiagramDialog.SchemaGraph currentGraph = emptyGraph();

    ErDataMapDialog(
            JFrame owner, DesktopRuntime runtime,
            String initialConnectionId) {
        super(owner, "ER 关系图", false);
        this.runtime = runtime;
        populateSources(initialConnectionId);
        buildUi();
        setMinimumSize(new Dimension(960, 660));
        setSize(1280, 840);
        setLocationRelativeTo(owner);
        loadScopes(null, true);
    }

    private void populateSources(String initialConnectionId) {
        ConnectionChoice selected = null;
        for (DesktopConnection connection :
                runtime.stateStore().listConnections()) {
            ConnectionChoice choice = new ConnectionChoice(connection);
            sourceSelector.addItem(choice);
            if (connection.getId().equals(initialConnectionId)) {
                selected = choice;
            }
        }
        if (selected != null) {
            sourceSelector.setSelectedItem(selected);
        }
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(NativeTheme.BACKGROUND);
        setContentPane(root);

        JPanel top = UiKit.glass(new BorderLayout(12, 8), 0);
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JPanel selectors = new JPanel(new FlowLayout(
                FlowLayout.LEFT, 8, 0));
        selectors.setOpaque(false);
        selectors.add(label("数据源"));
        sourceSelector.setPreferredSize(new Dimension(240, 34));
        sourceSelector.setRenderer(new SourceRenderer());
        selectors.add(sourceSelector);
        selectors.add(label("数据库 / Schema / Project"));
        scopeSelector.setPreferredSize(new Dimension(220, 34));
        selectors.add(scopeSelector);
        selectors.add(chooseTables);
        selectors.add(clearTables);
        selectors.add(label("字段"));
        fieldDisplayMode.setSelectedItem(FieldDisplayMode.PHYSICAL);
        fieldDisplayMode.setPreferredSize(new Dimension(132, 34));
        selectors.add(fieldDisplayMode);
        lineageKind.setPreferredSize(new Dimension(112, 34));
        lineageDirection.setPreferredSize(new Dimension(100, 34));
        lineagePolicy.setPreferredSize(new Dimension(118, 34));
        lineageColumn.setPreferredSize(new Dimension(190, 34));
        lineageBar.setOpaque(false);
        lineageBar.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        lineageBar.add(lineageKindLabel);
        lineageBar.add(lineageKind);
        lineageBar.add(lineageDirectionLabel);
        lineageBar.add(lineageDirection);
        lineageBar.add(lineageColumnLabel);
        lineageBar.add(lineageColumn);
        lineageBar.add(lineagePolicyLabel);
        lineageBar.add(lineagePolicy);
        lineageBar.add(probeLineage);
        top.add(selectors, BorderLayout.CENTER);

        graphFilter.putClientProperty("JTextField.placeholderText",
                "在已加载图中筛选表、字段或注释");
        graphFilter.putClientProperty("JTextField.leadingIcon",
                LyraIcons.of(LyraIcons.Kind.SEARCH, NativeTheme.MUTED));
        graphFilter.putClientProperty("JTextField.showClearButton", true);
        graphFilter.setPreferredSize(new Dimension(260, 34));
        top.add(graphFilter, BorderLayout.EAST);

        JPanel secondRow = new JPanel(new BorderLayout());
        secondRow.setOpaque(false);
        secondRow.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        status.setFont(NativeTheme.FONT_CAPTION);
        status.setForeground(NativeTheme.MUTED);
        secondRow.add(status, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(
                FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        JButton zoomOut = UiKit.button(
                "缩小", LyraIcons.of(LyraIcons.Kind.ZOOM_OUT),
                UiKit.ButtonStyle.GHOST);
        zoomOut.setToolTipText("缩小关系图");
        zoomOut.addActionListener(event ->
                graph.setZoom(graph.zoom() - 0.1D));
        JButton zoomIn = UiKit.button(
                "放大", LyraIcons.of(LyraIcons.Kind.ZOOM_IN),
                UiKit.ButtonStyle.GHOST);
        zoomIn.setToolTipText("放大关系图");
        zoomIn.addActionListener(event ->
                graph.setZoom(graph.zoom() + 0.1D));
        JButton fit = UiKit.button(
                "适应画布", LyraIcons.of(LyraIcons.Kind.FIT),
                UiKit.ButtonStyle.GHOST);
        fit.addActionListener(event -> fitGraph());
        JButton close = UiKit.button(
                "关闭", LyraIcons.of(LyraIcons.Kind.CLOSE),
                UiKit.ButtonStyle.GHOST);
        close.addActionListener(event -> dispose());
        actions.add(zoomOut);
        actions.add(zoomIn);
        actions.add(fit);
        actions.add(refresh);
        actions.add(export);
        actions.add(close);
        secondRow.add(actions, BorderLayout.EAST);
        JPanel lowerRows = new JPanel();
        lowerRows.setOpaque(false);
        lowerRows.setLayout(new BoxLayout(
                lowerRows, BoxLayout.Y_AXIS));
        lowerRows.add(lineageBar);
        lowerRows.add(secondRow);
        top.add(lowerRows, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);

        scroll.setBorder(BorderFactory.createEmptyBorder());
        root.add(scroll, BorderLayout.CENTER);

        sourceSelector.addActionListener(event -> {
            if (!updatingSelectors) {
                loadScopes(null, true);
            }
        });
        scopeSelector.addActionListener(event -> {
            if (!updatingSelectors) {
                ConnectionChoice source = selectedSource();
                if (source != null && isMaxCompute(source.connection())) {
                    resetSelection("Project 已切换，请选择血缘根表");
                } else {
                    loadOrdinaryScope();
                }
            }
        });
        chooseTables.addActionListener(event -> openTablePicker());
        clearTables.addActionListener(event ->
                resetSelection("已清空选表；搜索并选择 1–"
                        + ErDiagramMetadataLoader.MAX_SELECTED_TABLES
                        + " 张表开始加载"));
        probeLineage.addActionListener(event -> probeLineage());
        lineageKind.addActionListener(event -> updateLineageColumnState());
        lineagePolicy.addActionListener(event -> {
            if (!updatingLineageControls) {
                persistLineagePolicy();
                configurePeriodicProbe();
            }
        });
        refresh.addActionListener(event -> refreshCurrent());
        export.addActionListener(event -> exportPng());
        fieldDisplayMode.addActionListener(event ->
                graph.setFieldDisplayMode(
                        (FieldDisplayMode) fieldDisplayMode.getSelectedItem()));
        graphFilter.getDocument().addDocumentListener(
                new javax.swing.event.DocumentListener() {
                    @Override public void insertUpdate(
                            javax.swing.event.DocumentEvent event) {
                        graph.setFilter(graphFilter.getText());
                    }
                    @Override public void removeUpdate(
                            javax.swing.event.DocumentEvent event) {
                        graph.setFilter(graphFilter.getText());
                    }
                    @Override public void changedUpdate(
                            javax.swing.event.DocumentEvent event) {
                        graph.setFilter(graphFilter.getText());
                    }
                });
        graph.addMouseWheelListener(event -> {
            if (event.isControlDown()) {
                event.consume();
                graph.setZoom(graph.zoom()
                        + (event.getWheelRotation() < 0 ? 0.1D : -0.1D));
            }
        });
        export.setEnabled(false);
        chooseTables.setEnabled(false);
        clearTables.setEnabled(false);
        setLineageControlsVisible(false);
    }

    private void loadScopes(String requestedNamespace, boolean clearCache) {
        ConnectionChoice source = selectedSource();
        if (source == null) {
            setEmpty("没有可用数据源，请先新建连接");
            return;
        }
        if (!supportsStructureMap(source.connection().getDbType())) {
            setEmpty("当前数据源类型不支持关系图："
                    + source.connection().getDbType());
            chooseTables.setEnabled(false);
            return;
        }
        if (clearCache) {
            metadataCache.clear();
            selectedTables.clear();
        }
        long request = beginWork("正在连接 "
                + source.connection().getName() + " 并读取命名空间…");
        worker = new SwingWorker<List<ScopeChoice>, Void>() {
            @Override
            protected List<ScopeChoice> doInBackground() throws Exception {
                runtime.connectionManager().connect(
                        source.connection().getId());
                return readScopes(source.connection());
            }

            @Override
            protected void done() {
                if (isCancelled() || request != generation) {
                    return;
                }
                try {
                    List<ScopeChoice> scopes = get();
                    updateScopes(scopes, requestedNamespace);
                    updateWindowTitle(source.connection());
                    configureMode(source.connection());
                    setLoading(false);
                    if (isMaxCompute(source.connection())) {
                        showSelectionHint(source.connection());
                    } else {
                        loadOrdinaryScope();
                    }
                } catch (Exception exception) {
                    setLoading(false);
                    setEmpty("命名空间读取失败：" + safeMessage(exception));
                }
            }
        };
        worker.execute();
    }

    private List<ScopeChoice> readScopes(DesktopConnection connection)
            throws Exception {
        String dbType = connection.getDbType();
        if ("MAXCOMPUTE".equalsIgnoreCase(dbType)) {
            String project = connectionParameter(
                    connection, "project", "defaultProject");
            return List.of(new ScopeChoice(
                    project == null ? "当前 Project" : project, null));
        }
        if ("SQLITE".equalsIgnoreCase(dbType)) {
            return List.of(new ScopeChoice(CURRENT_SCOPE, null));
        }
        List<ScopeChoice> scopes = new ArrayList<>();
        for (TreeNode node : runtime.connectionManager().tree(
                connection.getId(), null)) {
            String type = normalizedType(node.getType());
            if (Set.of("DATABASE", "SCHEMA", "PROJECT", "CATALOG")
                    .contains(type)
                    && node.getName() != null
                    && !node.getName().isBlank()) {
                String namespace = node.getPath() == null
                        || node.getPath().isBlank()
                        ? node.getName() : node.getPath();
                scopes.add(new ScopeChoice(node.getName(), namespace));
            }
        }
        return scopes.isEmpty()
                ? List.of(new ScopeChoice(CURRENT_SCOPE, null))
                : List.copyOf(scopes);
    }

    private void updateScopes(
            List<ScopeChoice> scopes, String requestedNamespace) {
        updatingSelectors = true;
        try {
            scopeSelector.removeAllItems();
            ScopeChoice selected = null;
            for (ScopeChoice scope : scopes) {
                scopeSelector.addItem(scope);
                if (same(scope.namespace(), requestedNamespace)) {
                    selected = scope;
                }
            }
            if (selected != null) {
                scopeSelector.setSelectedItem(selected);
            } else if (scopeSelector.getItemCount() > 0) {
                scopeSelector.setSelectedIndex(0);
            }
        } finally {
            updatingSelectors = false;
        }
        currentGraph = emptyGraph();
        graph.setGraph(currentGraph);
        updateSelectionButtons();
    }

    private void openTablePicker() {
        ConnectionChoice source = selectedSource();
        ScopeChoice scope = selectedScope();
        if (source == null || scope == null) {
            return;
        }
        String sourceLabel = source.connection().getName()
                + " · " + scope.label();
        List<ErDiagramMetadataLoader.TableChoice> result =
                ErTableSelectionDialog.choose(
                        this, sourceLabel,
                        new ArrayList<>(selectedTables.values()),
                        query -> searchTables(
                                source.connection(), scope, query));
        if (result == null) {
            return;
        }
        selectedTables.clear();
        result.forEach(choice -> selectedTables.put(
                choice.key(), choice));
        updateSelectionButtons();
        loadSelectedGraph();
    }

    private List<ErDiagramMetadataLoader.TableChoice> searchTables(
            DesktopConnection connection,
            ScopeChoice scope,
            String query) throws Exception {
        Map<String, ErDiagramMetadataLoader.TableChoice> choices =
                new LinkedHashMap<>();
        for (TreeNode node : runtime.connectionManager().search(
                connection.getId(), scope.namespace(), query, 120)) {
            if (!SUPPORTED_OBJECT_TYPES.contains(
                    normalizedType(node.getType()))) {
                continue;
            }
            ErDiagramMetadataLoader.TableChoice choice =
                    ErDiagramMetadataLoader.fromNode(
                            node, scope.namespace(), scope.label(),
                            connection.getDbType());
            choices.putIfAbsent(choice.key(), choice);
        }
        return List.copyOf(choices.values());
    }

    private void loadSelectedGraph() {
        ConnectionChoice source = selectedSource();
        ScopeChoice scope = selectedScope();
        List<ErDiagramMetadataLoader.TableChoice> selected =
                new ArrayList<>(selectedTables.values());
        if (source == null || scope == null || selected.isEmpty()) {
            resetSelection("请搜索并选择要展示的表");
            return;
        }
        boolean maxCompute = "MAXCOMPUTE".equalsIgnoreCase(
                source.connection().getDbType());
        long request = beginWork("正在按需读取 " + selected.size()
                + " 张表的" + (maxCompute
                ? "结构元数据…" : "字段和真实约束…"));
        worker = new SwingWorker<ErDiagramDialog.SchemaGraph, Void>() {
            @Override
            protected ErDiagramDialog.SchemaGraph doInBackground()
                    throws Exception {
                String id = source.connection().getId();
                return ErDiagramMetadataLoader.load(
                        new ErDiagramMetadataLoader.MetadataSource(
                                (namespace, table) ->
                                        runtime.connectionManager().columns(
                                                id, namespace, table),
                                (namespace, table) ->
                                        maxCompute
                                                ? List.of()
                                                : runtime.connectionManager()
                                                        .constraints(id, namespace, table)),
                        selected, metadataCache);
            }

            @Override
            protected void done() {
                if (isCancelled() || request != generation) {
                    return;
                }
                try {
                    currentGraph = get();
                    graph.setGraph(currentGraph);
                    graph.setFieldDisplayMode((FieldDisplayMode)
                            fieldDisplayMode.getSelectedItem());
                    graph.setFilter(graphFilter.getText());
                    setLoading(false);
                    export.setEnabled(!currentGraph.tables().isEmpty());
                    showLoadedStatus(source.connection(), scope);
                    if (maxCompute) {
                        updateLineageColumns();
                        if (lineagePolicy.getSelectedItem()
                                == DataWorksLineageService.ProbePolicy.ON_SELECTION) {
                            probeLineage();
                        }
                    }
                    SwingUtilities.invokeLater(
                            ErDataMapDialog.this::fitGraph);
                } catch (Exception exception) {
                    setLoading(false);
                    setEmpty("关系图加载失败：" + safeMessage(exception));
                }
            }
        };
        worker.execute();
    }

    private void refreshCurrent() {
        ConnectionChoice source = selectedSource();
        if (source == null) {
            return;
        }
        runtime.connectionManager().invalidateMetadata(
                source.connection().getId());
        metadataCache.clear();
        if (!isMaxCompute(source.connection())) {
            loadOrdinaryScope();
            return;
        }
        if (selectedTables.isEmpty()) {
            ScopeChoice scope = selectedScope();
            loadScopes(scope == null ? null : scope.namespace(), false);
        } else {
            loadSelectedGraph();
        }
    }

    private void resetSelection(String message) {
        generation++;
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
        selectedTables.clear();
        currentGraph = emptyGraph();
        graph.setGraph(currentGraph);
        export.setEnabled(false);
        setLoading(false);
        updateSelectionButtons();
        status.setForeground(NativeTheme.MUTED);
        status.setText(message);
    }

    private void showSelectionHint(DesktopConnection connection) {
        updateSelectionButtons();
        boolean maxCompute = "MAXCOMPUTE".equalsIgnoreCase(
                connection.getDbType());
        if (maxCompute) {
            status.setForeground(NativeTheme.MUTED);
            status.setText("选择一张或多张根表；默认手动探查，"
                    + "也可配置选表后或定时探查真实表血缘 / 字段血缘");
            return;
        }
    }

    private void showLoadedStatus(
            DesktopConnection connection, ScopeChoice scope) {
        int tableCount = currentGraph.tables().size();
        int relationCount = currentGraph.relations().size();
        boolean maxCompute = "MAXCOMPUTE".equalsIgnoreCase(
                connection.getDbType());
        if (maxCompute) {
            status.setForeground(NativeTheme.SUCCESS);
            status.setText(connection.getName() + " · " + scope.label()
                    + " · 已加载 " + tableCount
                    + " 张根表结构 · 点击“探查血缘”读取 DataWorks 真实关系");
            return;
        }
        status.setForeground(NativeTheme.SUCCESS);
        String capHint = tableCount >= ErDiagramMetadataLoader.MAX_SCOPE_TABLES
                ? " · 已达到 2,000 张安全上限，可用筛选器缩小画布范围"
                : "";
        status.setText(connection.getName() + " · " + scope.label()
                + " · " + tableCount + " 张表 · " + relationCount
                + " 条真实关系" + capHint);
    }

    private void loadOrdinaryScope() {
        ConnectionChoice source = selectedSource();
        ScopeChoice scope = selectedScope();
        if (source == null || scope == null
                || isMaxCompute(source.connection())) {
            return;
        }
        metadataCache.clear();
        selectedTables.clear();
        long request = beginWork("正在读取所选范围内的全部表和视图…");
        worker = new SwingWorker<List<ErDiagramMetadataLoader.TableChoice>, Void>() {
            @Override
            protected List<ErDiagramMetadataLoader.TableChoice>
                    doInBackground() throws Exception {
                return readAllScopeTables(source.connection(), scope);
            }

            @Override
            protected void done() {
                if (isCancelled() || request != generation) {
                    return;
                }
                try {
                    List<ErDiagramMetadataLoader.TableChoice> choices = get();
                    choices.forEach(choice -> selectedTables.put(
                            choice.key(), choice));
                    currentGraph = ErDiagramMetadataLoader.skeleton(choices);
                    graph.setGraph(currentGraph);
                    graph.setFilter(graphFilter.getText());
                    export.setEnabled(!choices.isEmpty());
                    if (choices.isEmpty()) {
                        setLoading(false);
                        setEmpty("所选数据库 / Schema 中没有表或视图");
                        return;
                    }
                    status.setText("已发现 " + choices.size()
                            + " 张表 / 视图，正在补充字段和外键…");
                    SwingUtilities.invokeLater(
                            ErDataMapDialog.this::fitGraph);
                    loadSelectedGraph();
                } catch (Exception exception) {
                    setLoading(false);
                    setEmpty("全范围表清单读取失败：" + safeMessage(exception));
                }
            }
        };
        worker.execute();
    }

    private List<ErDiagramMetadataLoader.TableChoice> readAllScopeTables(
            DesktopConnection connection, ScopeChoice scope) throws Exception {
        Map<String, ErDiagramMetadataLoader.TableChoice> choices =
                new LinkedHashMap<>();
        Deque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(scope.namespace() == null ? "" : scope.namespace());
        while (!pending.isEmpty()
                && choices.size() < ErDiagramMetadataLoader.MAX_SCOPE_TABLES) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("ER 全范围元数据加载已取消");
            }
            String pathToken = pending.removeFirst();
            String path = pathToken.isEmpty() ? null : pathToken;
            String visitKey = path == null ? "<root>" : path;
            if (!visited.add(visitKey.toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (TreeNode node : runtime.connectionManager().tree(
                    connection.getId(), path)) {
                String type = normalizedType(node.getType());
                if (SUPPORTED_OBJECT_TYPES.contains(type)) {
                    ErDiagramMetadataLoader.TableChoice choice =
                            ErDiagramMetadataLoader.fromNode(
                                    node, path, scope.label(),
                                    connection.getDbType());
                    choices.putIfAbsent(choice.key(), choice);
                } else if (node.isHasChildren()
                        && SCOPE_OBJECT_TYPES.contains(type)
                        && node.getPath() != null
                        && !node.getPath().isBlank()) {
                    pending.addLast(node.getPath());
                }
                if (choices.size()
                        >= ErDiagramMetadataLoader.MAX_SCOPE_TABLES) {
                    break;
                }
            }
        }
        return List.copyOf(choices.values());
    }

    private void probeLineage() {
        ConnectionChoice source = selectedSource();
        ScopeChoice scope = selectedScope();
        if (source == null || scope == null
                || !isMaxCompute(source.connection())) {
            return;
        }
        String project = connectionParameter(
                source.connection(), "project", "defaultProject");
        if (project == null) {
            setEmpty("MaxCompute 连接缺少 Project，无法构造血缘实体 ID");
            return;
        }
        DataWorksLineageService.EntityKind kind =
                (DataWorksLineageService.EntityKind)
                        lineageKind.getSelectedItem();
        List<String> roots = new ArrayList<>();
        if (kind == DataWorksLineageService.EntityKind.COLUMN) {
            ColumnChoice column = (ColumnChoice) lineageColumn.getSelectedItem();
            if (column != null) {
                roots.add(DataWorksLineageService.columnEntityId(
                        project, column.table(), column.column()));
            }
        } else {
            selectedTables.values().forEach(choice -> roots.add(
                    DataWorksLineageService.tableEntityId(
                            project, choice.name())));
        }
        if (roots.isEmpty()) {
            status.setForeground(NativeTheme.WARNING);
            status.setText(kind == DataWorksLineageService.EntityKind.COLUMN
                    ? "请先选择根表并选择一个根字段"
                    : "请先选择至少一张血缘根表");
            return;
        }
        DataWorksLineageService.Direction direction =
                (DataWorksLineageService.Direction)
                        lineageDirection.getSelectedItem();
        long request = beginWork("正在通过 DataWorks 探查真实"
                + kind + "…");
        worker = new SwingWorker<DataWorksLineageService.LineageResult, Void>() {
            @Override
            protected DataWorksLineageService.LineageResult
                    doInBackground() throws Exception {
                return DataWorksLineageService.fromConnection(
                        source.connection()).explore(
                        roots, direction,
                        DataWorksLineageService.DEFAULT_DEPTH,
                        DataWorksLineageService.DEFAULT_MAX_NODES);
            }

            @Override
            protected void done() {
                if (isCancelled() || request != generation) {
                    return;
                }
                try {
                    DataWorksLineageService.LineageResult result = get();
                    currentGraph = result.graph();
                    graph.setGraph(currentGraph);
                    graph.setFilter(graphFilter.getText());
                    setLoading(false);
                    export.setEnabled(!currentGraph.tables().isEmpty());
                    status.setForeground(NativeTheme.SUCCESS);
                    status.setText(source.connection().getName()
                            + " · DataWorks " + kind + " · "
                            + currentGraph.tables().size() + " 个节点 · "
                            + result.edgeCount() + " 条真实血缘 · 探查于 "
                            + TIME_FORMAT.format(result.observedAt())
                            + (currentGraph.truncated()
                            ? " · 已按安全上限截断" : ""));
                    SwingUtilities.invokeLater(
                            ErDataMapDialog.this::fitGraph);
                } catch (Exception exception) {
                    setLoading(false);
                    status.setForeground(NativeTheme.ERROR);
                    status.setText("血缘探查失败：" + safeMessage(exception)
                            + "。请确认 DataWorks 标准版及以上、"
                            + "ListLineages 权限和地域配置");
                }
            }
        };
        worker.execute();
    }

    private void updateLineageColumns() {
        ColumnChoice selected = (ColumnChoice) lineageColumn.getSelectedItem();
        lineageColumn.removeAllItems();
        for (ErDiagramMetadataLoader.TableChoice table
                : selectedTables.values()) {
            ErDiagramMetadataLoader.TableMetadata metadata =
                    metadataCache.get(table.key());
            if (metadata == null) {
                continue;
            }
            metadata.columns().forEach(column -> lineageColumn.addItem(
                    new ColumnChoice(table.name(), column.getName())));
        }
        if (selected != null) {
            for (int index = 0; index < lineageColumn.getItemCount(); index++) {
                if (selected.equals(lineageColumn.getItemAt(index))) {
                    lineageColumn.setSelectedIndex(index);
                    break;
                }
            }
        }
        updateLineageColumnState();
    }

    private long beginWork(String message) {
        long request = ++generation;
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
        setLoading(true);
        status.setForeground(NativeTheme.WARNING);
        status.setText(message);
        return request;
    }

    private void setLoading(boolean loading) {
        sourceSelector.setEnabled(!loading);
        scopeSelector.setEnabled(!loading
                && scopeSelector.getItemCount() > 0);
        chooseTables.setEnabled(!loading
                && selectedSource() != null
                && selectedScope() != null);
        refresh.setEnabled(!loading);
        clearTables.setEnabled(!loading && !selectedTables.isEmpty());
        if (loading) {
            export.setEnabled(false);
        } else {
            export.setEnabled(!currentGraph.tables().isEmpty());
        }
    }

    private void updateSelectionButtons() {
        chooseTables.setText(selectedTables.isEmpty()
                ? "选择表" : "已选 " + selectedTables.size() + " 张表");
        chooseTables.setEnabled(selectedSource() != null
                && selectedScope() != null);
        clearTables.setEnabled(!selectedTables.isEmpty());
        ConnectionChoice source = selectedSource();
        if (source != null && isMaxCompute(source.connection())) {
            chooseTables.setText(selectedTables.isEmpty()
                    ? "选择血缘根表"
                    : "根表 " + selectedTables.size() + " 张");
        }
    }

    private void configureMode(DesktopConnection connection) {
        boolean maxCompute = isMaxCompute(connection);
        setTitle(maxCompute
                ? "MaxCompute 数据血缘与字段血缘"
                : "全库 ER 关系图");
        chooseTables.setVisible(maxCompute);
        clearTables.setVisible(maxCompute);
        setLineageControlsVisible(maxCompute);
        graphFilter.putClientProperty("JTextField.placeholderText",
                maxCompute
                        ? "筛选血缘节点、表、字段或注释"
                        : "筛选所选库中的表、字段或注释");
        if (maxCompute) {
            updatingLineageControls = true;
            try {
                lineagePolicy.setSelectedItem(
                        DataWorksLineageService.ProbePolicy.fromValue(
                                connection.getParams().get(
                                        "lineageProbePolicy")));
            } finally {
                updatingLineageControls = false;
            }
            configurePeriodicProbe();
        } else if (periodicProbeTimer != null) {
            periodicProbeTimer.stop();
        }
    }

    private void setLineageControlsVisible(boolean visible) {
        lineageBar.setVisible(visible);
        lineageKindLabel.setVisible(visible);
        lineageKind.setVisible(visible);
        lineageDirectionLabel.setVisible(visible);
        lineageDirection.setVisible(visible);
        lineagePolicyLabel.setVisible(visible);
        lineagePolicy.setVisible(visible);
        lineageColumnLabel.setVisible(visible);
        lineageColumn.setVisible(visible);
        probeLineage.setVisible(visible);
        updateLineageColumnState();
    }

    private void updateLineageColumnState() {
        boolean columnMode = lineageKind.getSelectedItem()
                == DataWorksLineageService.EntityKind.COLUMN;
        lineageColumnLabel.setVisible(lineageKind.isVisible() && columnMode);
        lineageColumn.setVisible(lineageKind.isVisible() && columnMode);
        probeLineage.setEnabled(!columnMode
                || lineageColumn.getSelectedItem() != null);
    }

    private void persistLineagePolicy() {
        ConnectionChoice source = selectedSource();
        if (source == null || !isMaxCompute(source.connection())) {
            return;
        }
        Object selected = lineagePolicy.getSelectedItem();
        if (!(selected instanceof DataWorksLineageService.ProbePolicy policy)) {
            return;
        }
        DesktopConnection updated = source.connection().copy();
        Map<String, Object> params = new LinkedHashMap<>(updated.getParams());
        params.put("lineageProbePolicy", policy.name());
        updated.setParams(params);
        runtime.stateStore().saveConnection(updated);
        source.connection().setParams(params);
        status.setText("血缘探查时机已保存：" + policy);
    }

    private void configurePeriodicProbe() {
        if (periodicProbeTimer != null) {
            periodicProbeTimer.stop();
            periodicProbeTimer = null;
        }
        Object selected = lineagePolicy.getSelectedItem();
        if (!(selected instanceof DataWorksLineageService.ProbePolicy policy)
                || policy.intervalMs() <= 0) {
            return;
        }
        periodicProbeTimer = new Timer(policy.intervalMs(), event -> {
            if (isShowing() && (worker == null || worker.isDone())
                    && !selectedTables.isEmpty()) {
                probeLineage();
            }
        });
        periodicProbeTimer.setRepeats(true);
        periodicProbeTimer.start();
    }

    private void setEmpty(String message) {
        currentGraph = emptyGraph();
        graph.setGraph(currentGraph);
        export.setEnabled(false);
        status.setForeground(NativeTheme.ERROR);
        status.setText(message);
    }

    private void updateWindowTitle(DesktopConnection connection) {
        setTitle("MAXCOMPUTE".equalsIgnoreCase(connection.getDbType())
                ? "MaxCompute 数据地图（表结构）" : "ER 关系图");
    }

    private void fitGraph() {
        graph.fitTo(scroll.getViewport().getExtentSize());
        scroll.getViewport().setViewPosition(new java.awt.Point(0, 0));
    }

    private void exportPng() {
        Dimension size = graph.getPreferredSize();
        if (currentGraph.tables().isEmpty()
                || size.width <= 0 || size.height <= 0) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        ConnectionChoice source = selectedSource();
        String sourceName = source == null ? "diagram"
                : safeFileName(source.connection().getName());
        chooser.setSelectedFile(new java.io.File(
                "lyradb-data-map-" + sourceName + ".png"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        if (Files.exists(target)) {
            int overwrite = JOptionPane.showConfirmDialog(this,
                    "文件已存在，确认覆盖？\n" + target.toAbsolutePath(),
                    "确认覆盖", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (overwrite != JOptionPane.YES_OPTION) {
                return;
            }
        }
        try {
            BufferedImage image = new BufferedImage(
                    size.width, size.height,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graph.setSize(size);
                graph.paint(graphics);
            } finally {
                graphics.dispose();
            }
            ImageIO.write(image, "png", target.toFile());
            status.setText("关系图已导出：" + target.toAbsolutePath());
        } catch (Exception exception) {
            JOptionPane.showMessageDialog(this,
                    safeMessage(exception), "导出失败",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    @Override
    public void dispose() {
        generation++;
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
        if (periodicProbeTimer != null) {
            periodicProbeTimer.stop();
        }
        super.dispose();
    }

    private ConnectionChoice selectedSource() {
        return (ConnectionChoice) sourceSelector.getSelectedItem();
    }

    private ScopeChoice selectedScope() {
        return (ScopeChoice) scopeSelector.getSelectedItem();
    }

    private static boolean supportsStructureMap(String dbType) {
        return !"MONGODB".equalsIgnoreCase(dbType)
                && !"REDIS".equalsIgnoreCase(dbType);
    }

    private static boolean isMaxCompute(DesktopConnection connection) {
        return connection != null
                && "MAXCOMPUTE".equalsIgnoreCase(connection.getDbType());
    }

    private static String connectionParameter(
            DesktopConnection connection, String... names) {
        for (Map.Entry<String, Object> entry :
                connection.getParams().entrySet()) {
            for (String name : names) {
                if (entry.getKey().equalsIgnoreCase(name)
                        && entry.getValue() != null
                        && !entry.getValue().toString().isBlank()) {
                    return entry.getValue().toString().trim();
                }
            }
        }
        return null;
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(NativeTheme.FONT_CAPTION);
        label.setForeground(NativeTheme.MUTED);
        return label;
    }

    private static String normalizedType(String type) {
        return type == null ? "" : type.toUpperCase(Locale.ROOT);
    }

    private static boolean same(String first, String second) {
        String left = first == null ? "" : first;
        String right = second == null ? "" : second;
        return left.equalsIgnoreCase(right);
    }

    private static String safeFileName(String value) {
        String normalized = value == null ? "diagram"
                : value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return normalized.isBlank() ? "diagram" : normalized;
    }

    private static String safeMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null
                ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static ErDiagramDialog.SchemaGraph emptyGraph() {
        return new ErDiagramDialog.SchemaGraph(
                List.of(), List.of(), false);
    }

    private record ScopeChoice(String label, String namespace) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record ColumnChoice(String table, String column) {
        @Override
        public String toString() {
            return table + "." + column;
        }
    }

    private record ConnectionChoice(DesktopConnection connection) {
        @Override
        public String toString() {
            return connection.getName();
        }
    }

    private final class SourceRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index,
                boolean selected, boolean focused) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, selected, focused);
            if (value instanceof ConnectionChoice choice) {
                DesktopConnection connection = choice.connection();
                boolean connected = runtime.connectionManager()
                        .isConnected(connection.getId());
                label.setText(connection.getName() + "  ·  "
                        + connection.getDbType());
                label.setIcon(LyraIcons.databaseEngine(
                        connection.getDbType(), 22, connected));
                label.setBorder(BorderFactory.createEmptyBorder(
                        3, 6, 3, 6));
            }
            return label;
        }
    }
}
