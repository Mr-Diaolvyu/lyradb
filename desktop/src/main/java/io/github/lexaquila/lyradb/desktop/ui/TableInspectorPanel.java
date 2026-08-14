package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.DesktopRuntime;
import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.PartitionMetadata;
import io.github.lexaquila.lyradb.model.dto.PartitionMetadataPage;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.TableConstraintMetadata;
import io.github.lexaquila.lyradb.model.dto.TableCommentMetadata;
import io.github.lexaquila.lyradb.model.dto.TreeNode;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 表/视图工作台：先展示字段与注释，再提供受限数据预览、索引约束和 DDL。
 * MaxCompute 数据预览默认不执行，避免无分区扫描阻塞元数据展示。
 */
final class TableInspectorPanel extends JPanel {

    static final int PREVIEW_LIMIT = 200;
    static final int MAXCOMPUTE_PREVIEW_LIMIT = 100;
    static final int PARTITION_PAGE_SIZE = 100;

    private final DesktopRuntime runtime;
    private final String connectionId;
    private final String connectionName;
    private final String dbType;
    private final String schema;
    private final String table;
    private final String objectType;
    private final MetadataHint initialMetadata;
    private final Consumer<String> statusSink;
    private final Consumer<String> sqlOpener;

    private final JLabel stateLabel = new JLabel("准备加载");
    private final JLabel tableCommentLabel = new JLabel("表注释：正在获取…");
    private final JLabel metadataStateLabel = new JLabel("元数据状态：准备读取");
    private final JLabel overviewComment = new JLabel("正在获取表注释…");
    private final JLabel overviewMetadata = new JLabel("准备读取元数据来源");
    private final JLabel overviewPartitions = new JLabel("尚未读取分区");
    private final JLabel overviewColumns = new JLabel("正在读取字段…");
    private final JButton loadPreviewButton = UiKit.button("加载数据", null, UiKit.ButtonStyle.SECONDARY);
    private final JButton choosePartitionButton = UiKit.button(
            "选择分区", LyraIcons.of(LyraIcons.Kind.PARTITION),
            UiKit.ButtonStyle.SECONDARY);
    private final JLabel rowBadge = UiKit.badge(
            "0 行", NativeTheme.ACCENT_LIGHT, NativeTheme.ACCENT_SOFT);
    private final JLabel columnBadge = UiKit.badge(
            "0 列", NativeTheme.MUTED, NativeTheme.SURFACE_ALT);
    private final JButton refreshButton = UiKit.button(
            "刷新", LyraIcons.of(LyraIcons.Kind.REFRESH),
            UiKit.ButtonStyle.SECONDARY);
    private final JButton openSqlButton = UiKit.button(
            "在 SQL 中打开", LyraIcons.of(LyraIcons.Kind.SQL),
            UiKit.ButtonStyle.PRIMARY);
    private final JComboBox<FieldDisplayMode> fieldDisplayMode =
            new JComboBox<>(FieldDisplayMode.values());
    private final JTable previewTable = table();
    private final JTable columnsTable = table();
    private final JTable constraintsTable = table();
    private final JTable partitionsTable = table();
    private final JTextField partitionFilter = new JTextField();
    private final JLabel partitionState = new JLabel("打开本页后再读取分区");
    private final JLabel partitionPage = new JLabel("第 1 页");
    private final JLabel selectedPartitionLabel = new JLabel("未选择分区");
    private final JLabel previewPartitionLabel = new JLabel("当前分区：未选择");
    private final JButton previousPartitionPage = UiKit.button(
            "上一页", null, UiKit.ButtonStyle.GHOST);
    private final JButton nextPartitionPage = UiKit.button(
            "下一页", null, UiKit.ButtonStyle.GHOST);
    private final JButton latestPartitionButton = UiKit.button(
            "选择首个分区", LyraIcons.of(LyraIcons.Kind.PARTITION),
            UiKit.ButtonStyle.SECONDARY);
    private final JButton reloadPartitionsButton = UiKit.button(
            "刷新分区", LyraIcons.of(LyraIcons.Kind.REFRESH),
            UiKit.ButtonStyle.GHOST);
    private final JTextArea ddlArea = new JTextArea();
    private final ReadOnlyTableModel previewModel =
            new ReadOnlyTableModel(List.of());
    private final ReadOnlyTableModel columnsModel =
            new ReadOnlyTableModel(List.of(
                    "#", "字段名", "数据类型", "长度", "可空",
                    "默认值", "键", "自增", "注释"));
    private final ReadOnlyTableModel constraintsModel =
            new ReadOnlyTableModel(List.of(
                    "类型", "名称", "字段", "引用对象", "引用字段"));
    private final PartitionTableModel partitionsModel =
            new PartitionTableModel();

    private SwingWorker<TableSnapshot, Void> worker;
    private SwingWorker<QueryResult, Void> previewWorker;
    private SwingWorker<PartitionMetadataPage, Void> partitionWorker;
    private SwingWorker<String, Void> partitionSqlWorker;
    private long generation;
    private String previewSql;
    private TableSnapshot currentSnapshot;
    private JTabbedPane detailTabs;
    private JPanel partitionPanel;
    private JPanel previewPanel;
    private boolean partitionsLoaded;
    private boolean partitionHasMore;
    private boolean selectLatestAfterLoad;
    private int partitionOffset;
    private String selectedPartitionSpec;
    private Boolean partitioned;
    private String commentMetadataSummary = "";
    private String partitionMetadataSummary = "";

    TableInspectorPanel(
            DesktopRuntime runtime,
            String connectionId,
            String connectionName,
            String dbType,
            String schema,
            String table,
            String objectType,
            MetadataHint metadataHint,
            Consumer<String> statusSink,
            Consumer<String> sqlOpener) {
        super(new BorderLayout());
        this.runtime = runtime;
        this.connectionId = connectionId;
        this.connectionName = connectionName;
        this.dbType = dbType;
        this.schema = schema;
        this.table = table;
        this.objectType = objectType == null ? "TABLE" : objectType;
        this.initialMetadata = metadataHint == null
                ? MetadataHint.empty() : metadataHint;
        this.partitioned = this.initialMetadata.partitioned();
        this.statusSink = statusSink;
        this.sqlOpener = sqlOpener;
        updateCommentPresentation(null, null);
        if (Boolean.TRUE.equals(partitioned)) {
            overviewPartitions.setText("已识别为分区表 · 打开分区页按需加载");
        } else if (Boolean.FALSE.equals(partitioned)) {
            overviewPartitions.setText("目录标记为非分区表 · 可手动加载受限预览");
        }

        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(createHeader(), BorderLayout.NORTH);
        add(createTabs(), BorderLayout.CENTER);
        refreshButton.addActionListener(event -> refresh());
        openSqlButton.addActionListener(event -> openSql());
        loadPreviewButton.setText(isMaxCompute()
                ? Boolean.FALSE.equals(partitioned)
                ? "加载前 " + MAXCOMPUTE_PREVIEW_LIMIT + " 行"
                : "选择分区后加载前 " + MAXCOMPUTE_PREVIEW_LIMIT + " 行"
                : "重新加载前 " + PREVIEW_LIMIT + " 行");
        loadPreviewButton.addActionListener(event -> loadPreview());
        choosePartitionButton.addActionListener(event -> {
            if (detailTabs != null && partitionPanel != null) {
                detailTabs.setSelectedComponent(partitionPanel);
            }
        });
        fieldDisplayMode.setSelectedItem(FieldDisplayMode.PHYSICAL);
        fieldDisplayMode.setToolTipText(
                "切换物理字段名、字段注释或两者；不会修改真实 SQL");
        fieldDisplayMode.addActionListener(event -> {
            if (currentSnapshot != null) {
                applyFieldDisplay(currentSnapshot);
            }
        });
        refresh();
    }

