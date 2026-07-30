package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.metadata.MetadataCapture;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;

/**
 * AI 元数据上下文的只读预览与附加确认。
 */
final class MetadataPreviewDialog extends JDialog {

    private boolean attach;

    private MetadataPreviewDialog(Window owner,
            MetadataCapture capture, String markdown, String json) {
        super(owner, "元数据预览", Dialog.ModalityType.APPLICATION_MODAL);
        setIconImage(LyraIcons.applicationImage());
        buildUi(capture, markdown, json);
        setMinimumSize(new Dimension(760, 520));
        setSize(940, 680);
        setLocationRelativeTo(owner);
    }

    static boolean show(Window owner, MetadataCapture capture,
            String markdown, String json) {
        MetadataPreviewDialog dialog =
                new MetadataPreviewDialog(owner, capture, markdown, json);
        dialog.setVisible(true);
        return dialog.attach;
    }

    private void buildUi(MetadataCapture capture, String markdown, String json) {
        getContentPane().setBackground(NativeTheme.BACKGROUND);

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBackground(NativeTheme.SURFACE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        0, 0, 1, 0, NativeTheme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(12, 16, 12, 16)));
        JLabel title = new JLabel(capture.scopeLabel());
        title.setFont(NativeTheme.FONT_TITLE);
        title.setForeground(NativeTheme.FOREGROUND);
        header.add(title, BorderLayout.WEST);
        JLabel statistics = new JLabel(
                capture.tableCount() + " 个表/视图 · "
                        + capture.columnCount() + " 列 · 约 "
                        + capture.estimatedTokens() + " Token");
        statistics.setFont(NativeTheme.FONT_CAPTION);
        statistics.setForeground(NativeTheme.MUTED);
        header.add(statistics, BorderLayout.EAST);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Markdown", text(markdown));
        tabs.addTab("JSON", text(json));
        tabs.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JButton keep = UiKit.button("暂不附加", null, UiKit.ButtonStyle.SECONDARY);
        keep.setMnemonic('N');
        keep.addActionListener(event -> {
            attach = false;
            dispose();
        });
        JButton attachButton =
                UiKit.button("附加到 AI", null, UiKit.ButtonStyle.PRIMARY);
        attachButton.setMnemonic('A');
        attachButton.addActionListener(event -> {
            attach = true;
            dispose();
        });
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setBackground(NativeTheme.SURFACE);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        1, 0, 0, 0, NativeTheme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        footer.add(keep);
        footer.add(attachButton);

        setLayout(new BorderLayout());
        add(header, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        UiKit.configureDialog(this, attachButton);
    }

    private static JScrollPane text(String value) {
        JTextArea area = new JTextArea(value == null ? "" : value);
        area.setEditable(false);
        area.setCaretPosition(0);
        area.setMargin(new java.awt.Insets(10, 10, 10, 10));
        UiKit.makeMonospaced(area);
        return UiKit.scroll(area);
    }
}
