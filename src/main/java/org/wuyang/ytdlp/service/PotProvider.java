package org.wuyang.ytdlp.service;

import org.springframework.stereotype.Component;
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

    private static final int MAX_WAIT_ATTEMPTS = 10;
    private static final int WAIT_INTERVAL_MS = 500;

    private final String exePath;
    private final int port;
    private final String downloadUrl;
    private final String baseUrl;

    private Process process;
    private boolean ready = false;

    public PotProvider(YtDlpProperties props) {
        this.exePath = props.pot().path();
        this.port = props.pot().port();
        this.downloadUrl = props.pot().downloadUrl();
        this.baseUrl = "http://127.0.0.1:" + port;
    }

    /** 获取 Provider 的 HTTP 基础 URL */
    public String baseUrl() {
        return baseUrl;
    }

    /** Provider 是否就绪 */
    public boolean isReady() {
        return ready;
    }

    /** 确保二进制存在，不存在则自动下载 */
    public void ensureBinary() {
        if (Files.exists(Path.of(exePath))) {
            return;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(downloadUrl).toURL().openConnection();
            conn.setInstanceFollowRedirects(true);
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(Path.of(exePath))) {
                in.transferTo(out);
            }
        } catch (IOException e) {
            // 下载失败不中断应用启动，后续降级运行
            System.err.println("[PotProvider] 下载失败: " + e.getMessage());
        }
    }

    /** 启动 Provider 进程并等待就绪 */
    public void start() {
        if (!Files.exists(Path.of(exePath))) return;

        if (ping()) {
            ready = true;
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    Path.of(exePath).toString(),
                    "server", "--host", "127.0.0.1", "--port", String.valueOf(port));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            process = pb.start();

            for (int i = 0; i < MAX_WAIT_ATTEMPTS; i++) {
                Thread.sleep(WAIT_INTERVAL_MS);
                if (ping()) {
                    ready = true;
                    return;
                }
            }
            System.err.println("[PotProvider] 启动但未响应 /ping");
        } catch (Exception e) {
            System.err.println("[PotProvider] 启动异常: " + e.getMessage());
        }
    }

    /** 停止 Provider 进程 */
    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
            return false;
        }
    }
}