    String workspaceKey() {
        return connectionId + "\u0000"
                + (schema == null ? "" : schema) + "\u0000" + table;
    }

    void disposeWorkspace() {
        generation++;
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
        if (previewWorker != null && !previewWorker.isDone()) {
            previewWorker.cancel(true);
        }
        if (partitionWorker != null && !partitionWorker.isDone()) {
            partitionWorker.cancel(true);
        }
        if (partitionSqlWorker != null && !partitionSqlWorker.isDone()) {
            partitionSqlWorker.cancel(true);
        }
    }

    void refresh() {
        long request = ++generation;
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
        if (previewWorker != null && !previewWorker.isDone()) {
            previewWorker.cancel(true);
        }
        if (partitionWorker != null && !partitionWorker.isDone()) {
            partitionWorker.cancel(true);
        }
        if (partitionSqlWorker != null && !partitionSqlWorker.isDone()) {
            partitionSqlWorker.cancel(true);
        }
        setLoading(true);
        previewSql = null;
        currentSnapshot = null;
        selectedPartitionSpec = null;
        selectedPartitionLabel.setText("未选择分区");
        previewPartitionLabel.setText("当前分区：未选择");
        partitionsLoaded = false;
        partitionHasMore = false;
        partitionOffset = 0;
        partitionMetadataSummary = "";
        updateOverviewMetadata();
        partitionsModel.setPage(List.of());
        partitionState.setText("打开“分区”页后再按需读取");
        overviewPartitions.setText(Boolean.TRUE.equals(partitioned)
                ? "已识别为分区表 · 打开分区页按需加载"
                : Boolean.FALSE.equals(partitioned)
                ? "目录标记为非分区表 · 可手动加载受限预览"
                : "尚未读取分区");
        loadPreviewButton.setEnabled(false);
        previewModel.setData(List.of("状态"), List.of(List.of(
                isMaxCompute()
                        ? Boolean.FALSE.equals(partitioned)
                        ? "已确认非分区表；点击加载后按上限读取前 100 行"
                        : "未选择分区，不会执行查询；请先到“分区”页选择"
                        : "先读取元数据，随后自动加载受限数据预览")));
        columnsModel.setData(columnsModel.columns(), List.of());
        constraintsModel.setData(constraintsModel.columns(), List.of());
        ddlArea.setText("正在读取表定义…");
        stateLabel.setText("正在优先读取字段和表注释…");
        statusSink.accept("正在读取表元数据：" + qualifiedName());

        worker = new SwingWorker<>() {
            @Override
            protected TableSnapshot doInBackground() {
                String generatedPreviewSql = "";
                List<ColumnMetadata> columns = List.of();
                List<TableConstraintMetadata> constraints = List.of();
                String ddl = "";
                TableCommentMetadata tableComment = null;
                Map<String, String> errors = new LinkedHashMap<>();

                try {
                    columns = runtime.connectionManager().columns(
                            connectionId, schema, table);
                } catch (Exception exception) {
                    errors.put("columns", safeMessage(exception));
                }
                if (isCancelled()) {
                    return null;
                }
                try {
                    tableComment = runtime.connectionManager()
                            .tableCommentMetadata(
                            connectionId, schema, table);
                } catch (Exception exception) {
                    errors.put("tableComment", safeMessage(exception));
                }
                if (isCancelled()) {
                    return null;
                }
                if (!isMaxCompute()) {
                    try {
                        constraints = runtime.connectionManager().constraints(
                                connectionId, schema, table);
                    } catch (Exception exception) {
                        errors.put("constraints", safeMessage(exception));
                    }
                }
                if (isCancelled()) {
                    return null;
                }
                try {
                    ddl = runtime.connectionManager().ddl(
                            connectionId, schema, table);
                } catch (Exception exception) {
                    errors.put("ddl", safeMessage(exception));
                }
                if (!isMaxCompute()) {
                    try {
                        generatedPreviewSql = runtime.connectionManager()
                                .previewSql(connectionId, schema, table,
                                        previewLimit());
                    } catch (Exception exception) {
                        errors.put("previewSql", safeMessage(exception));
                    }
                }
                return new TableSnapshot(
                        generatedPreviewSql, null, columns,
                        constraints, ddl, tableComment, errors);
            }

            @Override
            protected void done() {
                if (isCancelled() || request != generation) {
                    return;
                }
                try {
                    TableSnapshot snapshot = get();
                    if (snapshot != null) {
                        apply(snapshot);
                        if (!isMaxCompute()) {
                            loadPreview();
                        } else {
                            stateLabel.setText(Boolean.FALSE.equals(partitioned)
                                    ? "已确认非分区表 · 点击按钮后按上限预览"
                                    : "元数据已就绪 · 选择分区前不会执行预览");
                        }
                    }
                } catch (Exception exception) {
                    apply(new TableSnapshot(
                            "", null, List.of(), List.of(), "",
                            null, Map.of("metadata", safeMessage(exception))));
                } finally {
                    if (request == generation) {
                        setLoading(false);
                    }
                }
            }
        };
        worker.execute();
    }

