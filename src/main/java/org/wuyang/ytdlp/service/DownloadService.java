package org.wuyang.ytdlp.service;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wuyang.ytdlp.config.YtDlpProperties;
import org.wuyang.ytdlp.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 下载服务
 *
 * <p>核心业务逻辑层，负责：</p>
 * <ol>
 *   <li>遍历多个 YouTube 客户端获取格式列表并去重排序</li>
 *   <li>自动选取最高码率的视频和音频</li>
 *   <li>执行下载并返回结果</li>
 *   <li>收集诊断信息（年龄限制、PO Token 状态等）</li>
 * </ol>
 *
 * @author wuyang
 */
@Service
public class DownloadService {

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);

    private final YtDlpRunner runner;
    private final PotProvider potProvider;
    private final YtDlpProperties props;
    private final ObjectMapper objectMapper;
    private final AudioLanguageRecognitionService audioLanguageRecognitionService;
    private final ExecutorService formatExecutor;

    public DownloadService(YtDlpRunner runner, PotProvider potProvider, YtDlpProperties props,
                           ObjectMapper objectMapper,
                           AudioLanguageRecognitionService audioLanguageRecognitionService) {
        this.runner = runner;
        this.potProvider = potProvider;
        this.props = props;
        this.objectMapper = objectMapper;
        this.audioLanguageRecognitionService = audioLanguageRecognitionService;
        int clientCount = props.youtube().clientArray().length;
        int parallelism = props.youtube().parallelism() > 0 ? props.youtube().parallelism() : 4;
        this.formatExecutor = Executors.newFixedThreadPool(Math.min(parallelism, Math.max(1, clientCount)));
    }

    @PreDestroy
    public void shutdownFormatExecutor() {
        formatExecutor.shutdownNow();
    }

    /**
     * 获取视频的可用格式列表
     *
     * @param url       YouTube 视频 URL
     * @param cookieMode Cookie 来源（FILE / FIREFOX / NONE）
     * @return 格式列表响应
     */
    public FormatListResponse listFormats(String url, String cookieMode) {
        log.info("event=formats.start url={} cookieMode={}", url, cookieMode);
        runner.resetState();
        runner.setCookieMode(parseCookieMode(cookieMode));

        List<Format> formats = fetchFormats(url, parseCookieMode(cookieMode));
        if (formats.isEmpty()) {
            log.warn("event=formats.empty url={} cookieMode={} lastClient={} potReady={}",
                url, cookieMode, runner.lastClient(), potProvider.isReady());
            return FormatListResponse.fail(noFormatsMessage());
        }
        log.info("event=formats.success url={} count={} videoCount={} audioCount={}", url, formats.size(),
            formats.stream().filter(format -> !format.audioOnly()).count(),
            formats.stream().filter(format -> format.audioOnly()).count());
        List<Format> identifiedFormats = audioLanguageRecognitionService.recognize(url, cookieMode, formats);
        return FormatListResponse.ok(identifiedFormats);
    }

    /**
     * 执行下载
     *
     * @param request 下载请求
     * @return 下载响应
     */
    public DownloadResponse download(DownloadRequest request) {
        return download(request, null);
    }

    /** 执行下载，并将 yt-dlp 输出交给任务状态记录器 */
    public DownloadResponse download(DownloadRequest request, Consumer<String> outputListener) {
        return download(request, outputListener, null);
    }

    /** Execute a download with task-level pause control. */
    public DownloadResponse download(DownloadRequest request, Consumer<String> outputListener,
                                     DownloadControl control) {
        log.info("event=download.start url={} mode={} cookieMode={} formatId={}",
            request.url(), request.mode(), request.cookieMode(), request.formatId());
        runner.resetState();
        runner.setCookieMode(parseCookieMode(request.cookieMode()));

        // 获取格式列表
        List<Format> formats = fetchFormats(request.url(), parseCookieMode(request.cookieMode()));
        if (formats.isEmpty()) {
            log.warn("event=download.formats_empty url={} mode={} lastClient={} potReady={}",
                    request.url(), request.mode(), runner.lastClient(), potProvider.isReady());
            return DownloadResponse.fail(noFormatsMessage());
        }

        // 自动选取最高码率的视频和音频
        List<Format> videoFormats = formats.stream().filter(f -> !f.audioOnly()).toList();
        List<Format> audioFormats = formats.stream().filter(format -> format.audioOnly()).toList();
        String bestVideoId = videoFormats.isEmpty() ? "bestvideo" : videoFormats.get(0).id();
        String bestAudioId = audioFormats.isEmpty() ? "bestaudio" : audioFormats.get(0).id();

        // 收集诊断信息
        List<String> diagnostics = collectDiagnostics(videoFormats, audioFormats);

        // 根据下载方式构建格式表达式
        String formatExpr = buildFormatExpr(request, bestVideoId, bestAudioId);

        // 执行下载
        String filePath = executeDownload(request.url(), formatExpr, request.mode(), outputListener, control);
        if (filePath == null) {
            log.error("event=download.output_missing url={} mode={} format={}",
                    request.url(), request.mode(), formatExpr);
            return DownloadResponse.fail("下载失败");
        }

        String bestVideo = videoFormats.isEmpty() ? "N/A"
                : videoFormats.get(0).id() + " " + videoFormats.get(0).ext() + " | " + videoFormats.get(0).res()
                + " | " + videoFormats.get(0).tbr();
        String bestAudio = audioFormats.isEmpty() ? "N/A"
                : audioFormats.get(0).id() + " " + audioFormats.get(0).ext() + " | " + audioFormats.get(0).tbr()
                + " | " + audioFormats.get(0).acodec();

        String fileName = Path.of(filePath).getFileName().toString();
        log.info("event=download.success url={} mode={} file={} format={}",
            request.url(), request.mode(), fileName, formatExpr);
        return DownloadResponse.ok(filePath, fileName, bestVideo, bestAudio, diagnostics);
    }

    // ===================== 格式获取 =====================

    /**
     * 遍历多个客户端获取格式列表
     *
     * <p>依次尝试多个 YouTube player_client，合并去重后排序。</p>
     */
    private List<Format> fetchFormats(String url, YtDlpRunner.CookieMode requestCookieMode) {
        long startedAt = System.nanoTime();
        Map<String, Format> dedup = new LinkedHashMap<>();
        List<String> clients = List.of(props.youtube().clientArray()).stream()
            .map(client -> client.trim()).filter(client -> !client.isBlank()).toList();

        ExecutorCompletionService<ClientFormats> completionService = new ExecutorCompletionService<>(formatExecutor);
        for (String client : clients) {
            completionService.submit(() -> tryList(url, client, requestCookieMode));
        }
        for (int completed = 0; completed < clients.size(); completed++) {
            try {
                ClientFormats result = completionService.take().get();
                runner.updateDiagnostics(result.output(), result.client());
                for (Format f : result.formats()) {
                    dedup.putIfAbsent(f.id() + "|" + f.ext(), f);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("event=formats.interrupted url={} message={}", url, e.getMessage());
                break;
            } catch (ExecutionException e) {
                log.warn("event=formats.worker_failed url={} message={}", url, e.getCause());
            }
        }

        List<Format> list = new ArrayList<>(dedup.values());
        // 排序：视频在前音频在后；视频按画质降序→文件大小降序；音频按码率降序→文件大小降序
        list.sort((a, b) -> {
            if (a.audioOnly() != b.audioOnly())
                return a.audioOnly() ? 1 : -1;
            if (a.audioOnly()) {
                int cmp = Integer.compare(b.tbrValue(), a.tbrValue());
                if (cmp != 0) return cmp;
                return Double.compare(b.sizeValue(), a.sizeValue());
            } else {
                int cmp = Integer.compare(b.height(), a.height());
                if (cmp != 0) return cmp;
                return Double.compare(b.sizeValue(), a.sizeValue());
            }
        });
        log.info("event=formats.probe_complete url={} clients={} formats={} elapsedMs={}",
            url, clients.size(), list.size(), (System.nanoTime() - startedAt) / 1_000_000);
        return list;
    }

    /** 尝试用指定客户端获取格式列表 */
    private ClientFormats tryList(String url, String client, YtDlpRunner.CookieMode requestCookieMode) {
        try {
            runner.setCookieMode(requestCookieMode);
            String out = runner.getFormatInfoJson(url, client);

            return new ClientFormats(client, out, FormatParser.parseJson(out, objectMapper));
        } catch (Exception e) {
            log.warn("event=formats.client_failed url={} client={} message={}", url, client, e.getMessage());
            log.debug("event=formats.client_failed_stack url={} client={}", url, client, e);
            return new ClientFormats(client, e.getMessage() == null ? "" : e.getMessage(), List.of());
        }
    }

    private String noFormatsMessage() {
        if (runner.needsBotCheck()) {
            return "YouTube 要求验证“并非机器人”，当前请求的登录凭据可能无效、过期或未成功加载。"
                    + "请确认 cookies.txt 是从已登录 YouTube 的浏览器导出的有效文件，"
                    + "或切换为 Firefox 登录态后重试；若仍失败，可能是当前网络/IP 受到临时验证限制。";
        }
        return "无可用格式: cookies / PO Token / 客户端问题";
    }

    private record ClientFormats(String client, String output, List<Format> formats) {
    }

    // ===================== 下载 =====================

    /** 根据下载方式构建 yt-dlp 格式表达式 */
    private String buildFormatExpr(DownloadRequest request, String bestVideoId, String bestAudioId) {
        return switch (request.mode()) {
            case BEST_MERGE -> bestVideoId + "+" + bestAudioId
                    + "/" + bestVideoId + "+bestaudio"
                    + "/bestvideo+bestaudio";
                case VIDEO_ONLY -> request.formatId() != null && !request.formatId().isBlank()
                    ? request.formatId() : bestVideoId + "/bestvideo";
                case AUDIO_ONLY -> request.formatId() != null && !request.formatId().isBlank()
                    ? request.formatId() : bestAudioId + "/bestaudio";
            case CUSTOM -> request.formatId() != null && !request.formatId().isBlank()
                    ? request.formatId() : "bestvideo+bestaudio";
        };
    }

    /** 执行下载，返回输出文件路径 */
    private String executeDownload(String url, String format, DownloadMode mode, Consumer<String> outputListener,
                                   DownloadControl control) {
        try {
            Files.createDirectories(Path.of(props.output().dir()));

            List<String> cmd = runner.baseCmd();
                cmd.addAll(List.of("-f", format));
                if (props.download().forceIpv4()) {
                    cmd.add("--force-ipv4");
                }
                if (mode == DownloadMode.BEST_MERGE || mode == DownloadMode.CUSTOM || mode == DownloadMode.VIDEO_ONLY) {
                cmd.addAll(List.of("--merge-output-format", "mp4", "--remux-video", "mp4"));
                }
                cmd.addAll(List.of(
                    "--windows-filenames",
                    "--add-metadata",
                    "--retries", String.valueOf(props.download().retries()),
                    "--fragment-retries", String.valueOf(props.download().fragmentRetries()),
                    "--retry-sleep", "fragment:exp=1:16",
                    "--retry-sleep", "http:exp=1:16",
                    "--file-access-retries", String.valueOf(props.download().httpRetries()),
                    "--continue",
                    "--newline",
                    "--progress",
                    "--print", "after_move:filepath",
                    "-o", props.output().dir() + "/" + props.output().template(),
                    url));
            if (props.download().httpChunkSize() > 0) {
                cmd.add(cmd.size() - 1, "--http-chunk-size");
                cmd.add(cmd.size() - 1, props.download().httpChunkSize() + "M");
            }
            String out = runner.run(cmd, outputListener, control);
            return extractFilePath(out);
        } catch (YtDlpRunner.DownloadPausedException e) {
            throw e;
        } catch (Exception e) {
            log.error("event=download.execution_failed url={} mode={} format={} outputDir={} message={}",
                    url, mode, format, props.output().dir(), e.getMessage(), e);
            return null;
        }
    }

    /** 从 yt-dlp --print 输出中提取实际文件路径 */
    private String extractFilePath(String output) {
        String[] lines = output.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("[") || line.startsWith("WARNING")) continue;
            Path p = Path.of(line);
            if (Files.exists(p)) return line;
        }
        // 回退：扫描输出目录找最新文件
        try {
            return Files.list(Path.of(props.output().dir()))
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            log.warn("event=download.file_timestamp_failed file={} message={}", p, e.getMessage(), e);
                            return 0;
                        }
                    }))
                    .map(p -> p.toString()).orElse(null);
        } catch (IOException e) {
            log.error("event=download.output_scan_failed outputDir={} message={}",
                    props.output().dir(), e.getMessage(), e);
            return null;
        }
    }

    // ===================== 诊断 =====================

    /** 收集诊断信息 */
    private List<String> collectDiagnostics(List<Format> videoFormats, List<Format> audioFormats) {
        List<String> diagnostics = new ArrayList<>();

        if (runner.isAgeLimited()) {
            diagnostics.add("检测到年龄限制视频，可能需要登录 cookies 才能获取完整格式");
        }
        if (runner.needsPot() && !potProvider.isReady()) {
            diagnostics.add("需要 GVS PO Token，但 Provider 未就绪（将缺失 mweb/web 高清）");
        }
        diagnostics.add("当前客户端: " + runner.lastClient());

        if (!videoFormats.isEmpty()) {
            Format v = videoFormats.get(0);
            diagnostics.add("最佳视频: " + v.id() + " " + v.ext() + " | " + v.res()
                    + " | " + v.tbr() + " | " + v.size());
        }
        if (!audioFormats.isEmpty()) {
            Format a = audioFormats.get(0);
            diagnostics.add("最佳音频: " + a.id() + " " + a.ext() + " | " + a.tbr()
                    + " | " + a.acodec() + " | " + a.size());
        }
        return diagnostics;
    }

    // ===================== 工具 =====================

    /** 解析 Cookie 来源模式 */
    private YtDlpRunner.CookieMode parseCookieMode(String mode) {
        if (mode == null || mode.isBlank()) return YtDlpRunner.CookieMode.FILE;
        return switch (mode.toUpperCase()) {
            case "FIREFOX" -> YtDlpRunner.CookieMode.FIREFOX;
            case "NONE" -> YtDlpRunner.CookieMode.NONE;
            default -> YtDlpRunner.CookieMode.FILE;
        };
    }
}
