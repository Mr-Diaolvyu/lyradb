package io.github.lexaquila.lyradb.config;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 桌面版系统托盘（PRD F10）。
 *
 * <p>
 * 仅在桌面打包运行时启用（jpackage 经 --java-options 注入
 * -Dapp.desktop.tray-enabled=true，并由启动类关闭 headless）。
 * 托盘菜单提供：打开管理界面（系统默认浏览器）、退出。
 * 服务器/容器部署不受影响（属性缺省关闭，且 headless 环境自动跳过）。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.desktop.tray-enabled", havingValue = "true")
public class DesktopTrayConfig {

    @Value("${server.port:8080}")
    private int serverPort;

    private TrayIcon trayIcon;

    /** 应用就绪后挂载托盘图标 */
    @EventListener(ApplicationReadyEvent.class)
    public void installTray() {
        if (java.awt.GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            log.info("当前环境不支持系统托盘，跳过挂载");
            return;
        }
        try {
            PopupMenu menu = new PopupMenu();

            MenuItem openItem = new MenuItem("Open LyraDB");
            openItem.addActionListener(e -> openBrowser());
            menu.add(openItem);

            menu.addSeparator();

            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(e -> System.exit(0));
            menu.add(exitItem);

            trayIcon = new TrayIcon(createIconImage(), "LyraDB", menu);
            trayIcon.setImageAutoSize(true);
            // 双击托盘图标直接打开界面
            trayIcon.addActionListener(e -> openBrowser());

            SystemTray.getSystemTray().add(trayIcon);
            log.info("系统托盘已挂载，双击图标打开 http://localhost:{}", serverPort);

            // 桌面版启动后自动打开浏览器
            openBrowser();
        } catch (Exception e) {
            log.warn("系统托盘挂载失败（不影响服务运行）: {}", e.getMessage());
        }
    }

    /** 用系统默认浏览器打开管理界面 */
    private void openBrowser() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI("http://localhost:" + serverPort));
            }
        } catch (Exception e) {
            log.warn("打开浏览器失败: {}", e.getMessage());
        }
    }

    /** 生成托盘图标：蓝色圆角底 + 白色 L（零资源文件依赖） */
    private BufferedImage createIconImage() {
        int size = 16;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x63, 0x66, 0xF1));
        g.fillRoundRect(0, 0, size, size, 6, 6);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.drawString("L", 5, 12);
        g.dispose();
        return img;
    }
}
