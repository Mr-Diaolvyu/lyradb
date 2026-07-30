package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.DesktopRuntime;
import io.github.lexaquila.lyradb.desktop.ai.AiContextComposer;
import io.github.lexaquila.lyradb.desktop.ai.AiTask;
import io.github.lexaquila.lyradb.desktop.metadata.MetadataCapture;
import io.github.lexaquila.lyradb.desktop.metadata.MetadataContextService;
import io.github.lexaquila.lyradb.desktop.metadata.MetadataExportService;
import io.github.lexaquila.lyradb.desktop.metadata.MetadataSelection;
import io.github.lexaquila.lyradb.desktop.model.AiProfile;
import io.github.lexaquila.lyradb.metadata.snapshot.MetadataSnapshotRenderer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 数据库助手。
 */
public final class AiAssistantDialog extends JDialog {

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
    private final JComboBox<AiTask> taskBox = new JComboBox<>(AiTask.values());
    private final JTextArea requestArea = new JTextArea();
    private final JTextArea schemaArea = new JTextArea();
    private final JTextArea responseArea = new JTextArea();
    private final JLabel statusLabel =
            new JLabel("手工上下文：未发送 · 元数据：未附加");
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
        super(owner, "LyraDB AI 数据库助手", false);
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
        setMinimumSize(new Dimension(900, 620));
        setSize(1120, 760);
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
        JLabel title = new JLabel("AI 数据库助手");
        title.setFont(NativeTheme.FONT_TITLE);
        title.setForeground(NativeTheme.FOREGROUND);
        JLabel subtitle = new JLabel("当前 SQL 与明确附加的上下文会随本次请求发送");
        subtitle.setFont(NativeTheme.FONT_CAPTION);
        subtitle.setForeground(NativeTheme.MUTED);
        identityText.add(title);
        identityText.add(Box.createVerticalStrut(3));
        identityText.add(subtitle);
        identity.add(identityText, BorderLayout.CENTER);
        header.add(identity, BorderLayout.CENTER);

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        tools.setOpaque(false);
        JLabel taskLabel = new JLabel("任务");
        taskLabel.setForeground(NativeTheme.MUTED);
        tools.add(taskLabel);
        taskBox.setPreferredSize(new Dimension(150, 34));
        tools.add(taskBox);
        JButton settings = UiKit.button("AI 设置",
                LyraIcons.of(LyraIcons.Kind.SETTINGS),
                UiKit.ButtonStyle.TOOLBAR);
        settings.addActionListener(event ->
                new AiSettingsDialog(owner, runtime).setVisible(true));
        tools.add(settings);
        header.add(tools, BorderLayout.EAST);

        configureTextAreas();
        JPanel requestSection = UiKit.section(
                "你的要求",
                "说明目标、过滤条件、输出字段和期望口径",
                textScroll(requestArea));
        JPanel schemaSection = UiKit.section(
                "结构与业务上下文（手工输入）",
                "手工内容始终保留；采集的元数据需另行确认附加",
                createSchemaContextPanel());
        JSplitPane inputSplit = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, requestSection, schemaSection);
        inputSplit.setResizeWeight(0.58);
        inputSplit.setDividerLocation(315);
        inputSplit.setDividerSize(10);
        inputSplit.setBorder(BorderFactory.createEmptyBorder());

        JPanel responseSection = UiKit.section(
                "AI 建议",
                "建议内容不会自动进入编辑器，更不会自动发送到数据库",
                textScroll(responseArea));

        JSplitPane mainSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, inputSplit, responseSection);
        mainSplit.setResizeWeight(0.43);
        mainSplit.setDividerLocation(470);
        mainSplit.setDividerSize(10);
        mainSplit.setBorder(BorderFactory.createEmptyBorder());

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(NativeTheme.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        content.add(mainSplit, BorderLayout.CENTER);

        JButton askButton = UiKit.button("发送给 AI",
                LyraIcons.of(LyraIcons.Kind.AI),
                UiKit.ButtonStyle.PRIMARY);
        askButton.addActionListener(event -> ask(askButton));
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
    }

    private void configureTextAreas() {
        requestArea.setLineWrap(true);
        requestArea.setWrapStyleWord(true);
        requestArea.setMargin(new java.awt.Insets(10, 10, 10, 10));
        requestArea.setToolTipText("示例：统计近 30 天每个客户的订单金额并按金额降序排列");
        schemaArea.setLineWrap(true);
        schemaArea.setWrapStyleWord(true);
        schemaArea.setMargin(new java.awt.Insets(10, 10, 10, 10));
        schemaArea.setToolTipText("只填写已经确认的字段含义与业务口径");
        responseArea.setEditable(false);
        responseArea.setLineWrap(true);
        responseArea.setWrapStyleWord(true);
        responseArea.setMargin(new java.awt.Insets(10, 10, 10, 10));
        responseArea.setFont(NativeTheme.FONT_MONO);
    }

    private static JScrollPane textScroll(JTextArea area) {
        JScrollPane scroll = UiKit.scroll(area);
        scroll.setPreferredSize(new Dimension(320, 210));
        return scroll;
    }

    private JPanel createSchemaContextPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);
        JPanel metadataBar = new JPanel(new BorderLayout(8, 4));
        metadataBar.setOpaque(false);
        metadataSummary.setFont(NativeTheme.FONT_CAPTION);
        metadataSummary.setForeground(NativeTheme.MUTED);
        metadataBar.add(metadataSummary, BorderLayout.CENTER);
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
        metadataBar.add(actions, BorderLayout.EAST);
        panel.add(metadataBar, BorderLayout.NORTH);
        panel.add(textScroll(schemaArea), BorderLayout.CENTER);
        updateMetadataControls();
        return panel;
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
                    statusLabel.setText("元数据采集完成，尚未附加到 AI");
                    previewMetadata();
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

    private void ask(JButton button) {
        AiProfile profile = runtime.stateStore().getAiProfile();
        if (!profile.isConfigured()) {
            JOptionPane.showMessageDialog(this,
                    "请先在“AI 设置”中配置服务商、模型和 API Key。",
                    "尚未配置 AI", JOptionPane.WARNING_MESSAGE);
            return;
        }

        AiTask task = (AiTask) taskBox.getSelectedItem();
        String request = requestArea.getText();
        String dialect = dbType.get();
        boolean metadataIncluded = metadataAttached && metadataCapture != null;
        String schemaContext = AiContextComposer.compose(
                schemaArea.getText(), metadataMarkdown, metadataIncluded);
        boolean manualIncluded =
                schemaArea.getText() != null && !schemaArea.getText().isBlank();
        String sqlSnapshot = currentSql.get();
        if ((request == null || request.isBlank())
                && (sqlSnapshot == null || sqlSnapshot.isBlank())) {
            JOptionPane.showMessageDialog(this,
                    "请填写要求，或先在 SQL 编辑器中选择一段 SQL。",
                    "缺少分析内容", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        button.setEnabled(false);
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
                return runtime.aiClient().complete(profile, task,
                        request, dialect, schemaContext, sqlSnapshot);
            }

            @Override
            protected void done() {
                button.setEnabled(true);
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
                    statusLabel.setForeground(NativeTheme.ERROR);
                    statusLabel.setText("AI 请求失败");
                    JOptionPane.showMessageDialog(AiAssistantDialog.this,
                            rootCause(exception).getMessage(),
                            "AI 请求错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void insertResponse() {
        String command = extractCommand(responseArea.getText(), dbType.get());
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
