package io.github.lexaquila.lyradb.desktop.ui;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.KeyStroke;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.event.KeyEvent;

/**
 * 原生桌面统一组件样式。
 */
final class UiKit {

    enum ButtonStyle {
        PRIMARY,
        SECONDARY,
        GHOST,
        DANGER,
        TOOLBAR
    }

    private UiKit() {
    }

    static JButton button(String text, Icon icon, ButtonStyle style) {
        JButton button = new JButton(text, icon);
        button.setIconTextGap(8);
        button.setMargin(style == ButtonStyle.TOOLBAR
                ? new Insets(7, 9, 7, 9)
                : new Insets(8, 14, 8, 14));
        button.putClientProperty("JButton.buttonType",
                style == ButtonStyle.TOOLBAR ? "toolBarButton" : "roundRect");
        switch (style) {
            case PRIMARY -> {
                button.setBackground(NativeTheme.ACCENT_STRONG);
                button.setForeground(Color.WHITE);
            }
            case DANGER -> {
                button.setBackground(NativeTheme.ERROR_DARK);
                button.setForeground(Color.WHITE);
            }
            case SECONDARY -> {
                button.setBackground(NativeTheme.SURFACE_ALT);
                button.setForeground(NativeTheme.FOREGROUND);
            }
            case GHOST, TOOLBAR -> {
                button.setBackground(NativeTheme.BACKGROUND);
                button.setForeground(NativeTheme.FOREGROUND);
            }
        }
        return button;
    }

    static JButton iconButton(Icon icon, String tooltip) {
        JButton button = button("", icon, ButtonStyle.TOOLBAR);
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.setFocusable(true);
        button.setMargin(new Insets(7, 8, 7, 8));
        return button;
    }

    static JPanel card(LayoutManager layout) {
        JPanel panel = glass(layout, 12);
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 17, 16));
        panel.putClientProperty("FlatLaf.style", "arc: 12");
        return panel;
    }
    static JPanel glass(LayoutManager layout, int arc) {
        return new GlassPanel(layout, arc);
    }


    static JPanel section(String title, String subtitle, Component content) {
        JPanel panel = card(new BorderLayout(0, 12));
        JPanel heading = new JPanel(new BorderLayout(0, 3));
        heading.setOpaque(false);
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(NativeTheme.FONT_TITLE);
        titleLabel.setForeground(NativeTheme.FOREGROUND);
        heading.add(titleLabel, BorderLayout.NORTH);
        if (subtitle != null && !subtitle.isBlank()) {
            JLabel subtitleLabel = new JLabel(subtitle);
            subtitleLabel.setFont(NativeTheme.FONT_CAPTION);
            subtitleLabel.setForeground(NativeTheme.MUTED);
            heading.add(subtitleLabel, BorderLayout.SOUTH);
        }
        panel.add(heading, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    static JScrollPane scroll(Component view) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(NativeTheme.SURFACE);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        return scroll;
    }

    static JLabel eyebrow(String text) {
        JLabel label = new JLabel(text.toUpperCase());
        label.setForeground(NativeTheme.ACCENT_LIGHT);
        label.setFont(NativeTheme.FONT_EYEBROW);
        return label;
    }

    static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(NativeTheme.FOREGROUND);
        label.setFont(NativeTheme.FONT_HERO);
        return label;
    }

    static JLabel body(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(NativeTheme.MUTED);
        label.setFont(NativeTheme.FONT_BODY);
        return label;
    }

    static JLabel badge(String text, Color foreground, Color background) {
        JLabel label = new JLabel("  " + text + "  ", SwingConstants.CENTER);
        label.setOpaque(true);
        label.setForeground(foreground);
        label.setBackground(background);
        label.setFont(NativeTheme.FONT_CAPTION_BOLD);
        label.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        label.putClientProperty("FlatLaf.style", "arc: 999");
        return label;
    }

    static void configureDialog(JDialog dialog, JButton defaultButton) {
        dialog.getRootPane().setDefaultButton(defaultButton);
        dialog.getRootPane().registerKeyboardAction(
                event -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    static void makeMonospaced(Component component) {
        component.setFont(new Font("JetBrains Mono", Font.PLAIN, 13));
        if ("Dialog".equals(component.getFont().getFamily())) {
            component.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        }
    }
}
