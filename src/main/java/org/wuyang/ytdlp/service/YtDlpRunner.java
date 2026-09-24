package org.wuyang.ytdlp.service;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wuyang.ytdlp.config.YtDlpProperties;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * yt-dlp 命令构建器和执行器
 *
 * <p>封装 yt-dlp 的基础命令参数构建（UA、referer、cookie、PO Token 等）
 * 和子进程执行逻辑（实时进度输出、环境变量清理）。</p>
 *
 * <p>作为 Spring 单例 Bean 管理。注意：{@code cookieMode} 和诊断状态字段
 * 在每次请求前通过 {@link #resetState()} 重置，确保线程安全。</p>
 *
 * @author wuyang
 */
@Component
public class YtDlpRunner {

    private static final Logger log = LoggerFactory.getLogger(YtDlpRunner.class);

    private final String ytDlpPath;
    private final String ffmpegPath;
    private final String cookiesPath;
    private final String userAgent;
    private final String referer;
    private final String nodePath;
    private final Path pluginDir;
    private final String proxy;
    private final YtDlpProperties.Download downloadConfig;
    private final PotProvider potProvider;

    /** Cookie 来源模式 */
    public enum CookieMode {
        /** cookies.txt 文件 */
        FILE,
        /** Firefox 浏览器 */
        FIREFOX,
        /** 匿名（不使用 cookie） */
        NONE
    }

    /** 请求级诊断状态（ThreadLocal 保证线程隔离） */
    private final ThreadLocal<CookieMode> cookieMode = ThreadLocal.withInitial(() -> CookieMode.FILE);
    private final ThreadLocal<Boolean> detectedAgeLimit = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> detectedPotRequirement = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<String> lastActiveClient = ThreadLocal.withInitial(() -> "web_embedded");

    public YtDlpRunner(YtDlpProperties props, PotProvider potProvider) {
        this.ytDlpPath = props.bin().ytDlp();
        this.ffmpegPath = props.bin().ffmpeg();
        this.cookiesPath = props.bin().cookies();
        this.userAgent = props.http().userAgent();
        this.referer = props.http().referer();
        this.nodePath = props.bin().node();
        this.pluginDir = locatePluginDir();
        this.proxy = props.http().proxy();
        this.downloadConfig = props.download();
        this.potProvider = potProvider;
    }

    /** 重置诊断状态（每次请求前调用） */
    public void resetState() {
        cookieMode.set(CookieMode.FILE);
        detectedAgeLimit.set(false);
        detectedPotRequirement.set(false);
        lastActiveClient.set("web_embedded");
    }

    /** 设置 Cookie 来源模式 */
    public void setCookieMode(CookieMode mode) {
        cookieMode.set(mode);
    }

    /** 是否检测到年龄限制 */
    public boolean isAgeLimited() {
        return detectedAgeLimit.get();
    }

    /** 是否需要 PO Token */
    public boolean needsPot() {
        return detectedPotRequirement.get();
    }
    /** 获取最后活跃的客户端 */
    public String lastClient() {
        return lastActiveClient.get();
    }

    /** 构建基础命令参数（UA、referer、node、PO Token、cookie） */
    public List<String> baseCmd() {
        List<String> cmd = new ArrayList<>();
        cmd.add(ytDlpPath);
        cmd.add("--user-agent"); cmd.add(userAgent);
        cmd.add("--referer"); cmd.add(referer);
        cmd.add("--js-runtimes"); cmd.add("node:" + nodePath);
        // yt-dlp 新版需要 EJS Challenge 求解脚本；仅配置 Node.js 运行时还不够。
        // 脚本从官方 GitHub 组件源获取，不会绕过登录或年龄验证。
        cmd.add("--remote-components"); cmd.add("ejs:github");
        if (pluginDir != null) {
            cmd.add("--plugin-dirs"); cmd.add(pluginDir.toAbsolutePath().toString());
        }
        cmd.add("--socket-timeout"); cmd.add("30");
        cmd.add("--extractor-retries"); cmd.add("3");
        addNetworkConfig(cmd);

        if (potProvider != null && potProvider.isReady()) {
            cmd.add("--extractor-args");
            cmd.add("youtubepot-bgutilhttp:base_url=" + potProvider.baseUrl());
        }

        return switch (cookieMode.get()) {
            case FILE -> {
                if (Files.exists(Path.of(cookiesPath))) {
                    cmd.add("--cookies");
                    cmd.add(Path.of(cookiesPath).toAbsolutePath().toString());
                }
                yield cmd;
            }
            case FIREFOX -> {
                cmd.add("--cookies-from-browser");
                cmd.add("firefox");
                cmd.add("--age-limit"); cmd.add("18");
                yield cmd;
            }
            case NONE -> cmd;
        };
    }

    /** 执行命令，实时显示进度，返回完整输出文本 */
    public String run(List<String> cmd) throws Exception {
        return run(cmd, null);
    }

    /** 执行命令，并将每一行输出交给调用方用于进度展示 */
    public String run(List<String> cmd, Consumer<String> outputListener) throws Exception {
        return run(cmd, outputListener, null);
    }

    /** Execute yt-dlp with optional task-level pause control. */
    public String run(List<String> cmd, Consumer<String> outputListener, DownloadControl control) throws Exception {
        boolean formatListing = cmd.contains("-F") || cmd.contains("--list-formats");
        String operation = cmd.size() > 1 ? String.join(" ", cmd.subList(0, Math.min(2, cmd.size()))) : ytDlpPath;
        if (!formatListing) {
            log.info("event=ytdlp.start executable={} arguments={} argumentCount={}",
                    ytDlpPath, operation, cmd.size());
        }
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        // 清除 IDE 注入的 NODE_OPTIONS，避免 node shim 拦截 yt-dlp 的 JS 挑战求解
        pb.environment().remove("NODE_OPTIONS");
        Process p = pb.start();
        if (control != null) control.attach(p);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = p.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (outputListener != null) outputListener.accept(line);
                if (formatListing) {
                    sb.append(line).append('\n');
                    continue;
                }
                if (line.contains("[download]") && line.contains("%")) {
                    log.info("event=ytdlp.progress message={}", line);
                    continue;
                }
                if (line.contains("[download]") && !line.contains("%")) {
                    log.info("event=ytdlp.download message={}", line);
                    continue;
                }
                if (line.contains("ERROR")) {
                    log.error("event=ytdlp.error message={}", line);
                } else if (line.contains("WARNING")) {
                    log.warn("event=ytdlp.message message={}", line);
                } else {
                    log.info("event=ytdlp.output message={}", line);
                }
                sb.append(line).append('\n');
            }
        }
        int exitCode = p.waitFor();
        if (control != null) control.detach(p);
        if (control != null && control.isPaused()) {
            throw new DownloadPausedException();
        }
        if (!formatListing) {
            log.info("event=ytdlp.exit executable={} exitCode={}", ytDlpPath, exitCode);
        }
        if (exitCode != 0) {
            throw new IOException("yt-dlp 退出失败，退出码: " + exitCode
                    + "，原因: " + summarizeFailure(sb.toString()));
        }
        if (formatListing) {
            System.out.print(sb);
        }
        return sb.toString();
    }

    public static final class DownloadPausedException extends RuntimeException {
        public DownloadPausedException() {
            super("下载已暂停");
        }
    }

    /** 从 yt-dlp 输出中提取可操作的错误摘要，避免调用方只能看到退出码。 */
    private static String summarizeFailure(String output) {
        String summary = output.lines()
                .filter(line -> line.contains("ERROR") || line.contains("WARNING")
                    || line.toLowerCase().contains("error")
                    || line.contains("failed") || line.contains("reloaded"))
                .filter(line -> !line.trim().equals("ERROR:") && !line.trim().equals("ERROR"))
                .reduce((first, second) -> first + " | " + second)
                .orElse("未返回错误详情");
        return summary.length() > 1200 ? summary.substring(summary.length() - 1200) : summary;
    }

    /** 获取 yt-dlp 版本号 */
    public String getYtDlpVersion() {
        return getVersion(ytDlpPath, "--version");
    }

    /** 获取 ffmpeg 版本号 */
    public String getFfmpegVersion() {
        return getVersion(ffmpegPath, "-version").split("\\R")[0];
    }

    /** 获取 node 版本号 */
    public String getNodeVersion() {
        return getVersion(nodePath, "--version");
    }

    /** cookies.txt 是否存在 */
    public boolean cookiesExists() {
        return Files.exists(Path.of(cookiesPath));
    }

    /** 更新诊断状态（从命令输出中检测） */
    void updateDiagnostics(String output, String client) {
        String low = output.toLowerCase();
        if (low.contains("sign in to confirm your age")) detectedAgeLimit.set(true);
        if (low.contains("gvs po token")) detectedPotRequirement.set(true);
        if (output.contains("PO Token Providers: bgutil")) lastActiveClient.set(client);
    }

    private static String getVersion(String exe, String arg) {
        try {
            Process p = new ProcessBuilder(exe, arg).start();
            try (BufferedReader r = p.inputReader(StandardCharsets.UTF_8)) {
                return r.lines().findFirst().orElse("?").trim();
            }
        } catch (Exception e) {
            log.warn("event=version.check_failed executable={} argument={} message={}", exe, arg, e.getMessage(), e);
            return "不可用";
        }
    }

    /** 查找源码运行和 Spring Boot 打包运行时的 Provider 插件目录。 */
    private static Path locatePluginDir() {
        for (Path candidate : List.of(
                Path.of("src/main/resources/yt-dlp-plugins"),
                Path.of("target/classes/yt-dlp-plugins"),
                Path.of("yt-dlp-plugins"))) {
            if (Files.isDirectory(candidate.resolve("bgutil-ytdlp-pot-provider/yt_dlp_plugins/extractor"))) {
                return candidate;
            }
        }
        return null;
    }

    private void addNetworkConfig(List<String> cmd) {
        cmd.add("--fragment-retries"); cmd.add(String.valueOf(downloadConfig.fragmentRetries()));
        cmd.add("--retry-sleep"); cmd.add("http:exp=1:16");
        cmd.add("--retry-sleep"); cmd.add("fragment:exp=1:16");
        if (downloadConfig.forceIpv4()) {
            cmd.add("--force-ipv4");
        }
        if (proxy != null && !proxy.isBlank()) {
            cmd.add("--proxy"); cmd.add(proxy.trim());
        }
    }
}
