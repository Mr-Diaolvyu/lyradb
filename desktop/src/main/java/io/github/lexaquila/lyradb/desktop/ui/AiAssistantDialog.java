package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.DesktopRuntime;
import io.github.lexaquila.lyradb.desktop.ai.AiContextComposer;
import io.github.lexaquila.lyradb.desktop.ai.AiTableSearchSupport;
import io.github.lexaquila.lyradb.desktop.ai.AiTask;
import io.github.lexaquila.lyradb.desktop.metadata.MetadataCapture;
import io.github.lexaquila.lyradb.desktop.metadata.MetadataContextService;
import io.github.lexaquila.lyradb.desktop.metadata.MetadataExportService;
import io.github.lexaquila.lyradb.desktop.metadata.MetadataSelection;
import io.github.lexaquila.lyradb.desktop.model.AiProfile;
import io.github.lexaquila.lyradb.metadata.snapshot.MetadataSnapshot;
import io.github.lexaquila.lyradb.metadata.snapshot.MetadataSnapshotRenderer;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 个人版智库助手。
 */
public final class AiAssistantDialog extends JDialog {

    private static final List<AiTask> QUICK_TASKS = List.of(
            AiTask.FIND_TABLE,
            AiTask.EXPLAIN_TABLE,
            AiTask.GENERATE,
            AiTask.OPTIMIZE,
            AiTask.FIX,
            AiTask.LINEAGE_IMPACT,
            AiTask.DATA_QUALITY);
    private static final List<AiTask> MORE_TASKS = List.of(
            AiTask.EXPLAIN,
            AiTask.REVIEW);

