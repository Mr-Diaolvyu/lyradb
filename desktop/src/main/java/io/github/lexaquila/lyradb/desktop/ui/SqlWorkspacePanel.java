package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.DesktopRuntime;
import io.github.lexaquila.lyradb.desktop.db.NativeConnectionManager.ExecutionResult;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.SqlReviewFinding;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 原生 SQL 编辑器、执行结果与事务控制面板。
 */
public final class SqlWorkspacePanel extends JPanel {

    private final DesktopRuntime runtime;
    private final String connectionId;
    private final String connectionName;
    private final String dbType;
    private final Consumer<String> statusConsumer;
    private final JTextArea editor = new JTextArea();
    private final JTable resultTable = new JTable();
    private final JTextArea messages = new JTextArea();
    private final JSpinner limitSpinner =
            new JSpinner(new SpinnerNumberModel(1000, 1, 10000, 100));
    private final JButton executeButton = UiKit.button(
            "执行", LyraIcons.of(LyraIcons.Kind.PLAY),
            UiKit.ButtonStyle.PRIMARY);
    private final JButton cancelButton = UiKit.button(
            "取消", LyraIcons.of(LyraIcons.Kind.CLOSE),
            UiKit.ButtonStyle.GHOST);
    private final JButton beginButton = UiKit.button(
            "开启事务", null, UiKit.ButtonStyle.TOOLBAR);
    private final JButton commitButton = UiKit.button(
            "提交", null, UiKit.ButtonStyle.TOOLBAR);
    private final JButton rollbackButton = UiKit.button(
            "回滚", null, UiKit.ButtonStyle.TOOLBAR);
    private volatile String runningExecutionId;
    private boolean operationBusy;

    public SqlWorkspacePanel(DesktopRuntime runtime, String connectionId,
            String connectionName, String dbType, Consumer<String> statusConsumer) {
        super(new BorderLayout());
        this.runtime = runtime;
        this.connectionId = connectionId;
        this.connectionName = connectionName;
        this.dbType = dbType;
        this.statusConsumer = statusConsumer;
        buildUi();
    }

    public String connectionId() {
        return connectionId;
    }

    public String connectionName() {
        return connectionName;
    }

    public String dbType() {
        return dbType;
    }

    public String currentSql() {
        String selected = editor.getSelectedText();
        return selected == null || selected.isBlank() ? editor.getText() : selected;
    }

