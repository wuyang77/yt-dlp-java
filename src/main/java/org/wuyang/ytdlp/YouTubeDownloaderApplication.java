package org.wuyang.ytdlp;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.wuyang.ytdlp.service.PotProvider;

/**
 * Spring Boot 应用启动类
 *
 * <p>基于 yt-dlp 的增强版下载服务，集成 PO Token Provider 以获取高清格式。
 * 设计原则：</p>
 * <ol>
 *   <li>Java 只做编排，不替 OS 管 socket</li>
 *   <li>PO Provider 必须真连通才算就绪（/ping 健康检查）</li>
 *   <li>yt-dlp 只认 HTTP Provider，不依赖 pip 插件</li>
 *   <li>路径配置外置到 application.yml，代码中不硬编码</li>
 *   <li>分层架构：config / model / service / controller</li>
 * </ol>
 *
 * @author wuyang
 */
@SpringBootApplication
public class YouTubeDownloaderApplication {

    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");
        configureJavaProxy();
        SpringApplication.run(YouTubeDownloaderApplication.class, args);
    }

    /**
     * 从 YTDLP_HTTP_PROXY 环境变量读取代理地址，设置 Java 系统代理属性。
     *
     * <p>使得后端自身的 HTTP 请求（如下载 bgutil-pot 二进制）也走代理翻墙。
     * Mieru 同端口同时支持 HTTP 和 SOCKS5，Java 的 http.proxyHost 只支持 HTTP 代理，
     * 因此把 socks5:// 前缀改写为 http://。localhost 和 127.0.0.1 排除在外，
     * 不影响本地 API 调用。</p>
     */
    private static void configureJavaProxy() {
        String raw = System.getenv("YTDLP_HTTP_PROXY");
        if (raw == null || raw.isBlank()) return;
        String url = raw.trim();
        // socks5://127.0.0.1:3080 → 127.0.0.1:3080
        int schemeEnd = url.indexOf("://");
        String hostPort = schemeEnd >= 0 ? url.substring(schemeEnd + 3) : url;
        int colon = hostPort.lastIndexOf(':');
        if (colon < 1) return;
        String host = hostPort.substring(0, colon);
        String port = hostPort.substring(colon + 1);
        System.setProperty("http.proxyHost", host);
        System.setProperty("http.proxyPort", port);
        System.setProperty("https.proxyHost", host);
        System.setProperty("https.proxyPort", port);
        System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1|::1");
    }

    /**
     * 应用启动时初始化 PO Token Provider
     *
     * <p>确保二进制存在 → 启动进程 → 健康检查 → 就绪标记</p>
     */
    @Bean
    CommandLineRunner initPotProvider(PotProvider potProvider) {
        return args -> {
            potProvider.ensureBinary();
            potProvider.start();
        };
    }
}
