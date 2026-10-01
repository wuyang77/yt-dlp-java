package org.wuyang.ytdlp.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.wuyang.ytdlp.model.PreviewOptionsResponse;
import org.wuyang.ytdlp.model.PreviewResponse;
import org.wuyang.ytdlp.service.VideoPreviewService;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** HTTP API for resolving YouTube videos into browser-playable preview streams. */
@RestController
@RequestMapping("/api/preview")
public class VideoPreviewController {

    private static final Logger log = LoggerFactory.getLogger(VideoPreviewController.class);

    private final VideoPreviewService previewService;

    public VideoPreviewController(VideoPreviewService previewService) {
        this.previewService = previewService;
    }

    @GetMapping("/options")
    public PreviewOptionsResponse options(
            @RequestParam String url,
            @RequestParam(defaultValue = "FILE") String cookieMode) {
        String cleanUrl = normalizeYouTubeUrl(url);
        if (cleanUrl == null) {
            return PreviewOptionsResponse.fail("请输入有效的 YouTube 视频链接");
        }
        try {
            return previewService.getOptions(cleanUrl, cookieMode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("event=preview.options_interrupted");
            return PreviewOptionsResponse.fail("读取预览选项已中断");
        } catch (IOException e) {
            log.warn("event=preview.options_failed message={}", e.getMessage());
            return PreviewOptionsResponse.fail(e.getMessage());
        }
    }

    @GetMapping
    public PreviewResponse preview(
            @RequestParam String url,
            @RequestParam(defaultValue = "FILE") String cookieMode,
            @RequestParam(required = false) String formatId,
            @RequestParam(required = false) String audioFormatId) {
        String cleanUrl = normalizeYouTubeUrl(url);
        if (cleanUrl == null) {
            return PreviewResponse.fail("请输入有效的 YouTube 视频链接");
        }

        try {
            var streams = previewService.resolveStreamUrls(cleanUrl, cookieMode, formatId, audioFormatId);
            return PreviewResponse.ok(streams.get(0), streams.size() > 1 ? streams.get(1) : null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("event=preview.interrupted");
            return PreviewResponse.fail("视频预览已中断");
        } catch (IOException e) {
            log.warn("event=preview.failed message={}", e.getMessage());
            return PreviewResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/subtitles")
    public ResponseEntity<String> subtitles(
            @RequestParam String url,
            @RequestParam String lang,
            @RequestParam(defaultValue = "FILE") String cookieMode) {
        String cleanUrl = normalizeYouTubeUrl(url);
        if (cleanUrl == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入有效的 YouTube 视频链接");
        }
        try {
            String vtt = previewService.getSubtitles(cleanUrl, cookieMode, lang);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .contentType(MediaType.parseMediaType("text/vtt;charset=UTF-8"))
                    .body(vtt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "读取字幕已中断", e);
        } catch (IOException e) {
            log.warn("event=preview.subtitles_failed lang={} message={}", lang, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    static String normalizeYouTubeUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 2048
                || value.contains("\r") || value.contains("\n")) {
            return null;
        }
        String candidate = value.trim();
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || host == null || uri.getUserInfo() != null) {
                return null;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            boolean youtubeHost = normalizedHost.equals("youtu.be")
                    || normalizedHost.equals("youtube.com")
                    || normalizedHost.endsWith(".youtube.com");
            return youtubeHost ? candidate : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
