package org.wuyang.ytdlp.service;

import org.springframework.stereotype.Service;
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

    private final YtDlpRunner runner;
    private final PotProvider potProvider;
    private final YtDlpProperties props;

    public DownloadService(YtDlpRunner runner, PotProvider potProvider, YtDlpProperties props) {
        this.runner = runner;
        this.potProvider = potProvider;
        this.props = props;
    }

    /**
     * 获取视频的可用格式列表
     *
     * @param url       YouTube 视频 URL
     * @param cookieMode Cookie 来源（FILE / FIREFOX / NONE）
     * @return 格式列表响应
     */
    public FormatListResponse listFormats(String url, String cookieMode) {
        runner.resetState();
        runner.setCookieMode(parseCookieMode(cookieMode));

        List<Format> formats = fetchFormats(url);
        if (formats.isEmpty()) {
            return FormatListResponse.fail("无可用格式: cookies / PO Token / 客户端问题");
        }
        return FormatListResponse.ok(formats);
    }

    /**
     * 执行下载
     *
     * @param request 下载请求
     * @return 下载响应
     */
    public DownloadResponse download(DownloadRequest request) {
        runner.resetState();
        runner.setCookieMode(parseCookieMode(request.cookieMode()));

        // 获取格式列表
        List<Format> formats = fetchFormats(request.url());
        if (formats.isEmpty()) {
            return DownloadResponse.fail("无可用格式: cookies / PO Token / 客户端问题");
        }

        // 自动选取最高码率的视频和音频
        List<Format> videoFormats = formats.stream().filter(f -> !f.audioOnly()).toList();
        List<Format> audioFormats = formats.stream().filter(Format::audioOnly).toList();
        String bestVideoId = videoFormats.isEmpty() ? "bestvideo" : videoFormats.get(0).id();
        String bestAudioId = audioFormats.isEmpty() ? "bestaudio" : audioFormats.get(0).id();

        // 收集诊断信息
        List<String> diagnostics = collectDiagnostics(videoFormats, audioFormats);

        // 根据下载方式构建格式表达式
        String formatExpr = buildFormatExpr(request, bestVideoId, bestAudioId);

        // 执行下载
        String filePath = executeDownload(request.url(), formatExpr);
        if (filePath == null) {
            return DownloadResponse.fail("下载失败");
        }

        String bestVideo = videoFormats.isEmpty() ? "N/A"
                : videoFormats.get(0).id() + " " + videoFormats.get(0).ext() + " | " + videoFormats.get(0).res()
                + " | " + videoFormats.get(0).tbr();
        String bestAudio = audioFormats.isEmpty() ? "N/A"
                : audioFormats.get(0).id() + " " + audioFormats.get(0).ext() + " | " + audioFormats.get(0).tbr()
                + " | " + audioFormats.get(0).acodec();

        String fileName = Path.of(filePath).getFileName().toString();
        return DownloadResponse.ok(filePath, fileName, bestVideo, bestAudio, diagnostics);
    }

    // ===================== 格式获取 =====================

    /**
     * 遍历多个客户端获取格式列表
     *
     * <p>依次尝试多个 YouTube player_client，合并去重后排序。</p>
     */
    private List<Format> fetchFormats(String url) {
        Map<String, Format> dedup = new LinkedHashMap<>();

        for (String client : props.youtube().clientArray()) {
            for (Format f : tryList(url, client.trim())) {
                dedup.putIfAbsent(f.id() + "|" + f.ext(), f);
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
        return list;
    }

    /** 尝试用指定客户端获取格式列表 */
    private List<Format> tryList(String url, String client) {
        try {
            List<String> cmd = runner.baseCmd();
            cmd.addAll(List.of(
                    "--extractor-args", "youtube:player_client=" + client,
                    "-F", url));
            String out = runner.run(cmd);

            runner.updateDiagnostics(out, client);
            return FormatParser.parse(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ===================== 下载 =====================

    /** 根据下载方式构建 yt-dlp 格式表达式 */
    private String buildFormatExpr(DownloadRequest request, String bestVideoId, String bestAudioId) {
        return switch (request.mode()) {
            case BEST_MERGE -> bestVideoId + "+" + bestAudioId
                    + "/" + bestVideoId + "+bestaudio"
                    + "/bestvideo+bestaudio";
            case VIDEO_ONLY -> bestVideoId + "/bestvideo";
            case AUDIO_ONLY -> bestAudioId + "/bestaudio";
            case CUSTOM -> request.formatId() != null && !request.formatId().isBlank()
                    ? request.formatId() : "bestvideo+bestaudio";
        };
    }

    /** 执行下载，返回输出文件路径 */
    private String executeDownload(String url, String format) {
        try {
            Files.createDirectories(Path.of(props.output().dir()));

            List<String> cmd = runner.baseCmd();
            cmd.addAll(List.of(
                    "-f", format,
                    "--merge-output-format", "mp4",
                    "--remux-video", "mp4",
                    "--restrict-filenames",
                    "--add-metadata",
                    "--retries", String.valueOf(props.download().retries()),
                    "--newline",
                    "--progress",
                    "--print", "after_move:filepath",
                    "-o", props.output().dir() + "/" + props.output().template(),
                    url));
            String out = runner.run(cmd);
            return extractFilePath(out);
        } catch (Exception e) {
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
                            return 0;
                        }
                    }))
                    .map(Path::toString).orElse(null);
        } catch (IOException e) {
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
