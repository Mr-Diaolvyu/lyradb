package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.DesktopRuntime;
import io.github.lexaquila.lyradb.desktop.ai.AiTask;
import io.github.lexaquila.lyradb.desktop.model.AiProfile;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
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
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 个人版原生 AI 数据库助手。
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
    private final JComboBox<AiTask> taskBox = new JComboBox<>(AiTask.values());
    private final JTextArea requestArea = new JTextArea();
    private final JTextArea schemaArea = new JTextArea();
    private final JTextArea responseArea = new JTextArea();
    private final JLabel statusLabel =
            new JLabel("AI 仅提供建议，结果需由你确认后手动执行");
    private final JButton insertButton = UiKit.button(
            "插入查询编辑器", LyraIcons.of(LyraIcons.Kind.SQL),
            UiKit.ButtonStyle.SECONDARY);
    private final JButton copyButton = UiKit.button(
            "复制结果", LyraIcons.of(LyraIcons.Kind.COPY),
            UiKit.ButtonStyle.GHOST);

    public AiAssistantDialog(JFrame owner, DesktopRuntime runtime,
            Supplier<String> currentSql, Supplier<String> dbType,
            Consumer<String> insertSql) {
        super(owner, "LyraDB AI 数据库助手 · 个人版", false);
        this.runtime = runtime;
        this.currentSql = currentSql;
        this.dbType = dbType;
        this.insertSql = insertSql;
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
        JLabel subtitle = new JLabel("基于当前 SQL 与结构上下文生成可审阅建议，绝不自动执行");
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
                "结构与业务上下文",
                "可选：粘贴 DDL、列说明或已经确认的业务口径",
                textScroll(schemaArea));
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
        String schemaContext = schemaArea.getText();
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
        statusLabel.setForeground(NativeTheme.WARNING);
        statusLabel.setText("AI 正在分析发送时的内容快照…");
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
                    statusLabel.setText("分析完成 · 请人工核对后再插入或执行");
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
}
