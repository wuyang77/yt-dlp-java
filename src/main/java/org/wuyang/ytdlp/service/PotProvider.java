package org.wuyang.ytdlp.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wuyang.ytdlp.config.YtDlpProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * PO Token Provider 管理器
 *
 * <p>负责 bgutil-ytdlp-pot-provider 二进制的下载、启动和健康检查。
 * 设计原则：先测端口 → 再启动 → 再 ping，确保"真连通"才算就绪。</p>
 *
 * <p>作为 Spring 单例 Bean 管理，通过构造器注入配置。</p>
 *
 * @author wuyang
 */
@Component
public class PotProvider {

    private static final Logger log = LoggerFactory.getLogger(PotProvider.class);

    private static final int MAX_WAIT_ATTEMPTS = 10;
    private static final int WAIT_INTERVAL_MS = 500;

    private final String exePath;
    private final int port;
    private final String downloadUrl;
    private final String baseUrl;
    private final String defaultProxy;
    private String proxy;

    private Process process;
    private boolean ready = false;

    public PotProvider(YtDlpProperties props) {
        this.exePath = props.pot().path();
        this.port = props.pot().port();
        this.downloadUrl = props.pot().downloadUrl();
        this.baseUrl = "http://127.0.0.1:" + port;
        this.defaultProxy = toHttpProxy(props.http().proxy());
        this.proxy = defaultProxy;
    }

    /**
     * 将配置的代理地址转换为 Provider 子进程可用的 HTTP 代理地址。
     *
     * <p>Mieru 在同一端口同时支持 SOCKS5 与 HTTP 两种协议；bgutil-pot 的
     * HTTP 客户端对 SOCKS 的支持取决于编译特性，而 HTTP 代理必定可用，
     * 因此统一把 socks 开头的地址改写为 http 方案。未配置时返回 {@code null}。</p>
     */
    private static String toHttpProxy(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.regionMatches(true, 0, "socks", 0, 5)) {
            int schemeEnd = trimmed.indexOf("://");
            if (schemeEnd > 0) {
                return "http" + trimmed.substring(schemeEnd);
            }
        }
        return trimmed;
    }

    /** 获取 Provider 的 HTTP 基础 URL */
    public String baseUrl() {
        return baseUrl;
    }

    /** Provider 是否就绪 */
    public boolean isReady() {
        return ready;
    }

    /** Apply the request's proxy to the provider process, falling back to the server default. */
    public synchronized void configureProxy(String requestProxy) {
        String nextProxy = requestProxy == null || requestProxy.isBlank()
                ? defaultProxy : toHttpProxy(requestProxy);
        if (Objects.equals(proxy, nextProxy)) {
            if (!ready) {
                start();
            }
            return;
        }
        proxy = nextProxy;
        if (ready || (process != null && process.isAlive())) {
            stop();
            start();
        }
    }

    /** 确保二进制存在，不存在则自动下载 */
    public void ensureBinary() {
        if (Files.exists(Path.of(exePath))) {
            log.debug("event=pot.binary_present path={}", exePath);
            return;
        }
        log.info("event=pot.binary_download_start url={} path={}", downloadUrl, exePath);
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(downloadUrl).toURL().openConnection();
            conn.setInstanceFollowRedirects(true);
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(Path.of(exePath))) {
                in.transferTo(out);
            }
        } catch (IOException e) {
            // 下载失败不中断应用启动，后续降级运行
            log.error("event=pot.binary_download_failed url={} path={} message={}",
                    downloadUrl, exePath, e.getMessage(), e);
        }
    }

    /**
     * 启动 Provider 进程并等待就绪。
     *
     * <p>每次启动前先杀掉残留的 bgutil-pot 进程，确保不会复用上一次
     * 未带代理的旧进程（应用异常退出时子进程会残留，ping 成功但无代理）。</p>
     */
    public synchronized void start() {
        if (!Files.exists(Path.of(exePath))) return;

        // 杀掉残留进程，避免复用无代理的旧实例
        killStaleProcess();

        if (ping()) {
            ready = true;
            log.info("event=pot.ready endpoint={}", baseUrl);
            return;
        }

        log.info("event=pot.start endpoint={} path={}", baseUrl, exePath);
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    Path.of(exePath).toString(),
                    "server", "--host", "127.0.0.1", "--port", String.valueOf(port));
            // Provider 需要访问 YouTube 的 BotGuard 接口，必须走代理；
            // 通过标准环境变量把代理传递给子进程（其 HTTP 客户端会自动读取）
            if (proxy != null) {
                pb.environment().put("HTTPS_PROXY", proxy);
                pb.environment().put("HTTP_PROXY", proxy);
                log.info("event=pot.proxy_configured");
            }
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            process = pb.start();

            for (int i = 0; i < MAX_WAIT_ATTEMPTS; i++) {
                Thread.sleep(WAIT_INTERVAL_MS);
                if (ping()) {
                    ready = true;
                    log.info("event=pot.ready endpoint={} attempts={}", baseUrl, i + 1);
                    return;
                }
            }
            log.error("event=pot.start_timeout endpoint={} attempts={}", baseUrl, MAX_WAIT_ATTEMPTS);
        } catch (Exception e) {
            log.error("event=pot.start_failed endpoint={} message={}", baseUrl, e.getMessage(), e);
        }
    }

    /** 停止 Provider 进程 */
    public synchronized void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("event=pot.stop_interrupted endpoint={} message={}", baseUrl, e.getMessage(), e);
            }
        }
        process = null;
        ready = false;
    }

    /** 应用关闭时自动停止 Provider，防止进程残留 */
    @PreDestroy
    public void onDestroy() {
        stop();
    }

    /**
     * 杀掉残留的 bgutil-pot 进程。
     *
     * <p>应用异常退出（Ctrl+C / kill）时子进程不会被清理，下次启动时
     * ping 成功但旧进程无代理环境变量，BotGuard 调用必然失败。
     * 通过 taskkill 清理后再由本实例重新启动带代理的进程。</p>
     */
    private void killStaleProcess() {
        try {
            ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/IM", "bgutil-pot.exe");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            p.waitFor(3, TimeUnit.SECONDS);
            if (p.exitValue() == 0) {
                log.info("event=pot.stale_killed");
                Thread.sleep(500); // 等端口释放
            }
        } catch (Exception e) {
            log.debug("event=pot.kill_stale_failed message={}", e.getMessage());
        }
    }

    /** 健康检查：HTTP GET /ping */
    private boolean ping() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(1)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/ping"))
                    .timeout(Duration.ofSeconds(1)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            log.debug("event=pot.healthcheck_failed endpoint={} message={}", baseUrl, e.getMessage());
            return false;
        }
    }
}
