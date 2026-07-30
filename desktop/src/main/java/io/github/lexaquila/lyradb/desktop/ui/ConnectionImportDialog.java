package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.transfer.ConnectionImportPlanner;
import io.github.lexaquila.lyradb.desktop.transfer.ConnectionTransferService;
import io.github.lexaquila.lyradb.transfer.connection.CredentialExportPolicy;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 连接包导入冲突预览与逐项决策。
 */
final class ConnectionImportDialog extends JDialog {

    private final ConnectionImportPlanner planner;
    private final List<ConnectionImportPlanner.PreviewItem> preview;
    private final PreviewTableModel model;
    private ConnectionImportPlanner.Resolution result;

    private ConnectionImportDialog(JFrame owner,
            ConnectionImportPlanner planner,
            List<ConnectionImportPlanner.PreviewItem> preview,
            ConnectionTransferService.ImportBundle bundle,
            List<io.github.lexaquila.lyradb.desktop.model.DesktopConnection> existing) {
        super(owner, "导入连接配置", Dialog.ModalityType.APPLICATION_MODAL);
        this.planner = planner;
        this.preview = List.copyOf(preview);
        this.model = new PreviewTableModel(preview, bundle);
        setIconImage(LyraIcons.applicationImage());
        buildUi(bundle, existing);
        setMinimumSize(new Dimension(980, 520));
        setSize(1120, 650);
        setLocationRelativeTo(owner);
    }

