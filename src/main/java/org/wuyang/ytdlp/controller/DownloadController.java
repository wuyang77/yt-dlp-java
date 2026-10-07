package org.wuyang.ytdlp.controller;

import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.wuyang.ytdlp.model.DownloadRequest;
import org.wuyang.ytdlp.model.DownloadResponse;
import org.wuyang.ytdlp.model.DownloadTask;
import org.wuyang.ytdlp.model.FormatListResponse;
import org.wuyang.ytdlp.config.YtDlpProperties;
import org.wuyang.ytdlp.service.DownloadService;
import org.wuyang.ytdlp.service.DownloadTaskService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

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

    private static final Logger log = LoggerFactory.getLogger(DownloadController.class);

    private final DownloadService downloadService;
    private final DownloadTaskService taskService;
    private final YtDlpProperties properties;

    public DownloadController(DownloadService downloadService, DownloadTaskService taskService,
                              YtDlpProperties properties) {
        this.downloadService = downloadService;
        this.taskService = taskService;
        this.properties = properties;
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
        String cleanUrl = normalizeUrl(url);
        if (cleanUrl == null) {
            log.warn("event=http.formats_rejected reason=invalid_url");
            return FormatListResponse.fail("请输入有效的视频链接");
        }
        log.info("event=http.formats url={} cookieMode={}", cleanUrl, cookieMode);
        return downloadService.listFormats(cleanUrl, cookieMode);
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
        if (request == null) {
            log.warn("event=http.download_rejected reason=empty_request");
            return DownloadResponse.fail("请求内容不能为空");
        }
        String cleanUrl = normalizeUrl(request.url());
        if (cleanUrl == null) {
            log.warn("event=http.download_rejected reason=invalid_url");
            return DownloadResponse.fail("请输入有效的视频链接");
        }
        request = new DownloadRequest(cleanUrl, request.mode(), request.cookieMode(), request.formatId());
        log.info("event=http.download url={} mode={} cookieMode={}", cleanUrl, request.mode(), request.cookieMode());
        if (request.mode() == null) {
            request = new DownloadRequest(request.url(), org.wuyang.ytdlp.model.DownloadMode.BEST_MERGE,
                    request.cookieMode(), request.formatId());
        }
        return downloadService.download(request);
    }

    /** 创建异步下载任务，供 Web 前端显示实时进度 */
    @PostMapping("/download/async")
    public DownloadTask startDownload(@RequestBody DownloadRequest request) {
        if (request == null) {
            log.warn("event=http.download_async_rejected reason=empty_request");
            throw new IllegalArgumentException("请求内容不能为空");
        }
        String cleanUrl = normalizeUrl(request.url());
        if (cleanUrl == null) {
            log.warn("event=http.download_async_rejected reason=invalid_url");
            throw new IllegalArgumentException("请输入有效的视频链接");
        }
        request = new DownloadRequest(cleanUrl, request.mode(), request.cookieMode(), request.formatId());
        log.info("event=http.download_async url={} mode={} cookieMode={}",
                cleanUrl, request.mode(), request.cookieMode());
        if (request.mode() == null) {
            request = new DownloadRequest(request.url(), org.wuyang.ytdlp.model.DownloadMode.BEST_MERGE,
                    request.cookieMode(), request.formatId());
        }
        return taskService.start(request);
    }

    /** 查询异步下载任务 */
    @GetMapping("/download/tasks/{taskId}")
    public DownloadTask task(@PathVariable String taskId) {
        DownloadTask task = taskService.find(taskId);
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
        return task;
    }

    @PostMapping("/download/tasks/{taskId}/pause")
    public DownloadTask pauseTask(@PathVariable String taskId) {
        DownloadTask task = taskService.pause(taskId);
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
        return task;
    }

    @PostMapping("/download/tasks/{taskId}/resume")
    public DownloadTask resumeTask(@PathVariable String taskId) {
        DownloadTask task = taskService.resume(taskId);
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
        return task;
    }

    /** 在运行服务的 Windows 计算机上打开文件或其所在目录 */
    @PostMapping("/files/open")
    public Map<String, Object> openFile(@RequestParam String path,
                                        @RequestParam(defaultValue = "false") boolean directory) {
        try {
            Path outputDir = Path.of(properties.output().dir()).toAbsolutePath().normalize();
            Path target = Path.of(path).toAbsolutePath().normalize();
            if (!target.startsWith(outputDir) || !Files.exists(target)) {
                return Map.of("success", false, "message", "文件不在下载目录内或不存在");
            }
            if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                return Map.of("success", false, "message", "打开文件功能目前仅支持 Windows");
            }
            if (directory) {
                Path directoryPath = Files.isDirectory(target) ? target : target.getParent();
                if (directoryPath == null || !Files.isDirectory(directoryPath)) {
                    return Map.of("success", false, "message", "文件所在目录不存在");
                }
                new ProcessBuilder("explorer.exe", directoryPath.toString()).start();
            } else {
                if (!Files.isRegularFile(target)) {
                    return Map.of("success", false, "message", "目标不是有效文件");
                }
                openFileInExplorer(target);
            }
            return Map.of("success", true, "message",
                    directory ? "已打开所在目录" : "已在资源管理器中定位文件");
        } catch (Exception e) {
            log.error("event=file.open_failed path={} message={}", path, e.getMessage(), e);
            return Map.of("success", false, "message", "无法打开: " + e.getMessage());
        }
    }

    private static void openFileInExplorer(Path target) throws IOException {
        String escapedPath = target.toString().replace("'", "''");
        String script = """
                $ErrorActionPreference = 'Stop'
                $target = [IO.Path]::GetFullPath('%s')
                $directory = [IO.Path]::GetDirectoryName($target)
                $shell = New-Object -ComObject Shell.Application
                $shell.Explore($directory)
                Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class ExplorerWindow { [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow); [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd); [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow(); }'
                $deadline = [DateTime]::UtcNow.AddSeconds(10)
                while ([DateTime]::UtcNow -lt $deadline) {
                    $matchedWindow = $null
                    foreach ($window in $shell.Windows()) {
                        try {
                            $windowPath = [IO.Path]::GetFullPath([string]$window.Document.Folder.Self.Path)
                            if ([string]::Equals($windowPath, $directory, [StringComparison]::OrdinalIgnoreCase)) {
                                $matchedWindow = $window
                                break
                            }
                        } catch { }
                    }
                    if ($null -ne $matchedWindow) {
                        $item = $matchedWindow.Document.Folder.ParseName([IO.Path]::GetFileName($target))
                        if ($null -eq $item) { throw 'Explorer could not find the downloaded file.' }
                        $matchedWindow.Document.SelectItem($item, 1 -bor 4 -bor 8 -bor 16)
                        $handle = [IntPtr]$matchedWindow.HWND
                        [void][ExplorerWindow]::ShowWindowAsync($handle, 9)
                        if (-not [ExplorerWindow]::SetForegroundWindow($handle) -and [ExplorerWindow]::GetForegroundWindow() -ne $handle) {
                            throw 'Explorer selected the file, but Windows did not allow its window to come to the foreground.'
                        }
                        exit 0
                    }
                    Start-Sleep -Milliseconds 150
                }
                throw 'Timed out waiting for the Explorer window.'
                """ .formatted(escapedPath);
        String encodedScript = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile",
                "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encodedScript)
                .redirectErrorStream(true)
                .start();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Timed out while selecting the downloaded file in Explorer");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while selecting the downloaded file in Explorer", e);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IllegalStateException(output.isEmpty()
                    ? "Explorer could not select the downloaded file" : output);
        }
    }

    /** 健康检查端点 */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    private static String normalizeUrl(String value) {
        if (value == null || value.isBlank() || value.contains("\r") || value.contains("\n")) {
            return null;
        }
        String candidate = value.trim();
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme();
            if (uri.getHost() == null && !candidate.startsWith("http://") && !candidate.startsWith("https://")) {
                return null;
            }
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    ? candidate : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
