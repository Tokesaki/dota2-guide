package com.dota.guide.desktop;

import org.springframework.boot.SpringApplication;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * DesktopLauncher —— 本地软件模式的启动入口（DotaGuideApplication.main 收到 --desktop 时调用）
 *
 * 1. 如果端口上已经有程序在跑（用户第二次双击图标），不再启动第二份，直接打开浏览器就退出；
 * 2. 否则打开 desktop profile 启动 Spring Boot，并关掉 headless 模式（托盘图标要用到图形界面）。
 */
public final class DesktopLauncher {

    private DesktopLauncher() {
    }

    public static void launch(Class<?> app, String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("DESKTOP_PORT", "17788"));
        if (alreadyRunning(port)) {
            DesktopShell.openBrowser("http://localhost:" + port + "/");
            return;
        }
        List<String> rest = new ArrayList<>();
        for (String a : args) {
            if (!"--desktop".equals(a)) {
                rest.add(a);
            }
        }
        SpringApplication sa = new SpringApplication(app);
        sa.setAdditionalProfiles("desktop");
        sa.setHeadless(false);
        sa.run(rest.toArray(new String[0]));
    }

    private static boolean alreadyRunning(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
