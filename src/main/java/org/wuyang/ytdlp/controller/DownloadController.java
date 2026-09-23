package org.wuyang.ytdlp.controller;

import org.springframework.web.bind.annotation.*;
import org.wuyang.ytdlp.model.DownloadRequest;
import org.wuyang.ytdlp.model.DownloadResponse;
import org.wuyang.ytdlp.model.FormatListResponse;
import org.wuyang.ytdlp.service.DownloadService;

/**
 * 下载 REST 控制器
 *
 * <p>提供以下 API 端点：</p>
 * <ul>
 *   <li>{@code GET  /api/formats?url=...}        获取格式列表</li>
 *   <li>{@code POST /api/download}              执行下载</li>
 *   <li>{@code GET  /api/health}                健康检查</li>
 * </ul>
 *
 * @author wuyang
 */
@RestController
@RequestMapping("/api")
public class DownloadController {

    private final DownloadService downloadService;

    public DownloadController(DownloadService downloadService) {
        this.downloadService = downloadService;
    }

    /**
     * 获取视频的可用格式列表
     *
     * @param url       YouTube 视频 URL
     * @param cookieMode Cookie 来源（FILE / FIREFOX / NONE，默认 FILE）
     */
    @GetMapping("/formats")
    public FormatListResponse getFormats(
            @RequestParam String url,
            @RequestParam(defaultValue = "FILE") String cookieMode) {
        return downloadService.listFormats(url, cookieMode);
    }

    /**
     * 执行下载
     *
     * <p>请求体示例：</p>
     * <pre>{@code
     * {
     *   "url": "https://www.youtube.com/watch?v=xxx",
     *   "mode": "BEST_MERGE",
     *   "cookieMode": "FILE",
     *   "formatId": null
     * }
     * }</pre>
     *
     * <p>mode 可选值：BEST_MERGE / VIDEO_ONLY / AUDIO_ONLY / CUSTOM</p>
     */
    @PostMapping("/download")
    public DownloadResponse download(@RequestBody DownloadRequest request) {
        if (request.url() == null || request.url().isBlank()) {
            return DownloadResponse.fail("URL 不能为空");
        }
        if (request.mode() == null) {
            request = new DownloadRequest(request.url(), org.wuyang.ytdlp.model.DownloadMode.BEST_MERGE,
                    request.cookieMode(), request.formatId());
        }
        return downloadService.download(request);
    }

    /** 健康检查端点 */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
