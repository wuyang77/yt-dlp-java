package org.wuyang.ytdlp.service;

import org.springframework.stereotype.Component;
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

    private final String ytDlpPath;
    private final String ffmpegPath;
    private final String cookiesPath;
    private final String userAgent;
    private final String referer;
    private final String nodePath;
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
        cmd.add("--socket-timeout"); cmd.add("30");
        cmd.add("--extractor-retries"); cmd.add("3");

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
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        // 清除 IDE 注入的 NODE_OPTIONS，避免 node shim 拦截 yt-dlp 的 JS 挑战求解
        pb.environment().remove("NODE_OPTIONS");
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = p.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (outputListener != null) outputListener.accept(line);
                if (line.contains("[download]") && line.contains("%")) {
                    System.out.print("\r" + line + "    ");
                    continue;
                }
                if (line.contains("[download]") && !line.contains("%")) {
                    System.out.print("\r" + line + "                              \n");
                    continue;
                }
                System.out.println(line);
                sb.append(line).append('\n');
            }
        }
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new IOException("yt-dlp 退出失败，退出码: " + exitCode);
        }
        return sb.toString();
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
            return "不可用";
        }
    }
}
