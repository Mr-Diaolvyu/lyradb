package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;
import io.github.lexaquila.lyradb.transfer.connection.CredentialExportPolicy;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Arrays;
import java.util.List;

/**
 * 全部本地连接的批量导出选项。
 */
final class ConnectionExportDialog extends JDialog {

    record ExportRequest(CredentialExportPolicy policy,
                         char[] exportPassword) implements AutoCloseable {
        ExportRequest {
            exportPassword = exportPassword == null
                    ? new char[0] : Arrays.copyOf(exportPassword, exportPassword.length);
        }

        @Override
        public char[] exportPassword() {
            return Arrays.copyOf(exportPassword, exportPassword.length);
        }

        @Override
        public void close() {
            Arrays.fill(exportPassword, '\0');
        }
    }

    private final JRadioButton omit =
            new JRadioButton("不含密码（默认）", true);
    private final JRadioButton encrypted =
            new JRadioButton("使用导出密码加密");
    private final JRadioButton plaintext =
            new JRadioButton("包含明文数据库凭据（高风险）");
    private final JPasswordField password = new JPasswordField(24);
    private final JPasswordField confirmation = new JPasswordField(24);
    private final JLabel warning = new JLabel();
    private ExportRequest result;

    private ConnectionExportDialog(JFrame owner,
            List<DesktopConnection> connections) {
        super(owner, "导出连接配置", Dialog.ModalityType.APPLICATION_MODAL);
        setIconImage(LyraIcons.applicationImage());
        buildUi(connections);
        setMinimumSize(new Dimension(620, 520));
        pack();
        setLocationRelativeTo(owner);
    }

    static ExportRequest show(JFrame owner, List<DesktopConnection> connections) {
        ConnectionExportDialog dialog =
                new ConnectionExportDialog(owner, connections);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void buildUi(List<DesktopConnection> connections) {
        getContentPane().setBackground(NativeTheme.BACKGROUND);
        JPanel content = new JPanel(new BorderLayout(0, 14));
        content.setBackground(NativeTheme.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JLabel summary = new JLabel(
                "将导出全部 " + connections.size() + " 个本地连接配置");
        summary.setFont(NativeTheme.FONT_TITLE);
        summary.setForeground(NativeTheme.FOREGROUND);
        content.add(summary, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.setOpaque(false);
        JList<String> list = new JList<>(connections.stream()
                .map(value -> value.getName() + "  [" + value.getDbType() + "]")
                .toArray(String[]::new));
        list.setFocusable(false);
        JScrollPane scroll = UiKit.scroll(list);
        scroll.setPreferredSize(new Dimension(560, 140));
        scroll.setBorder(BorderFactory.createLineBorder(NativeTheme.BORDER));
        center.add(scroll, BorderLayout.NORTH);
        center.add(createPolicyPanel(), BorderLayout.CENTER);
        content.add(center, BorderLayout.CENTER);

        JButton cancel = UiKit.button("取消", null, UiKit.ButtonStyle.GHOST);
        cancel.setMnemonic('C');
        cancel.addActionListener(event -> dispose());
        JButton next = UiKit.button("选择保存位置", null, UiKit.ButtonStyle.PRIMARY);
        next.setMnemonic('S');
        next.addActionListener(event -> accept());
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setOpaque(false);
        footer.add(cancel);
        footer.add(next);
        content.add(footer, BorderLayout.SOUTH);
        setContentPane(content);
        UiKit.configureDialog(this, next);
        updatePolicyState();
    }

    private JPanel createPolicyPanel() {
        JPanel panel = UiKit.card(new GridBagLayout());
        ButtonGroup group = new ButtonGroup();
        group.add(omit);
        group.add(encrypted);
        group.add(plaintext);
        omit.setOpaque(false);
        encrypted.setOpaque(false);
        plaintext.setOpaque(false);
        omit.addActionListener(event -> updatePolicyState());
        encrypted.addActionListener(event -> updatePolicyState());
        plaintext.addActionListener(event -> updatePolicyState());

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(2, 0, 5, 0);
        panel.add(omit, constraints);
        constraints.gridy++;
        panel.add(encrypted, constraints);
        constraints.gridy++;
        constraints.gridwidth = 1;
        constraints.insets = new Insets(4, 24, 4, 8);
        panel.add(new JLabel("导出密码"), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(password, constraints);
        constraints.gridy++;
        constraints.gridx = 0;
        constraints.weightx = 0;
        panel.add(new JLabel("确认密码"), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(confirmation, constraints);
        constraints.gridy++;
        constraints.gridx = 0;
        constraints.gridwidth = 2;
        constraints.insets = new Insets(7, 0, 5, 0);
        panel.add(plaintext, constraints);
        constraints.gridy++;
        warning.setFont(NativeTheme.FONT_CAPTION);
        constraints.insets = new Insets(2, 24, 2, 0);
        panel.add(warning, constraints);
        return panel;
    }

    private void updatePolicyState() {
        boolean needsPassword = encrypted.isSelected();
        password.setEnabled(needsPassword);
        confirmation.setEnabled(needsPassword);
        if (plaintext.isSelected()) {
            warning.setForeground(NativeTheme.ERROR);
            warning.setText("文件中的数据库凭据可被任何读取者直接查看。");
        } else if (needsPassword) {
            warning.setForeground(NativeTheme.MUTED);
            warning.setText("导出密码至少 8 个字符；LyraDB 不保存该密码。");
        } else {
            warning.setForeground(NativeTheme.MUTED);
            warning.setText("数据库凭据值不会写入导出文件，导入后需重新填写。");
        }
    }

    private void accept() {
        CredentialExportPolicy policy = omit.isSelected()
                ? CredentialExportPolicy.OMIT
                : encrypted.isSelected()
                ? CredentialExportPolicy.PASSWORD_ENCRYPTED
                : CredentialExportPolicy.PLAINTEXT;
        char[] first = password.getPassword();
        char[] second = confirmation.getPassword();
        try {
            if (policy == CredentialExportPolicy.PLAINTEXT) {
                int choice = JOptionPane.showConfirmDialog(this,
                        """
                                明文模式会把数据库凭据直接写入文件，
                                任何能读取文件的人都可以查看这些凭据。
                                确认选择明文模式吗？
                                """,
                        "确认高风险选项",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) {
                    return;
                }
            }
            if (policy == CredentialExportPolicy.PASSWORD_ENCRYPTED) {
                if (first.length < 8) {
                    showError("导出密码至少需要 8 个字符。");
                    password.requestFocusInWindow();
                    return;
                }
                if (!Arrays.equals(first, second)) {
                    showError("两次输入的导出密码不一致。");
                    confirmation.requestFocusInWindow();
                    return;
                }
            }
            result = new ExportRequest(policy,
                    policy == CredentialExportPolicy.PASSWORD_ENCRYPTED
                            ? first : new char[0]);
            dispose();
        } finally {
            Arrays.fill(first, '\0');
            Arrays.fill(second, '\0');
            password.setText("");
            confirmation.setText("");
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message,
                "导出选项不完整", JOptionPane.WARNING_MESSAGE);
    }
}
