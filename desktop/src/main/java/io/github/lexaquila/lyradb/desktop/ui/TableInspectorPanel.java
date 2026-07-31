package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.DesktopRuntime;
import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.TableConstraintMetadata;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 表/视图工作台：默认展示数据预览，并提供字段、索引约束和 DDL 分页。
 */
final class TableInspectorPanel extends JPanel {

    static final int PREVIEW_LIMIT = 200;

    private final DesktopRuntime runtime;
    private final String connectionId;
    private final String connectionName;
    private final String dbType;
    private final String schema;
    private final String table;
    private final String objectType;
    private final Consumer<String> statusSink;
    private final Consumer<String> sqlOpener;

    private final JLabel stateLabel = new JLabel("准备加载");
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
    private final JTable previewTable = table();
    private final JTable columnsTable = table();
    private final JTable constraintsTable = table();
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

    private SwingWorker<TableSnapshot, Void> worker;
    private long generation;
    private String previewSql;

    TableInspectorPanel(
            DesktopRuntime runtime,
            String connectionId,
            String connectionName,
            String dbType,
            String schema,
            String table,
            String objectType,
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
        this.statusSink = statusSink;
        this.sqlOpener = sqlOpener;

        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(createHeader(), BorderLayout.NORTH);
        add(createTabs(), BorderLayout.CENTER);
        refreshButton.addActionListener(event -> refresh());
        openSqlButton.addActionListener(event -> openSql());
        refresh();
    }

    String workspaceKey() {
        return connectionId + "\u0000"
                + (schema == null ? "" : schema) + "\u0000" + table;
    }

    void refresh() {
        long request = ++generation;
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
        setLoading(true);
        previewSql = null;
        previewModel.setData(List.of(), List.of());
        columnsModel.setData(columnsModel.columns(), List.of());
        constraintsModel.setData(constraintsModel.columns(), List.of());
        ddlArea.setText("正在读取表定义…");
        statusSink.accept("正在加载表工作台：" + qualifiedName());

        worker = new SwingWorker<>() {
            @Override
            protected TableSnapshot doInBackground() {
                QueryResult preview = null;
                List<ColumnMetadata> columns = List.of();
                List<TableConstraintMetadata> constraints = List.of();
                String ddl = "";
                Map<String, String> errors = new LinkedHashMap<>();

                try {
                    preview = runtime.connectionManager().previewTable(
                            connectionId, schema, table, PREVIEW_LIMIT);
                } catch (Exception exception) {
                    errors.put("preview", safeMessage(exception));
                }
                if (isCancelled()) {
                    return null;
                }
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
                    constraints = runtime.connectionManager().constraints(
                            connectionId, schema, table);
                } catch (Exception exception) {
                    errors.put("constraints", safeMessage(exception));
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
                return new TableSnapshot(
                        preview, columns, constraints, ddl, errors);
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
                    }
                } catch (Exception exception) {
                    apply(new TableSnapshot(
                            null, List.of(), List.of(), "",
                            Map.of("preview", safeMessage(exception))));
                } finally {
                    if (request == generation) {
                        setLoading(false);
                    }
                }
            }
        };
        worker.execute();
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
        title.setFont(NativeTheme.FONT_TITLE.deriveFont(Font.BOLD, 17F));
        title.setForeground(NativeTheme.FOREGROUND);
        JLabel subtitle = new JLabel(
                connectionName + "  ·  " + dbType
                        + "  ·  只读预览，最多 " + PREVIEW_LIMIT + " 行");
        subtitle.setFont(NativeTheme.FONT_CAPTION);
        subtitle.setForeground(NativeTheme.MUTED);
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

        JTabbedPane tabs = new JTabbedPane();
        tabs.setOpaque(false);
        tabs.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        tabs.addTab("数据预览", LyraIcons.of(
                LyraIcons.Kind.TABLE, NativeTheme.ACCENT_LIGHT),
                dataTab());
        tabs.addTab("字段结构", LyraIcons.of(
                LyraIcons.Kind.COLUMN, NativeTheme.ACCENT_LIGHT),
                tableTab(columnsTable));
        tabs.addTab("索引 / 约束", LyraIcons.of(
                LyraIcons.Kind.INDEX, NativeTheme.WARNING),
                tableTab(constraintsTable));
        tabs.addTab("DDL", LyraIcons.of(
                LyraIcons.Kind.SQL, NativeTheme.MUTED),
                ddlTab());
        return tabs;
    }

    private JPanel dataTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
        stateLabel.setFont(NativeTheme.FONT_CAPTION);
        stateLabel.setForeground(NativeTheme.MUTED);
        bar.add(stateLabel, BorderLayout.WEST);
        JLabel note = new JLabel(
                "为保护数据库，预览不会执行全表导出",
                SwingConstants.RIGHT);
        note.setFont(NativeTheme.FONT_CAPTION);
        note.setForeground(NativeTheme.MUTED);
        bar.add(note, BorderLayout.EAST);
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
        QueryResult preview = snapshot.preview();
        if (preview != null) {
            previewSql = preview.getSql();
            List<List<Object>> rows = new ArrayList<>();
            for (Map<String, Object> row : preview.getRows()) {
                List<Object> values = new ArrayList<>();
                for (String column : preview.getColumns()) {
                    values.add(row.get(column));
                }
                rows.add(values);
            }
            previewModel.setData(preview.getColumns(), rows);
            configurePreviewWidths(preview.getColumns());
            rowBadge.setText("  " + preview.getRows().size()
                    + (preview.isTruncated() ? "+ 行  " : " 行  "));
            stateLabel.setText(preview.getRows().size() + " 行"
                    + (preview.isTruncated() ? "（已按上限截断）" : "")
                    + "  ·  " + preview.getElapsedMs() + " ms");
        } else {
            previewModel.setData(List.of("状态"), List.of(List.of(
                    "无法读取数据预览："
                            + snapshot.errors().getOrDefault(
                            "preview", "当前驱动不支持"))));
            rowBadge.setText("  0 行  ");
            stateLabel.setText("数据预览不可用");
        }

        List<List<Object>> columnRows = new ArrayList<>();
        int index = 1;
        for (ColumnMetadata column : snapshot.columns()) {
            columnRows.add(List.of(
                    index++,
                    value(column.getName()),
                    value(column.getTypeName()),
                    size(column),
                    column.isNullable() ? "YES" : "NO",
                    value(column.getDefaultValue()),
                    column.isPrimaryKey() ? "PK" : "",
                    column.isAutoIncrement() ? "YES" : "",
                    value(column.getRemarks())));
        }
        columnsModel.setData(columnsModel.columns(), columnRows);
        columnBadge.setText("  " + snapshot.columns().size() + " 列  ");

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

        if (snapshot.errors().isEmpty()) {
            statusSink.accept("表工作台已加载：" + qualifiedName());
        } else {
            statusSink.accept("表工作台已加载，部分信息不可用："
                    + String.join("、", snapshot.errors().keySet()));
        }
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
        openSqlButton.setEnabled(!loading
                && previewSql != null && !previewSql.isBlank());
        if (loading) {
            stateLabel.setText("正在读取数据、字段与约束…");
        }
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

    private record TableSnapshot(
            QueryResult preview,
            List<ColumnMetadata> columns,
            List<TableConstraintMetadata> constraints,
            String ddl,
            Map<String, String> errors) {
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
