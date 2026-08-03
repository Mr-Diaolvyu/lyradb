package io.github.lexaquila.lyradb.desktop.ui;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** 带明确关闭入口并支持鼠标中键关闭的工作区标签头。 */
final class ClosableTabHeader extends JPanel {

    private final JButton closeButton;

    ClosableTabHeader(
            JTabbedPane tabs,
            Component content,
            String title,
            Icon icon,
            Runnable closeAction) {
        super(new FlowLayout(FlowLayout.LEFT, 5, 0));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, 1, 0, 1));

        JLabel label = new JLabel(title, icon, JLabel.LEADING);
        label.setToolTipText(title);
        add(label);

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
                if (SwingMouse.isMiddleButton(event)
                        && tabs.indexOfComponent(content) >= 0) {
                    closeAction.run();
                }
            }
        };
        addMouseListener(middleClick);
        label.addMouseListener(middleClick);
    }

    JButton closeButton() {
        return closeButton;
    }

    /** 隔离 SwingUtilities 静态调用，便于保持标签头代码简洁。 */
    private static final class SwingMouse {
        private SwingMouse() {
        }

        private static boolean isMiddleButton(MouseEvent event) {
            return javax.swing.SwingUtilities.isMiddleMouseButton(event);
        }
    }
}