    private static final Pattern CODE_BLOCK =
            Pattern.compile("```[ \\t]*sql[ \\t]*\\R(.*?)```",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SQL_START =
            Pattern.compile("^(?:--[^\\r\\n]*(?:\\R|$)|/\\*.*?\\*/\\s*)*"
                            + "(?:SELECT|WITH|INSERT|UPDATE|DELETE|MERGE|CREATE|ALTER|"
                            + "DROP|TRUNCATE|EXPLAIN|SHOW|DESCRIBE|DESC|VALUES|CALL|EXEC|"
                            + "GRANT|REVOKE|USE|PRAGMA|SET|BEGIN|START|COMMIT|ROLLBACK)"
                            + "\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern REDIS_CODE_BLOCK =
            Pattern.compile("```[ \\t]*redis[ \\t]*\\R(.*?)```",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern REDIS_START =
            Pattern.compile("^(?:GET|KEYS|SCAN|TYPE|HGETALL|LRANGE|SMEMBERS|"
                            + "ZRANGE|STRLEN|DBSIZE|INFO|TTL|SET|DEL|EXPIRE|"
                            + "PERSIST|FLUSHDB)\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MONGO_CODE_BLOCK =
            Pattern.compile("```[ \\t]*(?:mongodb|mongo|json|javascript|js)"
                            + "[ \\t]*\\R(.*?)```",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MONGO_COMMAND =
            Pattern.compile("^(?:\\{.*}|[\\p{L}\\p{N}_$-]+[./]"
                            + "[\\p{L}\\p{N}_$-]+)$",
                    Pattern.DOTALL);

    private final DesktopRuntime runtime;
    private final Supplier<String> currentSql;
    private final Supplier<String> dbType;
    private final Consumer<String> insertSql;
    private final Supplier<MetadataSelection> metadataSelection;
    private final MetadataSnapshotRenderer metadataRenderer =
            new MetadataSnapshotRenderer();
    private final MetadataContextService metadataService;
    private final MetadataExportService metadataExporter;
    private final Map<AiTask, JButton> taskButtons =
            new EnumMap<>(AiTask.class);
    private final List<AbstractButton> taskControls = new ArrayList<>();
    private final JTextArea requestArea = new JTextArea();
    private final JTextArea schemaArea = new JTextArea();
    private final JTextArea currentSqlPreviewArea = new JTextArea();
    private final JTextArea responseArea = new JTextArea();
    private final JLabel taskTitleLabel = new JLabel();
    private final JLabel taskHintLabel = new JLabel();
    private final JLabel databaseSummary = contextBadge("数据库：未知");
    private final JLabel sqlSummary = contextBadge("当前 SQL：未选择");
    private final JLabel selectionSummary = contextBadge("当前选择：无");
    private final JLabel metadataContextSummary = contextBadge("元数据：未采集");
    private final JLabel statusLabel =
            new JLabel("不会自动执行或写入数据库 · 元数据需确认后附加");
    private final JLabel metadataSummary = new JLabel("未采集元数据");
    private final JButton collectMetadataButton =
            UiKit.button("读取当前选择", null, UiKit.ButtonStyle.TOOLBAR);
    private final JButton cancelMetadataButton =
            UiKit.button("取消", null, UiKit.ButtonStyle.TOOLBAR);
    private final JButton previewMetadataButton =
            UiKit.button("预览", null, UiKit.ButtonStyle.TOOLBAR);
    private final JButton attachMetadataButton =
            UiKit.button("附加", null, UiKit.ButtonStyle.TOOLBAR);
    private final JButton saveMetadataButton =
            UiKit.button("保存", null, UiKit.ButtonStyle.TOOLBAR);
    private final JButton clearMetadataButton =
            UiKit.button("清除", null, UiKit.ButtonStyle.TOOLBAR);
    private final JButton insertButton = UiKit.button(
            "插入查询编辑器", LyraIcons.of(LyraIcons.Kind.SQL),
            UiKit.ButtonStyle.SECONDARY);
    private final JButton copyButton = UiKit.button(
            "复制结果", LyraIcons.of(LyraIcons.Kind.COPY),
            UiKit.ButtonStyle.GHOST);
    private final JButton askButton = UiKit.button(
            "发送：智能找表", LyraIcons.of(LyraIcons.Kind.AI),
            UiKit.ButtonStyle.PRIMARY);
    private final JButton contextToggleButton = UiKit.button(
            "展开上下文", LyraIcons.of(LyraIcons.Kind.MORE),
            UiKit.ButtonStyle.TOOLBAR);
    private JPanel contextDetailsPanel;
    private JSplitPane conversationSplit;
    private AiTask selectedTask = AiTask.FIND_TABLE;
    private boolean contextDetailsVisible;
    private SwingWorker<RenderedMetadata, Void> metadataWorker;
    private MetadataCapture metadataCapture;
    private String metadataMarkdown = "";
    private String metadataJson = "";
    private boolean metadataAttached;

    public AiAssistantDialog(JFrame owner, DesktopRuntime runtime,
            Supplier<String> currentSql, Supplier<String> dbType,
            Consumer<String> insertSql) {
        this(owner, runtime, currentSql, dbType, insertSql, () -> null);
    }

    public AiAssistantDialog(JFrame owner, DesktopRuntime runtime,
            Supplier<String> currentSql, Supplier<String> dbType,
            Consumer<String> insertSql,
            Supplier<MetadataSelection> metadataSelection) {
        super(owner, "LyraDB · 天琴智库助手", false);
        this.runtime = runtime;
        this.currentSql = currentSql;
        this.dbType = dbType;
        this.insertSql = insertSql;
        this.metadataSelection = metadataSelection;
        this.metadataService =
                new MetadataContextService(runtime.connectionManager(), metadataRenderer);
        this.metadataExporter = new MetadataExportService(metadataRenderer);
        setIconImage(LyraIcons.applicationImage());
        buildUi(owner);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowActivated(WindowEvent event) {
                refreshContextSummary();
            }
        });
        setMinimumSize(new Dimension(860, 620));
        setSize(1040, 760);
        setLocationRelativeTo(owner);
    }

    private void buildUi(JFrame owner) {
        getContentPane().setBackground(NativeTheme.BACKGROUND);

        JPanel header = new JPanel(new BorderLayout(24, 0));
        header.setBackground(NativeTheme.SURFACE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        0, 0, 1, 0, NativeTheme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(14, 18, 14, 18)));

        JPanel identity = new JPanel(new BorderLayout(12, 0));
        identity.setOpaque(false);
        identity.add(new JLabel(LyraIcons.of(
                LyraIcons.Kind.AI, 32, NativeTheme.ACCENT_LIGHT)),
                BorderLayout.WEST);
        JPanel identityText = new JPanel();
        identityText.setOpaque(false);
        identityText.setLayout(new BoxLayout(identityText, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("智库助手");
        title.setFont(NativeTheme.FONT_TITLE);
        title.setForeground(NativeTheme.FOREGROUND);
        JLabel subtitle = new JLabel("找表、懂表、写 SQL、查影响，一个入口完成");
        subtitle.setFont(NativeTheme.FONT_CAPTION);
        subtitle.setForeground(NativeTheme.MUTED);
        identityText.add(title);
        identityText.add(Box.createVerticalStrut(3));
        identityText.add(subtitle);
        identity.add(identityText, BorderLayout.CENTER);
        header.add(identity, BorderLayout.CENTER);

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        tools.setOpaque(false);
        JButton settings = UiKit.button("模型设置",
                LyraIcons.of(LyraIcons.Kind.SETTINGS),
                UiKit.ButtonStyle.TOOLBAR);
        settings.addActionListener(event ->
                new AiSettingsDialog(owner, runtime).setVisible(true));
        tools.add(settings);
        header.add(tools, BorderLayout.EAST);

        configureTextAreas();
        JPanel requestSection = createRequestSection();
        JPanel responseSection = UiKit.section(
                "AI 建议",
                "结果仅供核对；不会自动进入编辑器，也不会自动执行或写库",
                textScroll(responseArea, 250));

        conversationSplit = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, requestSection, responseSection);
        conversationSplit.setResizeWeight(0.43);
        conversationSplit.setDividerLocation(285);
        conversationSplit.setDividerSize(10);
        conversationSplit.setBorder(BorderFactory.createEmptyBorder());

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBackground(NativeTheme.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        content.add(createContextSummaryPanel(), BorderLayout.NORTH);
        content.add(conversationSplit, BorderLayout.CENTER);

        askButton.addActionListener(event -> ask());
        askButton.setMnemonic('A');
        insertButton.setEnabled(false);
        insertButton.addActionListener(event -> insertResponse());
        copyButton.setEnabled(false);
        copyButton.addActionListener(event -> copyResponse());
        JButton close = UiKit.button("关闭",
                LyraIcons.of(LyraIcons.Kind.CLOSE),
                UiKit.ButtonStyle.GHOST);
        close.addActionListener(event -> dispose());

        JPanel footer = new JPanel(new BorderLayout(16, 0));
        footer.setBackground(NativeTheme.SURFACE);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        1, 0, 0, 0, NativeTheme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)));
        JPanel status = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 7));
        status.setOpaque(false);
        status.add(new JLabel(LyraIcons.of(
                LyraIcons.Kind.SHIELD, NativeTheme.SUCCESS)));
        statusLabel.setForeground(NativeTheme.MUTED);
        statusLabel.setFont(NativeTheme.FONT_CAPTION);
        status.add(statusLabel);
        footer.add(status, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(copyButton);
        actions.add(insertButton);
        actions.add(askButton);
        actions.add(close);
        footer.add(actions, BorderLayout.EAST);

        setLayout(new BorderLayout());
        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        UiKit.configureDialog(this, askButton);
        selectTask(selectedTask);
        refreshContextSummary();
    }