    private void loadPreview() {
        if (!previewAllowed(isMaxCompute(), currentSnapshot != null,
                partitioned, selectedPartitionSpec) || previewWorker != null
                && !previewWorker.isDone()) {
            if (isMaxCompute() && !Boolean.FALSE.equals(partitioned)
                    && (selectedPartitionSpec == null
                    || selectedPartitionSpec.isBlank())) {
                stateLabel.setText("未选择分区：已阻止数据查询");
                statusSink.accept("请先选择 MaxCompute 分区，未执行任何查询");
            }
            return;
        }
        long request = generation;
        String requestedPartition = selectedPartitionSpec;
        loadPreviewButton.setEnabled(false);
        stateLabel.setText(isMaxCompute()
                ? Boolean.FALSE.equals(partitioned)
                ? "正在读取非分区表的受限预览…"
                : "正在读取分区 " + requestedPartition + " 的受限预览…"
                : "正在加载受限数据预览…");
        previewWorker = new SwingWorker<>() {
            @Override
            protected QueryResult doInBackground() throws Exception {
                if (isMaxCompute() && Boolean.TRUE.equals(partitioned)) {
                    return runtime.connectionManager().previewPartition(
                            connectionId, schema, table,
                            requestedPartition, previewLimit());
                }
                return runtime.connectionManager().previewTable(
                        connectionId, schema, table, previewLimit());
            }

            @Override
            protected void done() {
                if (isCancelled() || request != generation) {
                    return;
                }
                Map<String, String> errors = new LinkedHashMap<>(
                        currentSnapshot.errors());
                try {
                    QueryResult preview = get();
                    errors.remove("preview");
                    currentSnapshot = new TableSnapshot(
                            currentSnapshot.previewSql(), preview,
                            currentSnapshot.columns(),
                            currentSnapshot.constraints(),
                            currentSnapshot.ddl(),
                            currentSnapshot.tableComment(), errors);
                } catch (Exception exception) {
                    errors.put("preview", safeMessage(exception));
                    currentSnapshot = new TableSnapshot(
                            currentSnapshot.previewSql(), null,
                            currentSnapshot.columns(),
                            currentSnapshot.constraints(),
                            currentSnapshot.ddl(),
                            currentSnapshot.tableComment(), errors);
                } finally {
                    apply(currentSnapshot);
                    loadPreviewButton.setEnabled(previewAllowed(
                            isMaxCompute(), currentSnapshot != null,
                            partitioned, selectedPartitionSpec));
                }
            }
        };
        previewWorker.execute();
    }

