package io.github.lexaquila.lyradb.desktop.ui;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeListener;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** 带明确关闭入口并支持鼠标中键关闭的工作区标签头。 */
final class ClosableTabHeader extends JPanel {

    private static final int MAX_TITLE_CODE_POINTS = 42;

    private final JTabbedPane tabs;
    private final Component content;
    private final JLabel titleLabel;
    private final JButton closeButton;
    private final ChangeListener selectionListener;

    ClosableTabHeader(
            JTabbedPane tabs,
            Component content,
            String title,
            Icon icon,
            Runnable closeAction) {
        super(new FlowLayout(FlowLayout.LEFT, 5, 0));
        this.tabs = tabs;
        this.content = content;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, 1, 0, 1));
        setToolTipText(title);
        getAccessibleContext().setAccessibleName("标签页 " + title);

        titleLabel = new JLabel(displayTitle(title), icon, JLabel.LEADING);
        titleLabel.setToolTipText(title);
        add(titleLabel);

        closeButton = new JButton(LyraIcons.of(
                LyraIcons.Kind.CLOSE, 12, NativeTheme.MUTED));
        closeButton.setToolTipText("关闭标签页（Ctrl+W）");
        closeButton.getAccessibleContext().setAccessibleName(
                "关闭标签页 " + title);
        closeButton.setFocusable(false);
        closeButton.setBorder(BorderFactory.createEmptyBorder());
        closeButton.setBorderPainted(false);
        closeButton.setContentAreaFilled(false);
        closeButton.setMargin(new Insets(0, 0, 0, 0));
        closeButton.setPreferredSize(new Dimension(24, 24));
        closeButton.addActionListener(event -> closeAction.run());
        add(closeButton);

        MouseAdapter middleClick = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)
                        && tabs.indexOfComponent(content) >= 0) {
                    tabs.setSelectedComponent(content);
                    tabs.requestFocusInWindow();
                } else if (SwingUtilities.isMiddleMouseButton(event)
                        && tabs.indexOfComponent(content) >= 0) {
                    closeAction.run();
                }
            }
        };
        addMouseListener(middleClick);
        titleLabel.addMouseListener(middleClick);

        selectionListener = event -> updateSelectionState();
        tabs.addChangeListener(selectionListener);
        updateSelectionState();
    }

    JButton closeButton() {
        return closeButton;
    }

    JLabel titleLabel() {
        return titleLabel;
    }

    void disposeHeader() {
        tabs.removeChangeListener(selectionListener);
    }

    private void updateSelectionState() {
        boolean selected = tabs.getSelectedComponent() == content;
        titleLabel.setForeground(selected
                ? NativeTheme.FOREGROUND : NativeTheme.MUTED);
        titleLabel.setFont(titleLabel.getFont().deriveFont(
                selected ? Font.BOLD : Font.PLAIN));
        closeButton.setIcon(LyraIcons.of(
                LyraIcons.Kind.CLOSE, 12,
                selected ? NativeTheme.FOREGROUND : NativeTheme.MUTED));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        0, 0, selected ? 2 : 0, 0,
                        selected ? NativeTheme.ACCENT_LIGHT
                                : NativeTheme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(
                        0, 1, selected ? 0 : 2, 1)));
        revalidate();
        repaint();
    }

    static String displayTitle(String title) {
        if (title == null) {
            return "";
        }
        int count = title.codePointCount(0, title.length());
        if (count <= MAX_TITLE_CODE_POINTS) {
            return title;
        }
        int end = title.offsetByCodePoints(0, MAX_TITLE_CODE_POINTS - 1);
        return title.substring(0, end) + "…";
    }
}
