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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 桌面版系统托盘（PRD F10）。
 *
 * <p>仅在桌面打包运行时启用。服务监听随机回环端口；每次打开浏览器时签发
 * 一次性本机令牌，交换为 Session 后立即从地址栏移除。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.desktop.tray-enabled", havingValue = "true")
public class DesktopTrayConfig {

    private final WebServerApplicationContext webServerContext;
    private final ServletContext servletContext;
    private final DesktopAccessTokenService tokenService;

    private TrayIcon trayIcon;

    public DesktopTrayConfig(WebServerApplicationContext webServerContext,
                             ServletContext servletContext,
                             DesktopAccessTokenService tokenService) {
        this.webServerContext = webServerContext;
        this.servletContext = servletContext;
        this.tokenService = tokenService;
    }

    /** 应用就绪后挂载托盘图标。 */
    @EventListener(ApplicationReadyEvent.class)
    public void installTray() {
        if (java.awt.GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            log.info("当前环境不支持系统托盘，跳过挂载");
            return;
        }
        try {
            PopupMenu menu = new PopupMenu();

            MenuItem openItem = new MenuItem("Open LyraDB");
            openItem.addActionListener(event -> openBrowser());
            menu.add(openItem);

            menu.addSeparator();

            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(event -> System.exit(0));
            menu.add(exitItem);

            trayIcon = new TrayIcon(createIconImage(), "LyraDB", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(event -> openBrowser());

            SystemTray.getSystemTray().add(trayIcon);
            log.info("系统托盘已挂载，桌面服务运行于随机回环端口 {}", serverPort());
            openBrowser();
        } catch (Exception e) {
            log.warn("系统托盘挂载失败（不影响服务运行）: {}", e.getMessage());
        }
    }

    /** 用系统默认浏览器打开一次性令牌交换地址。 */
    private void openBrowser() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                String token = tokenService.issueToken();
                Desktop.getDesktop().browse(new URI(baseUrl()
                        + DesktopAccessGuardFilter.BOOTSTRAP_PATH + "?token=" + token));
            }
        } catch (Exception e) {
            log.warn("打开浏览器失败: {}", e.getMessage());
        }
    }

    private String baseUrl() {
        String contextPath = servletContext.getContextPath();
        if (contextPath == null || contextPath.isEmpty() || "/".equals(contextPath)) {
            contextPath = "";
        }
        return "http://127.0.0.1:" + serverPort() + contextPath;
    }

    /** 获取操作系统实际分配的随机端口。 */
    private int serverPort() {
        if (webServerContext.getWebServer() == null) {
            throw new IllegalStateException("Web 服务尚未完成初始化");
        }
        return webServerContext.getWebServer().getPort();
    }

    /** 生成托盘图标：蓝色圆角底 + 白色 L（零资源文件依赖）。 */
    private BufferedImage createIconImage() {
        int size = 16;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(0x63, 0x66, 0xF1));
        graphics.fillRoundRect(0, 0, size, size, 6, 6);
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        graphics.drawString("L", 5, 12);
        graphics.dispose();
        return image;
    }
}
