package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.NativeDesktopApplication;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;

/**
 * 工作台欢迎页。
 */
final class WelcomePanel extends JPanel {

    WelcomePanel(Runnable newConnection, Runnable openAiSettings,
            Runnable openAiAssistant) {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(40, 36, 40, 36));

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel meta = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        meta.setOpaque(false);
        meta.setAlignmentX(Component.LEFT_ALIGNMENT);
        meta.add(UiKit.badge("v" + NativeDesktopApplication.VERSION,
                NativeTheme.FOREGROUND, NativeTheme.ACCENT_SOFT));
        content.add(meta);
        content.add(Box.createVerticalStrut(16));

        JLabel heading = UiKit.heading("LyraDB");
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(heading);
        content.add(Box.createVerticalStrut(10));

        JLabel subtitle = UiKit.body("选择一项操作开始。");
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(subtitle);
        content.add(Box.createVerticalStrut(30));

        JPanel actions = new JPanel(new GridLayout(1, 3, 14, 0));
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.setMaximumSize(new Dimension(900, 112));
        actions.add(actionCard("创建数据库连接", "填写连接参数",
                LyraIcons.Kind.ADD_DATABASE, newConnection, true));
        actions.add(actionCard("配置 AI", "设置服务商、模型和 API Key",
                LyraIcons.Kind.SETTINGS, openAiSettings, false));
        actions.add(actionCard("打开 AI 助手", "输入任务并查看建议",
                LyraIcons.Kind.AI, openAiAssistant, false));
        content.add(actions);
        content.add(Box.createVerticalGlue());

        add(content, BorderLayout.NORTH);
    }

    private static JButton actionCard(String title, String description,
            LyraIcons.Kind icon, Runnable action, boolean primary) {
        JButton button = new JButton("""
                <html><div style='width:150px'>
                <b style='font-size:13px'>%s</b><br>
                <span style='font-size:11px'>%s</span>
                </div></html>
                """.formatted(title, description),
                LyraIcons.of(icon, 24,
                        primary ? Color.WHITE : NativeTheme.ACCENT_LIGHT));
        button.setHorizontalAlignment(JButton.LEFT);
        button.setIconTextGap(11);
        button.setMargin(new Insets(14, 13, 14, 13));
        button.setBackground(primary ? NativeTheme.ACCENT_STRONG : NativeTheme.SURFACE_ALT);
        button.setForeground(primary ? Color.WHITE : NativeTheme.FOREGROUND);
        button.setBorder(BorderFactory.createLineBorder(
                primary ? NativeTheme.ACCENT_STRONG : NativeTheme.GLASS_BORDER));
        button.putClientProperty("JButton.buttonType", "roundRect");
        button.putClientProperty("FlatLaf.style", "arc: 12");
        button.addActionListener(event -> action.run());
        return button;
    }
}
