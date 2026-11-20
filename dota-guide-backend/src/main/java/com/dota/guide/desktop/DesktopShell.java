package com.dota.guide.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.net.URI;

/**
 * DesktopShell —— 本地软件的"外壳"：启动完成后自动打开浏览器，并在任务栏右下角放一个托盘图标
 *
 * 程序本身没有窗口（界面就是浏览器里的网页），所以需要托盘图标让用户能：
 *   - 再次打开页面（关掉浏览器标签后）；
 *   - 退出程序（否则它会一直在后台运行）。
 * 没有图形界面的环境（服务器、测试）里自动跳过。
 */
@Component
@Profile("desktop")
public class DesktopShell {

    private static final Logger log = LoggerFactory.getLogger(DesktopShell.class);

    private final ConfigurableApplicationContext context;
    private final String url;
    private final boolean openBrowser;

    public DesktopShell(ConfigurableApplicationContext context,
                        @Value("${server.port}") int port,
                        @Value("${desktop.open-browser:true}") boolean openBrowser) {
        this.context = context;
        this.url = "http://localhost:" + port + "/";
        this.openBrowser = openBrowser;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("Dota 2 复盘助手已启动：{}", url);
        // 图形界面出问题（没有显示器、远程桌面异常等）不能让整个程序挂掉：
        // 托盘和自动打开浏览器都只是锦上添花，失败了用户照样能手动访问上面的地址
        try {
            if (GraphicsEnvironment.isHeadless()) {
                return;
            }
            installTray();
        } catch (Throwable e) {
            log.warn("托盘图标不可用：{}", e.toString());
        }
        if (openBrowser) {
            openBrowser(url);
        }
    }

    private void installTray() {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("打开页面");
            open.addActionListener(e -> openBrowser(url));
            MenuItem quit = new MenuItem("退出");
            quit.addActionListener(e -> {
                SystemTray tray = SystemTray.getSystemTray();
                for (TrayIcon icon : tray.getTrayIcons()) {
                    tray.remove(icon);
                }
                int code = org.springframework.boot.SpringApplication.exit(context);
                System.exit(code);
            });
            menu.add(open);
            menu.addSeparator();
            menu.add(quit);

            TrayIcon icon = new TrayIcon(drawIcon(), "Dota 2 复盘助手", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> openBrowser(url));   // 双击托盘图标也打开页面
            SystemTray.getSystemTray().add(icon);
        } catch (Exception e) {
            log.warn("托盘图标创建失败：{}", e.getMessage());
        }
    }

    /** 画一个简单的红底金边方块当图标，省得再带图片文件 */
    private static BufferedImage drawIcon() {
        int size = 32;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(0xE3, 0x55, 0x48));
        g.fillRoundRect(2, 2, size - 4, size - 4, 8, 8);
        g.setColor(new java.awt.Color(0xF0, 0xB2, 0x32));
        g.setStroke(new java.awt.BasicStroke(3));
        g.drawRoundRect(2, 2, size - 4, size - 4, 8, 8);
        g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, 16f));
        g.setColor(java.awt.Color.WHITE);
        g.drawString("D", 10, 22);
        g.dispose();
        return img;
    }

    /** 用系统默认浏览器打开网址；Desktop API 不可用时退回到 Windows / macOS / Linux 的命令行方式 */
    public static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Throwable e) {
            LoggerFactory.getLogger(DesktopShell.class).warn("打开浏览器失败，请手动访问 {}：{}", url, e.getMessage());
        }
    }
}