    static ConnectionImportPlanner.Resolution show(JFrame owner,
            ConnectionImportPlanner planner,
            List<ConnectionImportPlanner.PreviewItem> preview,
            ConnectionTransferService.ImportBundle bundle,
            List<io.github.lexaquila.lyradb.desktop.model.DesktopConnection> existing) {
        ConnectionImportDialog dialog = new ConnectionImportDialog(
                owner, planner, preview, bundle, existing);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void buildUi(ConnectionTransferService.ImportBundle bundle,
            List<io.github.lexaquila.lyradb.desktop.model.DesktopConnection> existing) {
        getContentPane().setBackground(NativeTheme.BACKGROUND);
        JPanel header = new JPanel(new BorderLayout(12, 4));
        header.setBackground(NativeTheme.SURFACE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        0, 0, 1, 0, NativeTheme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(12, 16, 12, 16)));
        JLabel title = new JLabel("预览 " + preview.size() + " 个连接");
        title.setFont(NativeTheme.FONT_TITLE);
        title.setForeground(NativeTheme.FOREGROUND);
        header.add(title, BorderLayout.WEST);
        JLabel policy = new JLabel(policyText(bundle.credentialPolicy()));
        policy.setForeground(bundle.credentialPolicy() == CredentialExportPolicy.PLAINTEXT
                ? NativeTheme.ERROR : NativeTheme.MUTED);
        header.add(policy, BorderLayout.EAST);

        JTable table = new ActionTable(model);
        table.setRowHeight(30);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(190);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(220);
        table.getColumnModel().getColumn(3).setPreferredWidth(220);
        table.getColumnModel().getColumn(4).setPreferredWidth(100);
        table.getColumnModel().getColumn(5).setPreferredWidth(190);
        table.getColumnModel().getColumn(5).setCellEditor(
                new DefaultCellEditor(new javax.swing.JTextField()));
        JScrollPane scroll = UiKit.scroll(table);
        scroll.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel note = new JLabel(
                "冲突项默认跳过；覆盖与重命名必须逐项明确选择。"
                        + (bundle.credentialPolicy() == CredentialExportPolicy.OMIT
                        ? " 标记“需补录”的连接导入后请编辑并填写凭据。" : ""));
        note.setFont(NativeTheme.FONT_CAPTION);
        note.setForeground(NativeTheme.MUTED);
        JPanel notePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        notePanel.setBackground(NativeTheme.SURFACE);
        notePanel.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        notePanel.add(note);

        JButton cancel = UiKit.button("取消", null, UiKit.ButtonStyle.GHOST);
        cancel.setMnemonic('C');
        cancel.addActionListener(event -> dispose());
        JButton apply = UiKit.button("执行导入", null, UiKit.ButtonStyle.PRIMARY);
        apply.setMnemonic('I');
        apply.addActionListener(event -> accept(existing));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setBackground(NativeTheme.SURFACE);
        actions.setBorder(BorderFactory.createEmptyBorder(4, 12, 10, 12));
        actions.add(cancel);
        actions.add(apply);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(notePanel, BorderLayout.CENTER);
        footer.add(actions, BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        UiKit.configureDialog(this, apply);
    }

    private void accept(
            List<io.github.lexaquila.lyradb.desktop.model.DesktopConnection> existing) {
        try {
            result = planner.resolve(existing, preview, model.decisions());
            if (result.toSave().isEmpty()) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "当前决策不会导入任何连接。是否关闭预览？",
                        "没有待导入连接", JOptionPane.YES_NO_OPTION,
                        JOptionPane.INFORMATION_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) {
                    result = null;
                    return;
                }
            }
            dispose();
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(),
                    "导入决策需要调整", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static String policyText(CredentialExportPolicy policy) {
        return switch (policy) {
            case OMIT -> "凭据未包含";
            case PASSWORD_ENCRYPTED -> "凭据已由导出密码解密";
            case PLAINTEXT -> "来源文件包含明文数据库凭据";
        };
    }

    private static final class PreviewTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {
                "连接", "类型", "冲突", "凭据", "处理", "目标名称"
        };

        private final List<Row> rows = new ArrayList<>();

        private PreviewTableModel(
                List<ConnectionImportPlanner.PreviewItem> preview,
                ConnectionTransferService.ImportBundle bundle) {
            for (ConnectionImportPlanner.PreviewItem item : preview) {
                Set<String> keys = bundle.connections().get(item.index()).credentialKeys();
                String credential = bundle.credentialPolicy()
                        == CredentialExportPolicy.OMIT && !keys.isEmpty()
                        ? "需补录：" + String.join("、", keys)
                        : bundle.credentialPolicy() == CredentialExportPolicy.OMIT
                        ? "未包含凭据" : "已包含";
                rows.add(new Row(item, item.defaultAction(),
                        item.suggestedName(), credential));
            }
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 4 ? ConnectionImportPlanner.Action.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.item.incoming().getName();
                case 1 -> row.item.incoming().getDbType();
                case 2 -> row.item.conflictDescription();
                case 3 -> row.credential;
                case 4 -> row.action;
                case 5 -> row.action == ConnectionImportPlanner.Action.RENAME
                        ? row.targetName : "";
                default -> "";
            };
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 4
                    || columnIndex == 5
                    && rows.get(rowIndex).action
                    == ConnectionImportPlanner.Action.RENAME;
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);
            if (columnIndex == 4 && value instanceof ConnectionImportPlanner.Action action) {
                row.action = action;
                fireTableRowsUpdated(rowIndex, rowIndex);
            } else if (columnIndex == 5) {
                row.targetName = value == null ? "" : value.toString();
            }
        }

        private List<ConnectionImportPlanner.Decision> decisions() {
            List<ConnectionImportPlanner.Decision> result = new ArrayList<>();
            for (Row row : rows) {
                result.add(new ConnectionImportPlanner.Decision(
                        row.item.index(), row.action, row.targetName));
            }
            return result;
        }

        private static final class Row {
            private final ConnectionImportPlanner.PreviewItem item;
            private ConnectionImportPlanner.Action action;
            private String targetName;
            private final String credential;

            private Row(ConnectionImportPlanner.PreviewItem item,
                    ConnectionImportPlanner.Action action,
                    String targetName, String credential) {
                this.item = item;
                this.action = action;
                this.targetName = targetName;
                this.credential = credential;
            }
        }
    }

    private static final class ActionTable extends JTable {

        private ActionTable(PreviewTableModel model) {
            super(model);
        }

        @Override
        public TableCellEditor getCellEditor(int row, int column) {
            if (column != 4) {
                return super.getCellEditor(row, column);
            }
            int modelRow = convertRowIndexToModel(row);
            PreviewTableModel model = (PreviewTableModel) getModel();
            ConnectionImportPlanner.ConflictKind kind =
                    model.rows.get(modelRow).item.conflictKind();
            ConnectionImportPlanner.Action[] allowed = switch (kind) {
                case NONE -> new ConnectionImportPlanner.Action[]{
                        ConnectionImportPlanner.Action.IMPORT,
                        ConnectionImportPlanner.Action.SKIP,
                        ConnectionImportPlanner.Action.RENAME
                };
                case EXISTING -> new ConnectionImportPlanner.Action[]{
                        ConnectionImportPlanner.Action.SKIP,
                        ConnectionImportPlanner.Action.RENAME,
                        ConnectionImportPlanner.Action.OVERWRITE
                };
                case FILE_DUPLICATE -> new ConnectionImportPlanner.Action[]{
                        ConnectionImportPlanner.Action.SKIP,
                        ConnectionImportPlanner.Action.RENAME
                };
            };
            JComboBox<ConnectionImportPlanner.Action> box = new JComboBox<>(allowed);
            return new DefaultCellEditor(box);
        }
    }
}