    public void insertSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        if (editor.getSelectionStart() != editor.getSelectionEnd()) {
            editor.replaceSelection(sql);
        } else {
            String prefix = editor.getText().isBlank() ? "" : "\n\n";
            editor.insert(prefix + sql, editor.getCaretPosition());
        }
        editor.requestFocusInWindow();
    }

    public void replaceSql(String sql) {
        editor.setText(sql == null ? "" : sql);
        editor.setCaretPosition(0);
    }

    private void buildUi() {
        setBackground(NativeTheme.BACKGROUND);

        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setBackground(NativeTheme.SURFACE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        0, 0, 1, 0, NativeTheme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        JPanel identity = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        identity.setOpaque(false);
        identity.add(new JLabel(LyraIcons.of(
                LyraIcons.Kind.DATABASE, NativeTheme.ACCENT_LIGHT)));
        JLabel connectionLabel = new JLabel(ellipsize(connectionName, 28));
        connectionLabel.setToolTipText(connectionName);
        connectionLabel.getAccessibleContext().setAccessibleName(connectionName);
        connectionLabel.setFont(NativeTheme.FONT_TITLE);
        connectionLabel.setForeground(NativeTheme.FOREGROUND);
        identity.add(connectionLabel);
        identity.add(UiKit.badge(dbType,
                NativeTheme.ACCENT_LIGHT, NativeTheme.ACCENT_SOFT));
        header.add(identity, BorderLayout.WEST);

        executeButton.setToolTipText("执行选中 SQL；未选中时执行全部（Ctrl+Enter）");
        executeButton.addActionListener(event -> execute(false));
        cancelButton.setEnabled(false);
        cancelButton.addActionListener(event -> cancel());
        beginButton.addActionListener(event -> transaction("begin"));
        commitButton.addActionListener(event -> transaction("commit"));
        rollbackButton.addActionListener(event -> transaction("rollback"));
        JButton export = UiKit.button(
                "导出 CSV", LyraIcons.of(LyraIcons.Kind.EXPORT),
                UiKit.ButtonStyle.TOOLBAR);
        export.addActionListener(event -> exportCsv());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        actions.setOpaque(false);
        actions.add(executeButton);
        actions.add(cancelButton);
        actions.add(Box.createHorizontalStrut(4));
        actions.add(beginButton);
        actions.add(commitButton);
        actions.add(rollbackButton);
        JLabel limitLabel = new JLabel("最大行数");
        limitLabel.setForeground(NativeTheme.MUTED);
        actions.add(limitLabel);
        limitSpinner.setPreferredSize(new Dimension(88, 34));
        actions.add(limitSpinner);
        actions.add(export);
        header.add(actions, BorderLayout.EAST);

        editor.setFont(NativeTheme.FONT_MONO);
        editor.setTabSize(4);
        editor.setLineWrap(false);
        editor.setMargin(new java.awt.Insets(12, 12, 12, 12));
        editor.getInputMap().put(KeyStroke.getKeyStroke(
                KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "executeSql");
        editor.getActionMap().put("executeSql",
                new javax.swing.AbstractAction() {
                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent event) {
                        execute(false);
                    }
                });

        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        resultTable.setFillsViewportHeight(true);
        resultTable.setRowHeight(28);
        resultTable.setShowVerticalLines(false);
        resultTable.setIntercellSpacing(new Dimension(0, 1));
        messages.setEditable(false);
        messages.setLineWrap(true);
        messages.setWrapStyleWord(true);
        messages.setRows(4);
        messages.setFont(NativeTheme.FONT_MONO.deriveFont(12F));
        messages.setMargin(new java.awt.Insets(10, 10, 10, 10));

        JTabbedPane outputTabs = new JTabbedPane();
        outputTabs.addTab("结果", UiKit.scroll(resultTable));
        outputTabs.addTab("消息与安全审核", UiKit.scroll(messages));

        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, UiKit.scroll(editor), outputTabs);
        split.setResizeWeight(0.55);
        split.setDividerLocation(360);
        split.setDividerSize(1);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setMinimumSize(new Dimension(500, 400));

        add(header, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
    }

    private void execute(boolean force) {
        executeSql(currentSql(), force);
    }

    private void executeSql(String sql, boolean force) {
        if (operationBusy || runningExecutionId != null) {
            return;
        }
        if (sql == null || sql.isBlank()) {
            status("没有可执行的 SQL");
            return;
        }

        int rowLimit = ((Number) limitSpinner.getValue()).intValue();
        runningExecutionId = "desktop-ui-" + UUID.randomUUID();
        String executionId = runningExecutionId;
        setOperationBusy(true, true);
        messages.setText("正在执行发送时的 SQL 快照…\n");
        long started = System.currentTimeMillis();
        status("正在 " + connectionName + " 执行 SQL…");

        new SwingWorker<ExecutionResult, Void>() {
            @Override
            protected ExecutionResult doInBackground() throws Exception {
                return runtime.connectionManager().execute(
                        connectionId, sql, rowLimit, force, executionId);
            }

            @Override
            protected void done() {
                runningExecutionId = null;
                setOperationBusy(false, false);
                try {
                    ExecutionResult result = get();
                    if (result.blocked()) {
                        showFindings(result.findings(), true);
                        int choice = JOptionPane.showConfirmDialog(
                                SqlWorkspacePanel.this,
                                blockingMessage(result.findings())
                                        + "\n\n确认理解风险并仍要执行这段已审核的 SQL 吗？",
                                "SQL 安全审核已拦截",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE);
                        if (choice == JOptionPane.YES_OPTION) {
                            executeSql(sql, true);
                        } else {
                            status("已取消危险 SQL");
                        }
                        return;
                    }
                    if (result.queryResult() != null) {
                        showQuery(result.queryResult());
                        status("查询完成：" + result.queryResult().getRows().size()
                                + " 行，" + result.queryResult().getElapsedMs() + " ms");
                    } else {
                        showUpdate(result.affectedRows(), result.findings(),
                                System.currentTimeMillis() - started);
                        status("执行完成：影响 " + result.affectedRows() + " 行");
                    }
                } catch (Exception exception) {
                    Throwable cause = rootCause(exception);
                    messages.setText("执行失败："
                            + cause.getClass().getSimpleName() + "\n" + cause.getMessage());
                    status("SQL 执行失败");
                    JOptionPane.showMessageDialog(SqlWorkspacePanel.this,
                            cause.getMessage(), "SQL 执行失败", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void cancel() {
        String executionId = runningExecutionId;
        if (executionId == null) {
            messages.append("\n当前没有执行中的语句。");
            status("没有执行中的语句");
            return;
        }
        cancelButton.setEnabled(false);
        status("正在请求驱动取消当前语句…");
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                return runtime.connectionManager().cancel(executionId);
            }
            @Override
            protected void done() {
                try {
                    boolean cancelled = get();
                    if (cancelled) {
                        messages.append("\n已向驱动发送取消请求。");
                        status("已发送取消请求，等待驱动结束语句");
                    } else {
                        messages.append(
                                "\n驱动未确认取消；查询可能仍在运行，可稍后重试。");
                        status("驱动未确认取消，查询可能仍在运行");
                        if (executionId.equals(runningExecutionId)) {
                            cancelButton.setEnabled(true);
                        }
                    }
                } catch (Exception exception) {
                    Throwable cause = rootCause(exception);
                    messages.append(
                            "\n取消请求失败：" + cause.getMessage());
                    status("取消请求失败，查询可能仍在运行");
                    if (executionId.equals(runningExecutionId)) {
                        cancelButton.setEnabled(true);
                    }
                }
            }
        }.execute();
    }

    private void transaction(String action) {
        if (operationBusy) {
            return;
        }
        setOperationBusy(true, false);
        status("正在执行事务操作…");
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                switch (action) {
                    case "begin" -> runtime.connectionManager()
                            .beginTransaction(connectionId);
                    case "commit" -> runtime.connectionManager().commit(connectionId);
                    case "rollback" -> runtime.connectionManager().rollback(connectionId);
                    default -> throw new IllegalArgumentException("未知事务动作");
                }
                return runtime.connectionManager().inTransaction(connectionId);
            }

            @Override
            protected void done() {
                setOperationBusy(false, false);
                try {
                    boolean active = get();
                    messages.append("\n事务状态："
                            + (active ? "手动事务已开启" : "自动提交"));
                    status(active ? "事务已开启" : "事务已结束");
                } catch (Exception exception) {
                    JOptionPane.showMessageDialog(SqlWorkspacePanel.this,
                            rootCause(exception).getMessage(),
                            "事务操作失败", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void setOperationBusy(boolean busy, boolean cancellable) {
        operationBusy = busy;
        executeButton.setEnabled(!busy);
        beginButton.setEnabled(!busy);
        commitButton.setEnabled(!busy);
        rollbackButton.setEnabled(!busy);
        limitSpinner.setEnabled(!busy);
        cancelButton.setEnabled(busy && cancellable);
    }

    private void showQuery(QueryResult result) {
        DefaultTableModel model = new DefaultTableModel(
                result.getColumns().toArray(), 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (Map<String, Object> row : result.getRows()) {
            Object[] values = result.getColumns().stream()
                    .map(row::get).toArray();
            model.addRow(values);
        }
        resultTable.setModel(model);
        for (int column = 0; column < resultTable.getColumnCount(); column++) {
            resultTable.getColumnModel().getColumn(column).setPreferredWidth(150);
        }
        StringBuilder text = new StringBuilder()
                .append("查询完成：").append(result.getRows().size()).append(" 行，")
                .append(result.getElapsedMs()).append(" ms");
        if (result.isTruncated()) {
            text.append("\n结果已按最大行数截断。");
        }
        messages.setText(text.toString());
        showFindings(result.getReviewFindings(), false);
    }

    private void showUpdate(Integer affected, List<SqlReviewFinding> findings, long elapsed) {
        DefaultTableModel model = new DefaultTableModel(
                new Object[]{"影响行数", "耗时(ms)"}, 0);
        model.addRow(new Object[]{affected, elapsed});
        resultTable.setModel(model);
        messages.setText("语句执行完成，影响 " + affected + " 行。");
        showFindings(findings, false);
    }

    private void showFindings(List<SqlReviewFinding> findings, boolean replace) {
        if (findings == null || findings.isEmpty()) {
            return;
        }
        StringBuilder builder = new StringBuilder(
                replace ? "SQL 审核结果：\n" : "\n\nSQL 审核提示：\n");
        for (SqlReviewFinding finding : findings) {
            builder.append("- [").append(finding.getSeverity()).append("] ")
                    .append(finding.getRuleId()).append("：")
                    .append(finding.getMessage()).append('\n');
        }
        if (replace) {
            messages.setText(builder.toString());
        } else {
            messages.append(builder.toString());
        }
    }

    private static String blockingMessage(List<SqlReviewFinding> findings) {
        StringBuilder builder = new StringBuilder("检测到高风险 SQL：\n");
        for (SqlReviewFinding finding : findings) {
            if (!"LOW".equals(finding.getSeverity())) {
                builder.append("• ").append(finding.getMessage()).append('\n');
            }
        }
        return builder.toString();
    }

    private void exportCsv() {
        if (resultTable.getColumnCount() == 0) {
            status("当前没有可导出的结果");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导出查询结果为 CSV");
        chooser.setSelectedFile(new java.io.File("lyradb-result.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        if (Files.exists(target)) {
            int overwrite = JOptionPane.showConfirmDialog(this,
                    "文件已存在，确认覆盖？\n" + target.toAbsolutePath(),
                    "确认覆盖 CSV", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (overwrite != JOptionPane.YES_OPTION) {
                return;
            }
        }

        List<String> headers = new ArrayList<>(resultTable.getColumnCount());
        for (int column = 0; column < resultTable.getColumnCount(); column++) {
            headers.add(resultTable.getColumnName(column));
        }
        List<List<String>> rows = new ArrayList<>(resultTable.getRowCount());
        for (int row = 0; row < resultTable.getRowCount(); row++) {
            List<String> values = new ArrayList<>(resultTable.getColumnCount());
            for (int column = 0; column < resultTable.getColumnCount(); column++) {
                Object value = resultTable.getValueAt(row, column);
                values.add(value == null ? "" : value.toString());
            }
            rows.add(values);
        }

        status("正在导出 CSV…");
        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() throws Exception {
                writeCsv(target, headers, rows);
                return target;
            }

            @Override
            protected void done() {
                try {
                    Path exported = get();
                    status("已导出：" + exported.toAbsolutePath());
                } catch (Exception exception) {
                    JOptionPane.showMessageDialog(SqlWorkspacePanel.this,
                            rootCause(exception).getMessage(),
                            "CSV 导出失败", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    static void writeCsv(Path target, List<String> headers,
            List<List<String>> rows) throws java.io.IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(target,
                StandardCharsets.UTF_8)) {
            writer.write('\ufeff');
            writer.write(headers.stream()
                    .map(SqlWorkspacePanel::csv)
                    .collect(java.util.stream.Collectors.joining(",")));
            writer.newLine();
            for (List<String> row : rows) {
                writer.write(row.stream()
                        .map(SqlWorkspacePanel::csv)
                        .collect(java.util.stream.Collectors.joining(",")));
                writer.newLine();
            }
        }
    }

    static String csv(String value) {
        String safe = protectSpreadsheetFormula(value == null ? "" : value);
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    static String protectSpreadsheetFormula(String value) {
        int index = 0;
        while (index < value.length()
                && (Character.isWhitespace(value.charAt(index))
                || Character.isSpaceChar(value.charAt(index))
                || value.charAt(index) == '\ufeff')) {
            index++;
        }
        if (index < value.length()) {
            char first = value.charAt(index);
            if (first == '=' || first == '+' || first == '-' || first == '@') {
                return "'" + value;
            }
        }
        return value;
    }

    static String ellipsize(String value, int maxCodePoints) {
        if (value == null || value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value == null ? "" : value;
        }
        int end = value.offsetByCodePoints(0, Math.max(1, maxCodePoints - 1));
        return value.substring(0, end) + "…";
    }

    private void status(String message) {
        statusConsumer.accept(message);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