    private JPanel createRequestSection() {
        JPanel panel = UiKit.card(new BorderLayout(0, 12));
        panel.add(createQuickActionsPanel(), BorderLayout.NORTH);

        JPanel inputPanel = new JPanel(new BorderLayout(0, 7));
        inputPanel.setOpaque(false);
        taskTitleLabel.setFont(NativeTheme.FONT_TITLE);
        taskTitleLabel.setForeground(NativeTheme.FOREGROUND);
        taskTitleLabel.setLabelFor(requestArea);
        taskHintLabel.setFont(NativeTheme.FONT_CAPTION);
        taskHintLabel.setForeground(NativeTheme.MUTED);
        JPanel inputHeading = new JPanel();
        inputHeading.setOpaque(false);
        inputHeading.setLayout(new BoxLayout(inputHeading, BoxLayout.Y_AXIS));
        inputHeading.add(taskTitleLabel);
        inputHeading.add(Box.createVerticalStrut(3));
        inputHeading.add(taskHintLabel);
        inputPanel.add(inputHeading, BorderLayout.NORTH);
        inputPanel.add(textScroll(requestArea, 125), BorderLayout.CENTER);
        panel.add(inputPanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createQuickActionsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);
        JLabel label = new JLabel("快捷能力");
        label.setFont(NativeTheme.FONT_CAPTION_BOLD);
        label.setForeground(NativeTheme.FOREGROUND);
        panel.add(label, BorderLayout.NORTH);

        JPanel actions = new JPanel(new GridLayout(2, 4, 8, 8));
        actions.setOpaque(false);
        for (AiTask task : QUICK_TASKS) {
            JButton button = UiKit.button(task.displayName(), taskIcon(task),
                    UiKit.ButtonStyle.TOOLBAR);
            button.setToolTipText(task.requestHint());
            button.getAccessibleContext().setAccessibleName(
                    "选择能力：" + task.displayName());
            button.getAccessibleContext().setAccessibleDescription(
                    task.requestHint());
            button.addActionListener(event -> selectTask(task));
            taskButtons.put(task, button);
            taskControls.add(button);
            actions.add(button);
        }
        JButton more = UiKit.button("更多",
                LyraIcons.of(LyraIcons.Kind.MORE),
                UiKit.ButtonStyle.TOOLBAR);
        more.setToolTipText("解释 SQL 或执行安全审查");
        more.getAccessibleContext().setAccessibleName("更多 AI 能力");
        JPopupMenu menu = new JPopupMenu();
        for (AiTask task : MORE_TASKS) {
            JMenuItem item = new JMenuItem(task.displayName(), taskIcon(task));
            item.setToolTipText(task.requestHint());
            item.addActionListener(event -> selectTask(task));
            menu.add(item);
        }
        more.addActionListener(event -> menu.show(more, 0, more.getHeight()));
        taskControls.add(more);
        actions.add(more);
        panel.add(actions, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createContextSummaryPanel() {
        JPanel panel = UiKit.card(new BorderLayout(0, 10));
        JPanel heading = new JPanel(new BorderLayout(12, 0));
        heading.setOpaque(false);
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("本次上下文");
        title.setFont(NativeTheme.FONT_TITLE);
        title.setForeground(NativeTheme.FOREGROUND);
        JLabel hint = new JLabel(
                "当前 SQL 会自动携带；元数据只有在你确认附加后才会发送一次");
        hint.setFont(NativeTheme.FONT_CAPTION);
        hint.setForeground(NativeTheme.MUTED);
        text.add(title);
        text.add(Box.createVerticalStrut(3));
        text.add(hint);
        heading.add(text, BorderLayout.CENTER);
        contextToggleButton.addActionListener(event ->
                setContextDetailsVisible(!contextDetailsVisible));
        contextToggleButton.getAccessibleContext().setAccessibleDescription(
                "查看当前 SQL、补充业务口径，以及采集和附加只读元数据");
        heading.add(contextToggleButton, BorderLayout.EAST);
        panel.add(heading, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        JPanel summaries = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        summaries.setOpaque(false);
        summaries.add(databaseSummary);
        summaries.add(sqlSummary);
        summaries.add(selectionSummary);
        summaries.add(metadataContextSummary);
        body.add(summaries);
        body.add(Box.createVerticalStrut(8));
        contextDetailsPanel = createContextDetailsPanel();
        contextDetailsPanel.setVisible(false);
        body.add(contextDetailsPanel);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createContextDetailsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setOpaque(false);

        JPanel editors = new JPanel(new GridLayout(1, 2, 10, 0));
        editors.setOpaque(false);
        editors.add(contextEditor(
                "当前 SQL（自动随请求发送）",
                "只读预览；返回结果仍需手动插入编辑器",
                currentSqlPreviewArea));
        editors.add(contextEditor(
                "补充业务口径（可选）",
                "只填写已经确认的字段含义、过滤条件和业务口径",
                schemaArea));
        panel.add(editors, BorderLayout.CENTER);
        panel.add(createMetadataControls(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel contextEditor(String title, String hint, JTextArea area) {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setOpaque(false);
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(NativeTheme.FONT_CAPTION_BOLD);
        titleLabel.setForeground(NativeTheme.FOREGROUND);
        titleLabel.setLabelFor(area);
        JLabel hintLabel = new JLabel(hint);
        hintLabel.setFont(NativeTheme.FONT_CAPTION);
        hintLabel.setForeground(NativeTheme.MUTED);
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.add(titleLabel);
        heading.add(Box.createVerticalStrut(2));
        heading.add(hintLabel);
        panel.add(heading, BorderLayout.NORTH);
        panel.add(textScroll(area, 120), BorderLayout.CENTER);
        return panel;
    }

    private void configureTextAreas() {
        requestArea.setLineWrap(true);
        requestArea.setWrapStyleWord(true);
        requestArea.setMargin(new java.awt.Insets(10, 10, 10, 10));
        requestArea.getAccessibleContext().setAccessibleName("给 AI 的要求");
        requestArea.getAccessibleContext().setAccessibleDescription(
                "描述你想查找、理解、生成、优化或诊断的数据库问题");
        schemaArea.setLineWrap(true);
        schemaArea.setWrapStyleWord(true);
        schemaArea.setMargin(new java.awt.Insets(10, 10, 10, 10));
        schemaArea.setToolTipText("只填写已经确认的字段含义与业务口径");
        schemaArea.getAccessibleContext().setAccessibleName("补充业务口径");
        currentSqlPreviewArea.setEditable(false);
        currentSqlPreviewArea.setLineWrap(true);
        currentSqlPreviewArea.setWrapStyleWord(true);
        currentSqlPreviewArea.setMargin(new java.awt.Insets(10, 10, 10, 10));
        currentSqlPreviewArea.setText("（当前编辑器没有 SQL）");
        currentSqlPreviewArea.getAccessibleContext().setAccessibleName(
                "当前 SQL 只读预览");
        UiKit.makeMonospaced(currentSqlPreviewArea);
        responseArea.setEditable(false);
        responseArea.setLineWrap(true);
        responseArea.setWrapStyleWord(true);
        responseArea.setMargin(new java.awt.Insets(10, 10, 10, 10));
        responseArea.setFont(NativeTheme.FONT_BODY);
        responseArea.setText("选择一个快捷能力，再用自然语言描述你的目标。\n"
                + "AI 只会提供建议，不会自动执行 SQL 或写入数据库。");
        responseArea.getAccessibleContext().setAccessibleName("AI 建议结果");
    }

    private static JScrollPane textScroll(JTextArea area, int height) {
        JScrollPane scroll = UiKit.scroll(area);
        scroll.setPreferredSize(new Dimension(320, height));
        return scroll;
    }

    private JPanel createMetadataControls() {
        JPanel panel = new JPanel(new BorderLayout(8, 4));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        1, 0, 0, 0, NativeTheme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(9, 0, 0, 0)));
        metadataSummary.setFont(NativeTheme.FONT_CAPTION);
        metadataSummary.setForeground(NativeTheme.MUTED);
        panel.add(metadataSummary, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        collectMetadataButton.setMnemonic('M');
        collectMetadataButton.addActionListener(event -> collectMetadata());
        cancelMetadataButton.addActionListener(event -> cancelMetadata());
        previewMetadataButton.addActionListener(event -> previewMetadata());
        attachMetadataButton.addActionListener(event -> toggleMetadataAttachment());
        saveMetadataButton.addActionListener(event -> saveMetadata());
        clearMetadataButton.addActionListener(event -> clearMetadata());
        actions.add(collectMetadataButton);
        actions.add(cancelMetadataButton);
        actions.add(previewMetadataButton);
        actions.add(attachMetadataButton);
        actions.add(saveMetadataButton);
        actions.add(clearMetadataButton);
        panel.add(actions, BorderLayout.EAST);
        updateMetadataControls();
        return panel;
    }

    static List<AiTask> quickTasks() {
        return QUICK_TASKS;
    }

    static String formatSqlSummary(String sql) {
        if (sql == null || sql.isBlank()) {
            return "当前 SQL：未选择";
        }
        return "当前 SQL：已包含（" + sql.trim().length() + " 字符）";
    }

    static String formatSelectionSummary(MetadataSelection selection) {
        return selection == null
                ? "当前选择：无"
                : "当前选择：" + selection.displayScope();
    }

    static boolean isRequiredMetadataMissing(
            AiTask task, boolean metadataIncluded) {
        return task != null && task.requiresMetadata() && !metadataIncluded;
    }

    static List<AiTableSearchSupport.CatalogEntry> catalogEntries(
            MetadataSnapshot snapshot) {
        if (snapshot == null) {
            return List.of();
        }
        List<AiTableSearchSupport.CatalogEntry> entries = new ArrayList<>();
        for (MetadataSnapshot.DataSource source : snapshot.dataSources()) {
            for (MetadataSnapshot.Database database : source.databases()) {
                for (MetadataSnapshot.Schema schema : database.schemas()) {
                    String namespace = qualifiedNamespace(
                            database.name(), schema.name());
                    for (MetadataSnapshot.Table table : schema.tables()) {
                        String path = namespace.isBlank()
                                ? table.name()
                                : namespace + "." + table.name();
                        entries.add(new AiTableSearchSupport.CatalogEntry(
                                table.name(), table.type(), namespace,
                                table.remarks(), path));
                    }
                }
            }
        }
        return List.copyOf(entries);
    }

    private static String qualifiedNamespace(
            String database, String schema) {
        String safeDatabase = database == null ? "" : database.trim();
        String safeSchema = schema == null ? "" : schema.trim();
        if (safeDatabase.isBlank()) {
            return safeSchema;
        }
        if (safeSchema.isBlank()
                || safeDatabase.equalsIgnoreCase(safeSchema)) {
            return safeDatabase;
        }
        return safeDatabase + "." + safeSchema;
    }

    private static String formatTableRecommendations(
            List<AiTableSearchSupport.Recommendation> recommendations,
            AiTableSearchSupport.Prompt prompt, boolean aiRanked) {
        if (recommendations == null || recommendations.isEmpty()) {
            return "当前已附加的元数据中没有找到可推荐对象。"
                    + "可以换一个业务词，或扩大导航器中的元数据采集范围。";
        }
        StringBuilder result = new StringBuilder(aiRanked
                ? "基于已附加元数据，推荐以下表或视图：\n"
                : "AI 暂不可用，已按已附加元数据进行本地匹配：\n");
        for (int index = 0; index < recommendations.size(); index++) {
            AiTableSearchSupport.Recommendation recommendation =
                    recommendations.get(index);
            result.append(index + 1).append(". ")
                    .append(recommendation.entry().path())
                    .append(" — ").append(recommendation.reason())
                    .append('\n');
        }
        if (prompt != null && prompt.truncated()) {
            result.append("\n候选目录已在本地按相关度安全截断；"
                    + "如未命中，请缩小采集范围或使用更明确的业务词。");
        }
        return result.toString().trim();
    }

    private static JLabel contextBadge(String text) {
        return UiKit.badge(text, NativeTheme.FOREGROUND,
                NativeTheme.SURFACE_ALT);
    }

    private static javax.swing.Icon taskIcon(AiTask task) {
        LyraIcons.Kind kind = switch (task) {
            case FIND_TABLE -> LyraIcons.Kind.SEARCH;
            case EXPLAIN_TABLE -> LyraIcons.Kind.TABLE;
            case GENERATE, EXPLAIN, FIX -> LyraIcons.Kind.SQL;
            case OPTIMIZE -> LyraIcons.Kind.FORMAT;
            case LINEAGE_IMPACT -> LyraIcons.Kind.ER;
            case DATA_QUALITY, REVIEW -> LyraIcons.Kind.SHIELD;
        };
        return LyraIcons.of(kind);
    }

    private void selectTask(AiTask task) {
        selectedTask = task == null ? AiTask.FIND_TABLE : task;
        taskButtons.forEach((candidate, button) -> {
            boolean selected = candidate == selectedTask;
            button.setSelected(selected);
            button.setBackground(selected
                    ? NativeTheme.ACCENT_SOFT : NativeTheme.BACKGROUND);
            button.setForeground(selected
                    ? NativeTheme.ACCENT_LIGHT : NativeTheme.FOREGROUND);
        });
        taskTitleLabel.setText("你想让 AI 做什么？ · "
                + selectedTask.displayName());
        String hint = selectedTask.requestHint()
                + (selectedTask.requiresMetadata()
                ? " · 需要确认附加元数据" : "");
        taskHintLabel.setText(hint);
        requestArea.setToolTipText(hint);
        askButton.setText("发送：" + selectedTask.displayName());
        askButton.getAccessibleContext().setAccessibleName(
                "发送给 AI：" + selectedTask.displayName());
        requestArea.requestFocusInWindow();
    }

    private void setContextDetailsVisible(boolean visible) {
        contextDetailsVisible = visible;
        if (contextDetailsPanel == null) {
            return;
        }
        if (visible) {
            refreshContextSummary();
        }
        contextDetailsPanel.setVisible(visible);
        contextToggleButton.setText(visible ? "收起上下文" : "展开上下文");
        contextToggleButton.getAccessibleContext().setAccessibleName(
                visible ? "收起本次上下文" : "展开本次上下文");
        contextDetailsPanel.getParent().revalidate();
        contextDetailsPanel.getParent().repaint();
        SwingUtilities.invokeLater(() -> conversationSplit.setDividerLocation(
                contextDetailsVisible ? 0.48 : 0.43));
    }

    private void refreshContextSummary() {
        String type = safeText(dbType);
        setBadgeText(databaseSummary, "数据库："
                + (type.isBlank() ? "未知" : type));

        String sql = safeText(currentSql);
        setBadgeText(sqlSummary, formatSqlSummary(sql));
        currentSqlPreviewArea.setText(sql.isBlank()
                ? "（当前编辑器没有 SQL）" : sql);
        currentSqlPreviewArea.setCaretPosition(0);

        MetadataSelection selection = currentMetadataSelection();
        String selectionText = formatSelectionSummary(selection);
        setBadgeText(selectionSummary, ellipsize(selectionText, 34));
        selectionSummary.setToolTipText(selection == null
                ? "请在数据库导航器中选择数据库、Schema、表或视图"
                : selection.path());
        updateMetadataControls();
    }

    private MetadataSelection currentMetadataSelection() {
        try {
            return metadataSelection == null ? null : metadataSelection.get();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String safeText(Supplier<String> supplier) {
        if (supplier == null) {
            return "";
        }
        try {
            String value = supplier.get();
            return value == null ? "" : value.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static void setBadgeText(JLabel label, String text) {
        label.setText("  " + text + "  ");
        label.getAccessibleContext().setAccessibleName(text);
    }

    private static String ellipsize(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value;
        }
        return value.substring(0, Math.max(1, maximum - 1)) + "…";
    }

    private void collectMetadata() {
        if (metadataWorker != null) {
            return;
        }
        final MetadataSelection selection;
        try {
            selection = metadataSelection.get();
            if (selection == null) {
                throw new IllegalArgumentException(
                        "请先在数据库导航器中选择数据库、Schema、表或视图");
            }
        } catch (RuntimeException exception) {
            showMetadataError(exception);
            return;
        }
        statusLabel.setForeground(NativeTheme.WARNING);
        statusLabel.setText("正在读取结构元数据；不读取数据行…");
        metadataWorker = new SwingWorker<>() {
            @Override
            protected RenderedMetadata doInBackground() throws Exception {
                MetadataCapture capture =
                        metadataService.collect(selection, this::isCancelled);
                String markdown = metadataRenderer.toMarkdown(capture.snapshot());
                String json = metadataRenderer.toJson(capture.snapshot());
                return new RenderedMetadata(capture, markdown, json);
            }

            @Override
            protected void done() {
                try {
                    if (isCancelled()) {
                        statusLabel.setForeground(NativeTheme.MUTED);
                        statusLabel.setText("元数据采集已取消");
                        return;
                    }
                    RenderedMetadata rendered = get();
                    metadataCapture = rendered.capture();
                    metadataMarkdown = rendered.markdown();
                    metadataJson = rendered.json();
                    metadataAttached = false;
                    statusLabel.setForeground(NativeTheme.SUCCESS);
                    statusLabel.setText("元数据采集完成；请预览并确认是否附加");
                } catch (CancellationException exception) {
                    statusLabel.setForeground(NativeTheme.MUTED);
                    statusLabel.setText("元数据采集已取消");
                } catch (Exception exception) {
                    showMetadataError(rootCause(exception));
                } finally {
                    metadataWorker = null;
                    updateMetadataControls();
                }
            }
        };
        updateMetadataControls();
        metadataWorker.execute();
    }

    private void cancelMetadata() {
        if (metadataWorker != null) {
            metadataWorker.cancel(true);
            statusLabel.setForeground(NativeTheme.MUTED);
            statusLabel.setText("正在取消元数据采集…");
        }
    }

    private void previewMetadata() {
        if (metadataCapture == null) {
            return;
        }
        metadataAttached = MetadataPreviewDialog.show(
                this, metadataCapture, metadataMarkdown, metadataJson);
        statusLabel.setForeground(metadataAttached
                ? NativeTheme.SUCCESS : NativeTheme.MUTED);
        statusLabel.setText(metadataAttached
                ? "元数据已附加，将随下一次 AI 请求发送一次"
                : "元数据已保留，但不会发送给 AI");
        updateMetadataControls();
    }

    private void toggleMetadataAttachment() {
        if (metadataCapture == null) {
            return;
        }
        metadataAttached = !metadataAttached;
        statusLabel.setForeground(metadataAttached
                ? NativeTheme.SUCCESS : NativeTheme.MUTED);
        statusLabel.setText(metadataAttached
                ? "元数据已附加，将随下一次 AI 请求发送一次"
                : "已取消元数据附加");
        updateMetadataControls();
    }

    private void saveMetadata() {
        if (metadataCapture == null) {
            return;
        }
        Object[] options = {"Markdown", "JSON", "取消"};
        int choice = JOptionPane.showOptionDialog(this,
                "选择独立保存格式：", "保存元数据快照",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        if (choice != 0 && choice != 1) {
            return;
        }
        MetadataExportService.Format format = choice == 0
                ? MetadataExportService.Format.MARKDOWN
                : MetadataExportService.Format.JSON;
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File(
                "lyradb-metadata" + format.suffix()));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = withSuffix(chooser.getSelectedFile().toPath(), format.suffix());
        if (Files.exists(target)) {
            int overwrite = JOptionPane.showConfirmDialog(this,
                    "文件已存在，是否覆盖？\n" + target,
                    "确认覆盖", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (overwrite != JOptionPane.YES_OPTION) {
                return;
            }
        }
        MetadataCapture captureToSave = metadataCapture;
        saveMetadataButton.setEnabled(false);
        statusLabel.setForeground(NativeTheme.WARNING);
        statusLabel.setText("正在保存元数据快照…");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                metadataExporter.save(target, captureToSave, format);
                return null;
            }

            @Override
            protected void done() {
                saveMetadataButton.setEnabled(true);
                try {
                    get();
                    statusLabel.setForeground(NativeTheme.SUCCESS);
                    statusLabel.setText("元数据快照已保存：" + target.getFileName());
                } catch (Exception exception) {
                    showMetadataError(rootCause(exception));
                }
            }
        }.execute();
    }

    private void clearMetadata() {
        cancelMetadata();
        metadataCapture = null;
        metadataMarkdown = "";
        metadataJson = "";
        metadataAttached = false;
        statusLabel.setForeground(NativeTheme.MUTED);
        statusLabel.setText("元数据上下文已清除");
        updateMetadataControls();
    }

    private void updateMetadataControls() {
        boolean busy = metadataWorker != null;
        boolean available = metadataCapture != null;
        collectMetadataButton.setEnabled(!busy);
        cancelMetadataButton.setEnabled(busy);
        previewMetadataButton.setEnabled(available && !busy);
        attachMetadataButton.setEnabled(available && !busy);
        attachMetadataButton.setText(metadataAttached ? "取消附加" : "附加");
        saveMetadataButton.setEnabled(available && !busy);
        clearMetadataButton.setEnabled((available || busy));
        metadataSummary.setText(available
                ? metadataCapture.scopeLabel() + " · "
                + metadataCapture.tableCount() + " 表/视图 · "
                + metadataCapture.columnCount() + " 列 · 约 "
                + metadataCapture.estimatedTokens() + " Token"
                + (metadataAttached ? " · 已附加" : " · 未附加")
                : busy ? "正在采集…" : "未采集元数据");
        String contextState;
        if (busy) {
            contextState = "元数据：采集中";
        } else if (metadataAttached) {
            contextState = "元数据：已附加（"
                    + metadataCapture.tableCount() + " 表/视图）";
        } else if (available) {
            contextState = "元数据：已采集，未附加";
        } else {
            contextState = "元数据：未采集";
        }
        setBadgeText(metadataContextSummary, contextState);
        metadataContextSummary.setForeground(metadataAttached
                ? NativeTheme.SUCCESS : NativeTheme.FOREGROUND);
    }

    private void showMetadataError(Throwable throwable) {
        String message = throwable.getMessage() == null
                ? "元数据操作失败" : throwable.getMessage();
        statusLabel.setForeground(NativeTheme.ERROR);
        statusLabel.setText("元数据操作失败");
        JOptionPane.showMessageDialog(this, message,
                "元数据操作失败", JOptionPane.ERROR_MESSAGE);
    }

    private static Path withSuffix(Path value, String suffix) {
        String name = value.getFileName().toString();
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(suffix)
                ? value : value.resolveSibling(name + suffix);
    }

    private void setRequestBusy(boolean busy) {
        askButton.setEnabled(!busy);
        for (AbstractButton control : taskControls) {
            control.setEnabled(!busy);
        }
        if (busy) {
            askButton.setText("正在分析…");
        } else {
            askButton.setText("发送：" + selectedTask.displayName());
        }
    }

    private void ask() {
        AiProfile profile = runtime.stateStore().getAiProfile();
        if (!profile.isConfigured()) {
            JOptionPane.showMessageDialog(this,
                    "请先在“模型设置”中配置服务商、模型和 API Key。",
                    "尚未配置模型", JOptionPane.WARNING_MESSAGE);
            return;
        }

        AiTask task = selectedTask;
        String request = requestArea.getText();
        String dialect = safeText(dbType);
        boolean metadataIncluded = metadataAttached && metadataCapture != null;
        if (isRequiredMetadataMissing(task, metadataIncluded)) {
            setContextDetailsVisible(true);
            statusLabel.setForeground(NativeTheme.WARNING);
            statusLabel.setText(task.displayName() + "需要先读取并确认附加元数据");
            JOptionPane.showMessageDialog(this,
                    "“" + task.displayName()
                            + "”只会依据你确认附加的元数据回答。\n"
                            + "请先展开上下文，读取当前选择并预览附加。",
                    "需要元数据上下文", JOptionPane.INFORMATION_MESSAGE);
            (metadataCapture == null
                    ? collectMetadataButton : attachMetadataButton)
                    .requestFocusInWindow();
            return;
        }
        final List<AiTableSearchSupport.CatalogEntry> tableEntries;
        final AiTableSearchSupport.Prompt tableSearchPrompt;
        final String schemaContext;
        final String modelRequest;
        if (task == AiTask.FIND_TABLE) {
            tableEntries = catalogEntries(metadataCapture.snapshot());
            tableSearchPrompt = AiTableSearchSupport.preparePrompt(
                    request, tableEntries);
            if (tableSearchPrompt.candidates().isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "当前附加的元数据中没有表或视图，请扩大采集范围。",
                        "没有可推荐对象", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            schemaContext = tableSearchPrompt.metadataContext();
            modelRequest = AiTableSearchSupport.requestText(
                    request, tableSearchPrompt);
        } else {
            tableEntries = List.of();
            tableSearchPrompt = null;
            schemaContext = AiContextComposer.compose(
                    schemaArea.getText(), metadataMarkdown, metadataIncluded);
            modelRequest = request;
        }
        boolean manualIncluded =
                schemaArea.getText() != null && !schemaArea.getText().isBlank();
        String sqlSnapshot = safeText(currentSql);
        if (task.requiresRequest()
                && (request == null || request.isBlank())) {
            JOptionPane.showMessageDialog(this,
                    "请描述要查找的业务内容或问题。",
                    "缺少查找内容", JOptionPane.INFORMATION_MESSAGE);
            requestArea.requestFocusInWindow();
            return;
        }
        if ((request == null || request.isBlank())
                && sqlSnapshot.isBlank() && !metadataIncluded) {
            JOptionPane.showMessageDialog(this,
                    "请填写要求，或在 SQL 编辑器中准备一段 SQL。",
                    "缺少分析内容", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        setRequestBusy(true);
        insertButton.setEnabled(false);
        copyButton.setEnabled(false);
        if (metadataIncluded) {
            metadataAttached = false;
            updateMetadataControls();
        }
        statusLabel.setForeground(NativeTheme.WARNING);
        statusLabel.setText("正在发送：手工上下文"
                + (manualIncluded ? "已包含" : "未包含")
                + " · 元数据" + (metadataIncluded ? "已附加" : "未附加"));
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                if (task != AiTask.FIND_TABLE) {
                    return runtime.aiClient().complete(profile, task,
                            modelRequest, dialect, schemaContext, sqlSnapshot);
                }
                try {
                    String response = runtime.aiClient().complete(
                            profile, task, modelRequest, dialect,
                            schemaContext, "");
                    List<AiTableSearchSupport.Recommendation> recommendations =
                            AiTableSearchSupport.parseRecommendations(
                                    response, tableSearchPrompt);
                    if (!recommendations.isEmpty()) {
                        return formatTableRecommendations(
                                recommendations, tableSearchPrompt, true);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw exception;
                } catch (Exception ignored) {
                    // 模型未配置好或输出不合规时仍保留本地元数据检索能力。
                }
                List<AiTableSearchSupport.Recommendation> local =
                        AiTableSearchSupport.localRecommendations(
                                request, tableEntries, 10);
                return formatTableRecommendations(
                        local, tableSearchPrompt, false);
            }

            @Override
            protected void done() {
                setRequestBusy(false);
                try {
                    String response = get();
                    responseArea.setText(response);
                    responseArea.setCaretPosition(0);
                    boolean hasResponse = response != null && !response.isBlank();
                    insertButton.setEnabled(!extractCommand(response, dialect).isBlank());
                    copyButton.setEnabled(hasResponse);
                    statusLabel.setForeground(NativeTheme.SUCCESS);
                    statusLabel.setText("分析完成 · 手工上下文"
                            + (manualIncluded ? "已包含" : "未包含")
                            + " · 元数据" + (metadataIncluded ? "已发送一次" : "未附加"));
                } catch (Exception exception) {
                    if (metadataIncluded && metadataCapture != null) {
                        metadataAttached = true;
                        updateMetadataControls();
                    }
                    statusLabel.setForeground(NativeTheme.ERROR);
                    statusLabel.setText("AI 请求失败");
                    Throwable cause = rootCause(exception);
                    String message = cause.getMessage() == null
                            ? "模型服务未返回可读错误，请检查模型设置或稍后重试。"
                            : cause.getMessage();
                    JOptionPane.showMessageDialog(AiAssistantDialog.this,
                            message,
                            "AI 请求错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void insertResponse() {
        String command = extractCommand(responseArea.getText(), safeText(dbType));
        if (!command.isBlank()) {
            insertSql.accept(command);
            statusLabel.setForeground(NativeTheme.SUCCESS);
            statusLabel.setText("已插入打开助手时对应的查询工作区");
        }
    }

    private void copyResponse() {
        String response = responseArea.getText();
        if (response == null || response.isBlank()) {
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(response), null);
        statusLabel.setForeground(NativeTheme.SUCCESS);
        statusLabel.setText("结果已复制到剪贴板");
    }

    static String extractSql(String response) {
        return extractCommand(response, "SQL");
    }

    static String extractCommand(String response, String dbType) {
        if (response == null) {
            return "";
        }
        String normalizedType = dbType == null ? ""
                : dbType.trim().toUpperCase(java.util.Locale.ROOT);
        if ("REDIS".equals(normalizedType)) {
            return extractValidated(response, REDIS_CODE_BLOCK, REDIS_START);
        }
        if ("MONGODB".equals(normalizedType)) {
            return extractValidated(response, MONGO_CODE_BLOCK, MONGO_COMMAND);
        }
        return extractValidated(response, CODE_BLOCK, SQL_START);
    }

    private static String extractValidated(String response,
            Pattern codeBlock, Pattern allowedStart) {
        Matcher matcher = codeBlock.matcher(response);
        if (matcher.find()) {
            String fenced = matcher.group(1).trim();
            return allowedStart.matcher(fenced).find() ? fenced : "";
        }
        String plain = response.trim();
        return allowedStart.matcher(plain).find() ? plain : "";
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record RenderedMetadata(MetadataCapture capture,
                                    String markdown,
                                    String json) {
    }
}
