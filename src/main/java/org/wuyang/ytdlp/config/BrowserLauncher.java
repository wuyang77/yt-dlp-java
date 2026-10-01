package org.wuyang.ytdlp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 应用就绪后在本机打开前端页面。
 *
 * <p>Spring Boot 默认 headless，不会自动弹出浏览器；Windows 下用 {@code start} 打开。</p>
 */
@Component
@ConditionalOnWebApplication
public class BrowserLauncher {

    private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);

    private final Environment environment;

    @Value("${app.open-browser:true}")
    private boolean openBrowser;

    public BrowserLauncher(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openHomePage() {
        if (!openBrowser) {
            return;
        }
        String port = environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8080"));
        String url = "http://127.0.0.1:" + port + "/";
        try {
            open(url);
            log.info("event=ui.open_browser url={}", url);
        } catch (Exception e) {
            log.warn("event=ui.open_browser_failed url={} message={} hint=请手动访问 {}",
                    url, e.getMessage(), url);
        }
    }

    private static void open(String url) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        ProcessBuilder builder;
        if (os.contains("win")) {
            builder = new ProcessBuilder("cmd", "/c", "start", "", url);
        } else if (os.contains("mac")) {
            builder = new ProcessBuilder("open", url);
        } else {
            builder = new ProcessBuilder("xdg-open", url);
        }
        builder.start();
    }
}
