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
 * 个人版工作台欢迎页。
 */
final class WelcomePanel extends JPanel {

    WelcomePanel(Runnable newConnection, Runnable openAiSettings,
            Runnable openAiAssistant) {
        super(new BorderLayout());
        setBackground(NativeTheme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(40, 36, 40, 36));

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel meta = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        meta.setOpaque(false);
        meta.setAlignmentX(Component.LEFT_ALIGNMENT);
        meta.add(UiKit.eyebrow("LYRADB PERSONAL"));
        meta.add(UiKit.badge("v" + NativeDesktopApplication.VERSION,
                NativeTheme.ACCENT_LIGHT, NativeTheme.ACCENT_SOFT));
        content.add(meta);
        content.add(Box.createVerticalStrut(16));

        JLabel heading = UiKit.heading("把数据库工作，留在一个原生工作台里");
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(heading);
        content.add(Box.createVerticalStrut(10));

        JLabel subtitle = UiKit.body(
                "数据库直连、SQL 开发、结构浏览、ER 分析与 AI 助手，均在本机原生进程中完成。");
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(subtitle);
        content.add(Box.createVerticalStrut(30));

        JPanel actions = new JPanel(new GridLayout(1, 3, 14, 0));
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.setMaximumSize(new Dimension(900, 112));
        actions.add(actionCard("创建数据库连接", "配置并直连 9 类数据库",
                LyraIcons.Kind.ADD_DATABASE, newConnection, true));
        actions.add(actionCard("配置个人 AI", "API Key 仅在本机加密保存",
                LyraIcons.Kind.SETTINGS, openAiSettings, false));
        actions.add(actionCard("打开 AI 助手", "生成、解释、修复与优化 SQL",
                LyraIcons.Kind.AI, openAiAssistant, false));
        content.add(actions);
        content.add(Box.createVerticalStrut(24));

        JPanel safety = UiKit.card(new BorderLayout(14, 0));
        safety.setAlignmentX(Component.LEFT_ALIGNMENT);
        safety.setMaximumSize(new Dimension(900, 78));
        JLabel shield = new JLabel(LyraIcons.of(
                LyraIcons.Kind.SHIELD, 28, NativeTheme.SUCCESS));
        safety.add(shield, BorderLayout.WEST);
        JPanel safetyText = new JPanel();
        safetyText.setOpaque(false);
        safetyText.setLayout(new BoxLayout(safetyText, BoxLayout.Y_AXIS));
        JLabel safetyTitle = new JLabel("本地优先，执行可控");
        safetyTitle.setFont(NativeTheme.FONT_TITLE);
        safetyTitle.setForeground(NativeTheme.FOREGROUND);
        JLabel safetyBody = new JLabel(
                "不启动浏览器、WebView 或本地 HTTP；AI 只提供建议，SQL 始终由你确认后执行。");
        safetyBody.setFont(NativeTheme.FONT_CAPTION);
        safetyBody.setForeground(NativeTheme.MUTED);
        safetyText.add(safetyTitle);
        safetyText.add(Box.createVerticalStrut(4));
        safetyText.add(safetyBody);
        safety.add(safetyText, BorderLayout.CENTER);
        content.add(safety);
        content.add(Box.createVerticalGlue());

        add(content, BorderLayout.NORTH);
    }

    private static JButton actionCard(String title, String description,
            LyraIcons.Kind icon, Runnable action, boolean primary) {
        String color = primary ? "#FFFFFF" : "#E5EAF2";
        String muted = primary ? "#EAF1FF" : "#94A3B8";
        JButton button = new JButton("""
                <html><div style='width:150px'>
                <b style='font-size:13px;color:%s'>%s</b><br>
                <span style='font-size:11px;color:%s'>%s</span>
                </div></html>
                """.formatted(color, title, muted, description),
                LyraIcons.of(icon, 24,
                        primary ? Color.WHITE : NativeTheme.ACCENT_LIGHT));
        button.setHorizontalAlignment(JButton.LEFT);
        button.setIconTextGap(11);
        button.setMargin(new Insets(14, 13, 14, 13));
        button.setBackground(primary ? NativeTheme.ACCENT_STRONG : NativeTheme.SURFACE);
        button.setForeground(primary ? Color.WHITE : NativeTheme.FOREGROUND);
        button.setBorder(BorderFactory.createLineBorder(
                primary ? NativeTheme.ACCENT_STRONG : NativeTheme.BORDER));
        button.putClientProperty("JButton.buttonType", "roundRect");
        button.putClientProperty("FlatLaf.style", "arc: 12");
        button.addActionListener(event -> action.run());
        return button;
    }
}
