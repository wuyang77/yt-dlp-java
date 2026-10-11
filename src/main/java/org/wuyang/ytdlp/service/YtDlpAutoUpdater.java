package org.wuyang.ytdlp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.wuyang.ytdlp.config.YtDlpProperties;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * yt-dlp 自动更新器
 *
 * <p>应用启动后在后台守护线程中执行 yt-dlp 自更新（{@code --update-to <channel>}），
 * 不阻塞 Spring Boot 启动流程。更新结果仅记录日志，失败不影响应用运行。</p>
 *
 * <p>通过 {@code ytdlp.auto-update.enabled} 开关控制，{@code ytdlp.auto-update.channel}
 * 选择更新通道（stable / nightly / master）。若配置了 {@code ytdlp.http.proxy}
 * （例如经 Mieru 启动脚本注入的 {@code YTDLP_HTTP_PROXY} 环境变量），
 * 更新请求也会走该代理。</p>
 *
 * @author wuyang
 */
@Component
public class YtDlpAutoUpdater implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(YtDlpAutoUpdater.class);

    /** 自更新子进程最长等待时间（秒），超时强制终止 */
    private static final long UPDATE_TIMEOUT_SECONDS = 180;

    private final String ytDlpPath;
    private final String proxy;
    private final YtDlpProperties.AutoUpdate config;

    public YtDlpAutoUpdater(YtDlpProperties props) {
        this.ytDlpPath = props.bin().ytDlp();
        this.proxy = props.http().proxy();
        this.config = props.autoUpdate();
    }

    /** Spring 上下文就绪后触发：校验配置并启动后台更新线程 */
    @Override
    public void run(ApplicationArguments args) {
        if (!config.enabled()) {
            log.info("event=ytdlp.autoupdate.disabled");
            return;
        }
        if (ytDlpPath == null || ytDlpPath.isBlank() || !Files.isRegularFile(Path.of(ytDlpPath))) {
            log.warn("event=ytdlp.autoupdate.skipped reason=binary_not_found path={}", ytDlpPath);
            return;
        }
        Thread updater = new Thread(this::runUpdate, "ytdlp-auto-updater");
        updater.setDaemon(true);
        updater.start();
    }

    /** 执行自更新并记录结果；所有异常都被捕获，仅降级为日志 */
    private void runUpdate() {
        List<String> cmd = buildCommand();
        log.info("event=ytdlp.autoupdate.start cmd={}", String.join(" ", cmd));
        Process process = null;
        try {
            process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            String output = readOutput(process);
            if (!process.waitFor(UPDATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("event=ytdlp.autoupdate.timeout seconds={}", UPDATE_TIMEOUT_SECONDS);
                return;
            }
            if (process.exitValue() == 0) {
                log.info("event=ytdlp.autoupdate.done output={}", output);
            } else {
                log.warn("event=ytdlp.autoupdate.failed exitCode={} output={}", process.exitValue(), output);
            }
        } catch (IOException e) {
            log.warn("event=ytdlp.autoupdate.failed message={}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    /** 构建自更新命令：指定更新通道；配置了代理时附加 --proxy */
    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(ytDlpPath);
        String channel = config.channel();
        if (channel == null || channel.isBlank()) {
            cmd.add("-U");
        } else {
            cmd.add("--update-to");
            cmd.add(channel.trim());
        }
        if (proxy != null && !proxy.isBlank()) {
            cmd.add("--proxy");
            cmd.add(proxy.trim());
        }
        return cmd;
    }

    /** 读取子进程合并后的标准输出，限制长度防止日志膨胀 */
    private String readOutput(Process process) throws IOException {
        StringBuilder sb = new StringBuilder(1024);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                if (sb.length() < 512) {
                    sb.append(line);
                }
            }
        }
        return sb.toString();
    }
}