    private JPanel createHeader() {
        JPanel header = UiKit.glass(new BorderLayout(14, 0), 14);
        header.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 14));

        JLabel icon = new JLabel(LyraIcons.treeNode(
                objectType, Map.of(), 30));
        header.add(icon, BorderLayout.WEST);

        JPanel identity = new JPanel();
        identity.setOpaque(false);
        identity.setLayout(new BoxLayout(identity, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(qualifiedName());
        title.setToolTipText(qualifiedName());
        title.setFont(NativeTheme.FONT_TITLE.deriveFont(Font.BOLD, 17F));
        title.setForeground(NativeTheme.FOREGROUND);
        JLabel subtitle = new JLabel(
                connectionName + "  ·  " + dbType
                        + (isMaxCompute()
                        ? "  ·  分区表需选完整分区；非分区表按需受限预览"
                        : "  ·  只读预览，最多 " + previewLimit() + " 行"));
        subtitle.setFont(NativeTheme.FONT_CAPTION);
        subtitle.setForeground(NativeTheme.MUTED);
        tableCommentLabel.setFont(NativeTheme.FONT_CAPTION);
        tableCommentLabel.setForeground(NativeTheme.FOREGROUND);
        metadataStateLabel.setFont(NativeTheme.FONT_CAPTION);
        metadataStateLabel.setForeground(NativeTheme.MUTED);
        JPanel badges = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        badges.setOpaque(false);
        badges.add(UiKit.badge(
                objectType,
                "VIEW".equalsIgnoreCase(objectType)
                        ? NativeTheme.SUCCESS : NativeTheme.ACCENT_LIGHT,
                NativeTheme.SURFACE_ALT));
        badges.add(columnBadge);
        badges.add(rowBadge);
        identity.add(title);
        identity.add(Box.createVerticalStrut(4));
        identity.add(subtitle);
        identity.add(Box.createVerticalStrut(4));
        identity.add(tableCommentLabel);
        identity.add(Box.createVerticalStrut(3));
        identity.add(metadataStateLabel);
        identity.add(Box.createVerticalStrut(8));
        identity.add(badges);
        header.add(identity, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(refreshButton);
        actions.add(openSqlButton);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private JTabbedPane createTabs() {
        previewTable.setModel(previewModel);
        previewTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        columnsTable.setModel(columnsModel);
        constraintsTable.setModel(constraintsModel);
        partitionsTable.setModel(partitionsModel);
        partitionsTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        detailTabs = new JTabbedPane();
        detailTabs.setOpaque(false);
        detailTabs.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        if (isMaxCompute()) {
            detailTabs.addTab("概览", LyraIcons.of(
                    LyraIcons.Kind.DATABASE, NativeTheme.ACCENT_LIGHT),
                    overviewTab());
            partitionPanel = partitionTab();
            detailTabs.addTab("分区", LyraIcons.of(
                    LyraIcons.Kind.PARTITION, NativeTheme.WARNING),
                    partitionPanel);
            detailTabs.addTab("字段", LyraIcons.of(
                    LyraIcons.Kind.COLUMN, NativeTheme.ACCENT_LIGHT),
                    tableTab(columnsTable));
            previewPanel = dataTab();
            detailTabs.addTab("数据预览", LyraIcons.of(
                    LyraIcons.Kind.TABLE, NativeTheme.ACCENT_LIGHT),
                    previewPanel);
        } else {
            previewPanel = dataTab();
            detailTabs.addTab("数据预览", LyraIcons.of(
                    LyraIcons.Kind.TABLE, NativeTheme.ACCENT_LIGHT),
                    previewPanel);
            detailTabs.addTab("字段结构", LyraIcons.of(
                    LyraIcons.Kind.COLUMN, NativeTheme.ACCENT_LIGHT),
                    tableTab(columnsTable));
            detailTabs.addTab("索引 / 约束", LyraIcons.of(
                    LyraIcons.Kind.INDEX, NativeTheme.WARNING),
                    tableTab(constraintsTable));
        }
        detailTabs.addTab("DDL", LyraIcons.of(
                LyraIcons.Kind.SQL, NativeTheme.MUTED),
                ddlTab());
        detailTabs.addChangeListener(event -> {
            if (isMaxCompute()
                    && detailTabs.getSelectedComponent() == partitionPanel
                    && !partitionsLoaded
                    && (partitionWorker == null
                    || partitionWorker.isDone())) {
                loadPartitions(0);
            }
        });
        return detailTabs;
    }

    private JPanel overviewTab() {
        JPanel grid = new JPanel(new GridLayout(2, 2, 8, 8));
        grid.setOpaque(false);
        grid.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        grid.add(summaryCard("表注释", overviewComment));
        grid.add(summaryCard("元数据可信度", overviewMetadata));
        grid.add(summaryCard("分区策略", overviewPartitions));
        grid.add(summaryCard("字段结构", overviewColumns));
        return grid;
    }

    private JPanel summaryCard(String title, JLabel valueLabel) {
        JPanel card = UiKit.glass(new BorderLayout(0, 6), 10);
        card.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(NativeTheme.FONT_CAPTION_BOLD);
        titleLabel.setForeground(NativeTheme.MUTED);
        valueLabel.setFont(NativeTheme.FONT_BODY);
        valueLabel.setForeground(NativeTheme.FOREGROUND);
        card.add(titleLabel, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    private JPanel partitionTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setOpaque(false);
        partitionState.setFont(NativeTheme.FONT_CAPTION);
        partitionState.setForeground(NativeTheme.MUTED);
        toolbar.add(partitionState, BorderLayout.WEST);

        partitionFilter.putClientProperty(
                "JTextField.placeholderText",
                "筛选当前页分区，如 ds=20260814");
        partitionFilter.putClientProperty("JTextField.leadingIcon",
                LyraIcons.of(LyraIcons.Kind.SEARCH, NativeTheme.MUTED));
        partitionFilter.putClientProperty(
                "JTextField.showClearButton", true);
        partitionFilter.setPreferredSize(new java.awt.Dimension(260, 32));
        partitionFilter.setToolTipText(
                "仅筛选当前已加载的 100 个分区，不触发全量扫描");
        partitionFilter.getDocument().addDocumentListener(
                new javax.swing.event.DocumentListener() {
                    @Override public void insertUpdate(
                            javax.swing.event.DocumentEvent event) {
                        filterPartitions();
                    }
                    @Override public void removeUpdate(
                            javax.swing.event.DocumentEvent event) {
                        filterPartitions();
                    }
                    @Override public void changedUpdate(
                            javax.swing.event.DocumentEvent event) {
                        filterPartitions();
                    }
                });
        reloadPartitionsButton.addActionListener(
                event -> loadPartitions(partitionOffset));
        latestPartitionButton.addActionListener(event -> selectLatestPartition());
        latestPartitionButton.setToolTipText(
                "快捷选择当前服务端顺序中的首个分区；不提供时间排序保证");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(partitionFilter);
        actions.add(reloadPartitionsButton);
        actions.add(latestPartitionButton);
        toolbar.add(actions, BorderLayout.EAST);
        panel.add(toolbar, BorderLayout.NORTH);

        partitionsTable.getColumnModel().getColumn(0).setPreferredWidth(58);
        partitionsTable.getColumnModel().getColumn(1).setPreferredWidth(420);
        partitionsTable.getColumnModel().getColumn(2).setPreferredWidth(520);
        partitionsTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                activateSelectedPartition(false);
            }
        });
        partitionsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    activateSelectedPartition(true);
                }
            }
        });
        panel.add(scroll(partitionsTable), BorderLayout.CENTER);

        previousPartitionPage.addActionListener(event -> loadPartitions(
                Math.max(0, partitionOffset - PARTITION_PAGE_SIZE)));
        nextPartitionPage.addActionListener(event -> loadPartitions(
                partitionOffset + PARTITION_PAGE_SIZE));
        previousPartitionPage.setEnabled(false);
        nextPartitionPage.setEnabled(false);
        selectedPartitionLabel.setFont(NativeTheme.FONT_CAPTION_BOLD);
        selectedPartitionLabel.setForeground(NativeTheme.ACCENT_LIGHT);
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
        footer.add(selectedPartitionLabel, BorderLayout.WEST);
        JPanel pager = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        pager.setOpaque(false);
        partitionPage.setFont(NativeTheme.FONT_CAPTION);
        partitionPage.setForeground(NativeTheme.MUTED);
        pager.add(previousPartitionPage);
        pager.add(partitionPage);
        pager.add(nextPartitionPage);
        footer.add(pager, BorderLayout.EAST);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private void loadPartitions(int requestedOffset) {
        if (!isMaxCompute()) {
            return;
        }
        if (partitionWorker != null && !partitionWorker.isDone()) {
            partitionWorker.cancel(true);
        }
        int offset = Math.max(0, requestedOffset);
        long request = generation;
        setPartitionLoading(true);
        partitionState.setText("正在按页读取分区，不扫描业务数据…");
        statusSink.accept("正在读取 MaxCompute 分区：" + qualifiedName());
        partitionWorker = new SwingWorker<>() {
            @Override
            protected PartitionMetadataPage doInBackground()
                    throws Exception {
                return runtime.connectionManager().partitions(
                        connectionId, schema, table,
                        offset, PARTITION_PAGE_SIZE);
            }

            @Override
            protected void done() {
                if (isCancelled() || request != generation) {
                    return;
                }
                try {
                    applyPartitionPage(get());
                    partitionsLoaded = true;
                } catch (Exception exception) {
                    String message = safeMessage(exception);
                    partitionState.setText("分区元数据不可用：" + message);
                    partitionState.setForeground(NativeTheme.ERROR);
                    overviewPartitions.setText("分区元数据不可用");
                    overviewPartitions.setToolTipText(message);
                    statusSink.accept("分区读取失败：" + message);
                } finally {
                    setPartitionLoading(false);
                }
            }
        };
        partitionWorker.execute();
    }

    private void applyPartitionPage(PartitionMetadataPage page) {
        PartitionMetadataPage safePage = page == null
                ? new PartitionMetadataPage() : page;
        partitionOffset = Math.max(0, safePage.getOffset());
        partitionHasMore = safePage.isHasMore();
        partitioned = safePage.isPartitioned();
        partitionsModel.setPage(safePage.getItems());
        filterPartitions();

        int pageNumber = partitionOffset / PARTITION_PAGE_SIZE + 1;
        partitionPage.setText("第 " + pageNumber + " 页 · 本页 "
                + safePage.getItems().size() + " 项");
        previousPartitionPage.setEnabled(partitionOffset > 0);
        nextPartitionPage.setEnabled(partitionHasMore);
        latestPartitionButton.setEnabled(!safePage.getItems().isEmpty());
        boolean specDescending = "PARTITION_SPEC_DESC".equalsIgnoreCase(
                safePage.getOrdering());
        latestPartitionButton.setText(specDescending
                ? "选择倒序首项" : "选择首项");
        latestPartitionButton.setToolTipText(specDescending
                ? "按分区规格倒序选择首项；分区规格未必是日期，不代表最后更新时间"
                : "选择服务端当前顺序的首项；该顺序不代表最后更新时间");

        String keys = safePage.getPartitionKeys().isEmpty()
                ? "未返回分区键"
                : String.join(" / ", safePage.getPartitionKeys());
        String metadata = metadataSummary(
                safePage.getMetadataSource(),
                safePage.getMetadataStatus(),
                safePage.getMetadataReason());
        if (safePage.isPartitioned()
                && (selectedPartitionSpec == null
                || selectedPartitionSpec.isBlank())) {
            loadPreviewButton.setText(
                    "选择分区后加载前 " + MAXCOMPUTE_PREVIEW_LIMIT + " 行");
            loadPreviewButton.setEnabled(false);
        }
        if (!safePage.isPartitioned()) {
            partitionState.setText("已确认当前表未声明分区；数据仍需手动点击后按上限预览");
            overviewPartitions.setText("非分区表 · 可手动加载受限预览");
            loadPreviewButton.setText(
                    "加载前 " + MAXCOMPUTE_PREVIEW_LIMIT + " 行");
            loadPreviewButton.setEnabled(currentSnapshot != null);
        } else if (safePage.getItems().isEmpty()) {
            partitionState.setText("当前页没有分区 · " + metadata);
            overviewPartitions.setText("分区键：" + keys + " · 当前页为空");
        } else {
            partitionState.setText("分区键：" + keys + " · " + metadata);
            overviewPartitions.setText("分区键：" + keys
                    + " · 已按页加载 " + safePage.getItems().size() + " 项");
        }
        partitionState.setForeground("COMPLETE".equalsIgnoreCase(
                safePage.getMetadataStatus())
                ? NativeTheme.MUTED : NativeTheme.WARNING);
        partitionState.setToolTipText(safePage.getMetadataReason());
        overviewPartitions.setToolTipText(safePage.getMetadataReason());

        partitionMetadataSummary = "分区：" + metadata;
        updateOverviewMetadata();
        if (selectLatestAfterLoad && !safePage.getItems().isEmpty()) {
            selectLatestAfterLoad = false;
            partitionsTable.setRowSelectionInterval(0, 0);
            activateSelectedPartition(false);
        } else if (safePage.getItems().isEmpty()) {
            selectLatestAfterLoad = false;
        }
    }

    private void setPartitionLoading(boolean loading) {
        reloadPartitionsButton.setEnabled(!loading);
        latestPartitionButton.setEnabled(
                !loading && partitionsModel.hasPageItems());
        previousPartitionPage.setEnabled(!loading && partitionOffset > 0);
        nextPartitionPage.setEnabled(!loading && partitionHasMore);
        partitionFilter.setEnabled(!loading);
    }

    private void filterPartitions() {
        partitionsModel.setFilter(partitionFilter.getText());
        if (partitionsLoaded && partitionWorker != null
                && partitionWorker.isDone()) {
            partitionState.setText(partitionFilter.getText().isBlank()
                    ? "当前页显示 " + partitionsModel.getRowCount() + " 个分区"
                    : "当前页筛选匹配 " + partitionsModel.getRowCount()
                    + " 个分区");
        }
    }

    private void selectLatestPartition() {
        selectLatestAfterLoad = true;
        if (partitionOffset == 0 && partitionsLoaded
                && partitionsModel.getRowCount() > 0) {
            selectLatestAfterLoad = false;
            partitionFilter.setText("");
            partitionsTable.setRowSelectionInterval(0, 0);
            activateSelectedPartition(false);
            return;
        }
        partitionFilter.setText("");
        loadPartitions(0);
    }

    private void activateSelectedPartition(boolean openPreview) {
        int viewRow = partitionsTable.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        int modelRow = partitionsTable.convertRowIndexToModel(viewRow);
        PartitionMetadata selected = partitionsModel.itemAt(modelRow);
        if (selected == null || selected.getSpec() == null
                || selected.getSpec().isBlank()) {
            return;
        }
        selectedPartitionSpec = selected.getSpec();
        selectedPartitionLabel.setText(
                "已选分区：" + selectedPartitionSpec);
        selectedPartitionLabel.setToolTipText(selectedPartitionSpec);
        previewPartitionLabel.setText(
                "当前分区：" + selectedPartitionSpec);
        previewPartitionLabel.setToolTipText(selectedPartitionSpec);
        loadPreviewButton.setText(
                "加载该分区前 " + MAXCOMPUTE_PREVIEW_LIMIT + " 行");
        loadPreviewButton.setEnabled(currentSnapshot != null);
        preparePartitionPreviewSql(selectedPartitionSpec);
        if (openPreview && detailTabs != null && previewPanel != null) {
            detailTabs.setSelectedComponent(previewPanel);
        }
    }

    private void preparePartitionPreviewSql(String partitionSpec) {
        if (partitionSqlWorker != null && !partitionSqlWorker.isDone()) {
            partitionSqlWorker.cancel(true);
        }
        long request = generation;
        previewSql = null;
        openSqlButton.setEnabled(false);
        stateLabel.setText("已选择分区，正在准备安全预览 SQL…");
        partitionSqlWorker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                return runtime.connectionManager().partitionPreviewSql(
                        connectionId, schema, table,
                        partitionSpec, previewLimit());
            }

            @Override
            protected void done() {
                if (isCancelled() || request != generation
                        || !partitionSpec.equals(selectedPartitionSpec)) {
                    return;
                }
                try {
                    previewSql = get();
                    if (currentSnapshot != null) {
                        currentSnapshot = new TableSnapshot(
                                previewSql, currentSnapshot.preview(),
                                currentSnapshot.columns(),
                                currentSnapshot.constraints(),
                                currentSnapshot.ddl(),
                                currentSnapshot.tableComment(),
                                currentSnapshot.errors());
                    }
                    openSqlButton.setEnabled(previewSql != null
                            && !previewSql.isBlank());
                    stateLabel.setText("已选择分区 " + partitionSpec
                            + " · 尚未执行数据查询");
                } catch (Exception exception) {
                    previewSql = null;
                    stateLabel.setText("无法准备分区预览："
                            + safeMessage(exception));
                    statusSink.accept("分区预览 SQL 生成失败，未执行查询");
                }
            }
        };
        partitionSqlWorker.execute();
    }

    private JPanel dataTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
        stateLabel.setFont(NativeTheme.FONT_CAPTION);
        stateLabel.setForeground(NativeTheme.MUTED);
        if (isMaxCompute()) {
            JPanel previewState = new JPanel();
            previewState.setOpaque(false);
            previewState.setLayout(new BoxLayout(
                    previewState, BoxLayout.Y_AXIS));
            previewPartitionLabel.setFont(NativeTheme.FONT_CAPTION_BOLD);
            previewPartitionLabel.setForeground(NativeTheme.ACCENT_LIGHT);
            previewState.add(previewPartitionLabel);
            previewState.add(Box.createVerticalStrut(2));
            previewState.add(stateLabel);
            bar.add(previewState, BorderLayout.WEST);
        } else {
            bar.add(stateLabel, BorderLayout.WEST);
        }
        JPanel displayTools = new JPanel(new FlowLayout(
                FlowLayout.RIGHT, 8, 0));
        displayTools.setOpaque(false);
        JLabel note = new JLabel(isMaxCompute()
                ? "分区表仅查询已选分区；非分区表按上限读取"
                : "为保护数据库，预览不会执行全表导出",
                SwingConstants.RIGHT);
        note.setFont(NativeTheme.FONT_CAPTION);
        note.setForeground(NativeTheme.MUTED);
        JLabel displayLabel = new JLabel("表头");
        displayLabel.setFont(NativeTheme.FONT_CAPTION);
        displayLabel.setForeground(NativeTheme.MUTED);
        displayTools.add(note);
        if (isMaxCompute()) {
            displayTools.add(choosePartitionButton);
        }
        displayTools.add(loadPreviewButton);
        displayTools.add(Box.createHorizontalStrut(8));
        displayTools.add(displayLabel);
        displayTools.add(fieldDisplayMode);
        bar.add(displayTools, BorderLayout.EAST);
        panel.add(bar, BorderLayout.NORTH);
        panel.add(scroll(previewTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel tableTab(JTable table) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        panel.add(scroll(table), BorderLayout.CENTER);
        return panel;
    }

    private JPanel ddlTab() {
        ddlArea.setEditable(false);
        ddlArea.setLineWrap(false);
        ddlArea.setTabSize(4);
        UiKit.makeMonospaced(ddlArea);
        ddlArea.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JButton copy = UiKit.button(
                "复制 DDL", LyraIcons.of(LyraIcons.Kind.COPY),
                UiKit.ButtonStyle.SECONDARY);
        copy.addActionListener(event -> {
            if (!ddlArea.getText().isBlank()) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(
                                ddlArea.getText()), null);
                statusSink.accept("DDL 已复制");
            }
        });
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 8));
        bar.setOpaque(false);
        bar.add(copy);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        panel.add(bar, BorderLayout.NORTH);
        panel.add(scroll(ddlArea), BorderLayout.CENTER);
        return panel;
    }

    private void apply(TableSnapshot snapshot) {
        currentSnapshot = snapshot;
        updateCommentPresentation(snapshot.tableComment(),
                snapshot.errors().get("tableComment"));
        QueryResult preview = snapshot.preview();
        if (!isMaxCompute() || (snapshot.previewSql() != null
                && !snapshot.previewSql().isBlank())
                || selectedPartitionSpec == null) {
            previewSql = snapshot.previewSql();
        }
        if (preview != null) {
            if (previewSql == null || previewSql.isBlank()) {
                previewSql = preview.getSql();
            }
            List<List<Object>> rows = new ArrayList<>();
            for (Map<String, Object> row : preview.getRows()) {
                List<Object> values = new ArrayList<>();
                for (String column : preview.getColumns()) {
                    values.add(row.get(column));
                }
                rows.add(values);
            }
            List<String> headers = displayHeaders(
                    preview.getColumns(), snapshot.columns());
            previewModel.setData(headers, rows);
            configurePreviewWidths(headers);
            rowBadge.setText("  " + preview.getRows().size()
                    + (preview.isTruncated() ? "+ 行  " : " 行  "));
            stateLabel.setText(preview.getRows().size() + " 行"
                    + (preview.isTruncated() ? "（已按上限截断）" : "")
                    + "  ·  " + preview.getElapsedMs() + " ms");
        } else if (snapshot.errors().containsKey("preview")) {
            String previewError = snapshot.errors().getOrDefault(
                    "preview", "当前驱动不支持");
            previewModel.setData(List.of("状态"), List.of(List.of(
                    previewFailureMessage(dbType, previewError))));
            previewTable.setToolTipText(previewError);
            rowBadge.setText("  0 行  ");
            stateLabel.setText("数据预览不可用 · 可在 SQL 中打开并调整条件");
        } else if (isMaxCompute()) {
            String previewMessage = Boolean.FALSE.equals(partitioned)
                    ? "已确认非分区表；点击加载后仅按上限读取前 100 行"
                    : selectedPartitionSpec == null
                    || selectedPartitionSpec.isBlank()
                    ? "未选择分区：不会执行查询。请先到“分区”页选择分区"
                    : "已选择分区 " + selectedPartitionSpec
                    + "；点击加载后仅预览该分区前 100 行";
            previewModel.setData(List.of("状态"), List.of(List.of(
                    previewMessage)));
            rowBadge.setText("  未加载  ");
            stateLabel.setText(Boolean.FALSE.equals(partitioned)
                    ? "已确认非分区表 · 尚未执行数据查询"
                    : selectedPartitionSpec == null
                    ? "元数据已就绪 · 选择分区前不会执行预览"
                    : "已选择分区 · 尚未执行数据查询");
        } else {
            stateLabel.setText("元数据已就绪 · 正在准备数据预览");
        }

        applyColumnRows(snapshot.columns());
        columnBadge.setText("  " + snapshot.columns().size() + " 列  ");
        overviewColumns.setText(snapshot.columns().isEmpty()
                ? "字段元数据未返回"
                : snapshot.columns().size() + " 个字段 · "
                + partitionFieldCount(snapshot.columns()) + " 个分区字段");

        List<List<Object>> constraintRows = new ArrayList<>();
        for (TableConstraintMetadata constraint : snapshot.constraints()) {
            constraintRows.add(List.of(
                    constraintType(constraint.getType()),
                    value(constraint.getName()),
                    String.join(", ", constraint.getColumns()),
                    value(constraint.getReferencedTable()),
                    String.join(", ", constraint.getReferencedColumns())));
        }
        constraintsModel.setData(
                constraintsModel.columns(), constraintRows);

        if (!snapshot.ddl().isBlank()) {
            ddlArea.setText(snapshot.ddl());
            ddlArea.setCaretPosition(0);
        } else {
            ddlArea.setText("无法读取 DDL："
                    + snapshot.errors().getOrDefault(
                    "ddl", "当前对象不提供 DDL"));
        }
        openSqlButton.setEnabled(previewSql != null
                && !previewSql.isBlank());
        loadPreviewButton.setEnabled(previewAllowed(
                isMaxCompute(), currentSnapshot != null,
                partitioned, selectedPartitionSpec));

        if (snapshot.errors().isEmpty()) {
            statusSink.accept("表工作台已加载：" + qualifiedName());
        } else {
            statusSink.accept("表工作台已加载，部分信息不可用："
                    + String.join("、", snapshot.errors().keySet()));
        }
    }

    private void applyFieldDisplay(TableSnapshot snapshot) {
        QueryResult preview = snapshot.preview();
        if (preview != null) {
            List<List<Object>> rows = new ArrayList<>();
            for (Map<String, Object> row : preview.getRows()) {
                List<Object> values = new ArrayList<>();
                for (String column : preview.getColumns()) {
                    values.add(row.get(column));
                }
                rows.add(values);
            }
            List<String> headers = displayHeaders(
                    preview.getColumns(), snapshot.columns());
            previewModel.setData(headers, rows);
            configurePreviewWidths(headers);
        }
        applyColumnRows(snapshot.columns());
        statusSink.accept("字段显示已切换为："
                + fieldDisplayMode.getSelectedItem());
    }

    private List<String> displayHeaders(
            List<String> physicalColumns,
            List<ColumnMetadata> metadata) {
        Map<String, String> remarks = new LinkedHashMap<>();
        for (ColumnMetadata column : metadata) {
            if (column.getRemarks() != null
                    && !column.getRemarks().isBlank()) {
                remarks.put(column.getName(), column.getRemarks());
            }
        }
        FieldDisplayMode mode = (FieldDisplayMode)
                fieldDisplayMode.getSelectedItem();
        return SqlWorkspacePanel.displayHeaders(
                physicalColumns, remarks,
                mode == null ? FieldDisplayMode.PHYSICAL : mode);
    }

    private void applyColumnRows(List<ColumnMetadata> columns) {
        FieldDisplayMode mode = (FieldDisplayMode)
                fieldDisplayMode.getSelectedItem();
        FieldDisplayMode safeMode = mode == null
                ? FieldDisplayMode.PHYSICAL : mode;
        List<List<Object>> columnRows = new ArrayList<>();
        int index = 1;
        for (ColumnMetadata column : columns) {
            columnRows.add(List.of(
                    index++,
                    value(safeMode.title(
                            column.getName(), column.getRemarks())),
                    value(column.getTypeName()),
                    size(column),
                    column.isNullable() ? "YES" : "NO",
                    value(column.getDefaultValue()),
                    column.isPrimaryKey() ? "PK" : "",
                    column.isAutoIncrement() ? "YES" : "",
                    value(column.getRemarks())));
        }
        columnsModel.setData(columnsModel.columns(), columnRows);
    }

    private void openSql() {
        if (previewSql == null || previewSql.isBlank()) {
            statusSink.accept("数据预览尚未生成 SELECT");
            return;
        }
        sqlOpener.accept(previewSql);
    }

    private void setLoading(boolean loading) {
        refreshButton.setEnabled(!loading);
        loadPreviewButton.setEnabled(!loading && previewAllowed(
                isMaxCompute(), currentSnapshot != null,
                partitioned, selectedPartitionSpec));
        openSqlButton.setEnabled(!loading
                && previewSql != null && !previewSql.isBlank());
        if (loading) {
            stateLabel.setText("正在读取数据、字段与约束…");
        }
    }

    private void updateCommentPresentation(
            TableCommentMetadata refreshed, String refreshError) {
        CommentPresentation presentation = commentPresentation(
                initialMetadata, refreshed, refreshError);
        tableCommentLabel.setText(presentation.headerText());
        tableCommentLabel.setToolTipText(presentation.details());
        metadataStateLabel.setText("元数据："
                + presentation.metadataSummary());
        metadataStateLabel.setToolTipText(presentation.details());
        overviewComment.setText(presentation.overviewText());
        overviewComment.setToolTipText(presentation.details());
        commentMetadataSummary = presentation.metadataSummary();
        updateOverviewMetadata();
        overviewMetadata.setToolTipText(presentation.details());
        boolean unavailable = presentation.unavailable();
        tableCommentLabel.setForeground(unavailable
                ? NativeTheme.WARNING : NativeTheme.FOREGROUND);
        metadataStateLabel.setForeground(unavailable
                ? NativeTheme.WARNING : NativeTheme.MUTED);
    }

    private void updateOverviewMetadata() {
        if (commentMetadataSummary.isBlank()) {
            overviewMetadata.setText(partitionMetadataSummary);
        } else if (partitionMetadataSummary.isBlank()) {
            overviewMetadata.setText(commentMetadataSummary);
        } else {
            overviewMetadata.setText(commentMetadataSummary
                    + " · " + partitionMetadataSummary);
        }
    }

    static CommentPresentation commentPresentation(
            MetadataHint hint,
            TableCommentMetadata refreshed,
            String refreshError) {
        MetadataHint safeHint = hint == null
                ? MetadataHint.empty() : hint;
        String source = refreshed == null
                ? safeHint.metadataSource()
                : refreshed.getMetadataSource();
        String status = refreshed == null
                ? safeHint.metadataStatus()
                : refreshed.getMetadataStatus();
        String reason = refreshed == null
                ? safeHint.metadataReason()
                : refreshed.getMetadataReason();
        String remarksStatus = refreshed == null
                ? safeHint.remarksStatus()
                : refreshed.getRemarksStatus();
        String remarks = refreshed == null
                ? safeHint.comment() : refreshed.getRemarks();

        if (refreshError != null && !refreshError.isBlank()) {
            if (!safeHint.comment().isBlank()) {
                remarks = safeHint.comment();
                source = safeHint.metadataSource();
                status = "PARTIAL";
                remarksStatus = "AVAILABLE";
                reason = "单表注释刷新失败，沿用目录注释："
                        + refreshError;
            } else {
                remarksStatus = "UNAVAILABLE";
                status = "PARTIAL";
                reason = refreshError;
            }
        } else if ((remarks == null || remarks.isBlank())
                && "UNAVAILABLE".equalsIgnoreCase(remarksStatus)
                && !safeHint.comment().isBlank()) {
            remarks = safeHint.comment();
            source = safeHint.metadataSource();
            status = "PARTIAL";
            remarksStatus = "AVAILABLE";
            reason = "单表接口未返回注释，沿用目录中已取得的注释";
        }

        String header;
        String overview;
        boolean unavailable;
        if (remarks != null && !remarks.isBlank()) {
            header = "表注释：" + remarks.trim();
            overview = remarks.trim();
            unavailable = false;
        } else if ("EMPTY".equalsIgnoreCase(remarksStatus)) {
            header = "表注释：未设置（元数据已确认）";
            overview = "权威元数据确认当前表未设置注释";
            unavailable = false;
        } else if ("UNAVAILABLE".equalsIgnoreCase(remarksStatus)
                || refreshError != null && !refreshError.isBlank()) {
            header = "表注释元数据不可用"
                    + (reason == null || reason.isBlank()
                    ? "" : "：" + reason);
            overview = "暂时无法判断该表是否设置注释";
            unavailable = true;
        } else {
            header = "表注释尚未获取";
            overview = "注释状态尚未确认，请刷新元数据";
            unavailable = true;
        }
        String metadata = metadataSummary(source, status, reason);
        String details = "来源：" + fallback(source, "UNKNOWN")
                + " · 状态：" + fallback(status, "UNKNOWN")
                + " · 注释状态：" + fallback(remarksStatus, "UNKNOWN")
                + (reason == null || reason.isBlank()
                ? "" : " · 说明：" + reason);
        return new CommentPresentation(
                header, overview, metadata, details, unavailable);
    }

    private static String metadataSummary(
            String source, String status, String reason) {
        String translatedSource = switch (fallback(source, "UNKNOWN")
                .toUpperCase(Locale.ROOT)) {
            case "TENANT_INFORMATION_SCHEMA" -> "租户级 INFORMATION_SCHEMA";
            case "PROJECT_INFORMATION_SCHEMA" -> "项目级 INFORMATION_SCHEMA";
            case "MAXCOMPUTE_JAVA_SDK" -> "MaxCompute Java SDK";
            case "SHOW_TABLES" -> "SHOW TABLES 降级";
            case "JDBC_METADATA" -> "JDBC 元数据";
            default -> fallback(source, "未知来源");
        };
        String translatedStatus = switch (fallback(status, "UNKNOWN")
                .toUpperCase(Locale.ROOT)) {
            case "COMPLETE" -> "完整";
            case "PARTIAL" -> "部分可用";
            case "FALLBACK" -> "降级";
            default -> fallback(status, "状态未知");
        };
        String summary = translatedSource + " · " + translatedStatus;
        if (reason != null && !reason.isBlank()) {
            String compact = reason.replaceAll("\\s+", " ").trim();
            summary += " · " + (compact.length() <= 70
                    ? compact : compact.substring(0, 70) + "…");
        }
        return summary;
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int partitionFieldCount(List<ColumnMetadata> columns) {
        int count = 0;
        for (ColumnMetadata column : columns) {
            String remarks = column.getRemarks();
            if (remarks != null && remarks.contains("分区字段")) {
                count++;
            }
        }
        return count;
    }

    static boolean previewAllowed(
            boolean maxCompute,
            boolean metadataReady,
            Boolean partitioned,
            String partitionSpec) {
        return metadataReady && (!maxCompute
                || Boolean.FALSE.equals(partitioned)
                || Boolean.TRUE.equals(partitioned)
                && partitionSpec != null && !partitionSpec.isBlank());
    }

    private boolean isMaxCompute() {
        return "MAXCOMPUTE".equalsIgnoreCase(dbType);
    }

    private int previewLimit() {
        return isMaxCompute() ? MAXCOMPUTE_PREVIEW_LIMIT : PREVIEW_LIMIT;
    }

    private String qualifiedName() {
        if (schema == null || schema.isBlank()) {
            return table;
        }
        return schema.replace('/', '.') + "." + table;
    }

    private static JTable table() {
        JTable table = new JTable();
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setRowHeight(30);
        table.setShowVerticalLines(false);
        return table;
    }

    private static JScrollPane scroll(java.awt.Component component) {
        JScrollPane scroll = UiKit.scroll(component);
        scroll.setBorder(BorderFactory.createLineBorder(
                NativeTheme.BORDER_SOFT));
        return scroll;
    }

    private void configurePreviewWidths(List<String> columns) {
        for (int index = 0;
             index < columns.size()
                     && index < previewTable.getColumnModel().getColumnCount();
             index++) {
            int width = Math.max(120,
                    Math.min(280, columns.get(index).length() * 13 + 48));
            previewTable.getColumnModel().getColumn(index)
                    .setPreferredWidth(width);
        }
    }

    private static String size(ColumnMetadata column) {
        if (column.getColumnSize() <= 0) {
            return "—";
        }
        return column.getDecimalDigits() > 0
                ? column.getColumnSize() + ","
                + column.getDecimalDigits()
                : String.valueOf(column.getColumnSize());
    }

    private static String constraintType(String type) {
        return switch (type == null ? "" : type) {
            case "PRIMARY_KEY" -> "主键";
            case "FOREIGN_KEY" -> "外键";
            case "UNIQUE_INDEX" -> "唯一索引";
            case "INDEX" -> "普通索引";
            default -> value(type);
        };
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank()
                ? "—" : value.toString();
    }

    private static String safeMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank()
                ? root.getClass().getSimpleName() : message;
    }

    static String previewFailureMessage(String dbType, String error) {
        String message = error == null || error.isBlank()
                ? "当前驱动不支持" : error;
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        if ("MAXCOMPUTE".equalsIgnoreCase(dbType)
                && (normalized.contains("fullscan")
                || normalized.contains("partition"))) {
            return "MaxCompute 已阻止无分区全表扫描；请在 SQL 中添加分区条件。原始错误："
                    + message;
        }
        return "无法读取数据预览：" + message;
    }

    private record TableSnapshot(
            String previewSql,
            QueryResult preview,
            List<ColumnMetadata> columns,
            List<TableConstraintMetadata> constraints,
            String ddl,
            TableCommentMetadata tableComment,
            Map<String, String> errors) {
    }

    record MetadataHint(
            String comment,
            String metadataSource,
            String metadataStatus,
            String metadataReason,
            String remarksStatus,
            Boolean partitioned) {

        static MetadataHint fromNode(TreeNode node) {
            String rawPartitioned = DatabaseWorkspacePanel.nodeProperty(
                    node, "partitioned");
            Boolean partitioned = rawPartitioned.isBlank()
                    ? null : switch (rawPartitioned
                    .trim().toLowerCase(Locale.ROOT)) {
                        case "true", "1", "yes" -> true;
                        case "false", "0", "no" -> false;
                        default -> null;
                    };
            return new MetadataHint(
                    DatabaseWorkspacePanel.nodeComment(node),
                    DatabaseWorkspacePanel.nodeProperty(
                            node, "metadataSource"),
                    DatabaseWorkspacePanel.nodeProperty(
                            node, "metadataStatus"),
                    DatabaseWorkspacePanel.nodeProperty(
                            node, "metadataReason"),
                    DatabaseWorkspacePanel.nodeProperty(
                            node, "remarksStatus"),
                    partitioned);
        }

        static MetadataHint empty() {
            return new MetadataHint(
                    "", "", "", "", "UNAVAILABLE", null);
        }

        MetadataHint {
            comment = fallback(comment, "").trim();
            metadataSource = fallback(metadataSource, "").trim();
            metadataStatus = fallback(metadataStatus, "").trim();
            metadataReason = fallback(metadataReason, "").trim();
            remarksStatus = fallback(
                    remarksStatus, "UNAVAILABLE").trim();
        }
    }

    record CommentPresentation(
            String headerText,
            String overviewText,
            String metadataSummary,
            String details,
            boolean unavailable) {
    }

    private static final class PartitionTableModel
            extends AbstractTableModel {
        private static final List<String> COLUMNS = List.of(
                "页内序号", "分区规格", "分区键值");
        private List<PartitionMetadata> page = List.of();
        private List<PartitionMetadata> visible = List.of();
        private String filter = "";

        private void setPage(List<PartitionMetadata> items) {
            page = items == null ? List.of() : List.copyOf(items);
            applyFilter();
        }

        private void setFilter(String query) {
            filter = query == null ? ""
                    : query.trim().toLowerCase(Locale.ROOT);
            applyFilter();
        }

        private void applyFilter() {
            if (filter.isBlank()) {
                visible = page;
            } else {
                visible = page.stream()
                        .filter(item -> searchable(item).contains(filter))
                        .toList();
            }
            fireTableDataChanged();
        }

        private PartitionMetadata itemAt(int row) {
            return row < 0 || row >= visible.size()
                    ? null : visible.get(row);
        }

        private boolean hasPageItems() {
            return !page.isEmpty();
        }

        @Override
        public int getRowCount() {
            return visible.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.size();
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS.get(column);
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            PartitionMetadata item = visible.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> page.indexOf(item) + 1;
                case 1 -> item.getSpec();
                case 2 -> item.getValues().entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .collect(java.util.stream.Collectors.joining(" · "));
                default -> "";
            };
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }

        private static String searchable(PartitionMetadata item) {
            if (item == null) {
                return "";
            }
            return (fallback(item.getSpec(), "") + " "
                    + item.getValues()).toLowerCase(Locale.ROOT);
        }
    }

    private static final class ReadOnlyTableModel
            extends AbstractTableModel {
        private List<String> columns;
        private List<List<Object>> rows = List.of();

        private ReadOnlyTableModel(List<String> columns) {
            this.columns = List.copyOf(columns);
        }

        private List<String> columns() {
            return columns;
        }

        private void setData(
                List<String> columns, List<List<Object>> rows) {
            this.columns = List.copyOf(columns);
            this.rows = rows == null ? List.of() : List.copyOf(rows);
            fireTableStructureChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.size();
        }

        @Override
        public String getColumnName(int column) {
            return columns.get(column);
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            List<Object> row = rows.get(rowIndex);
            return columnIndex < row.size() ? row.get(columnIndex) : null;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }
    }
}
