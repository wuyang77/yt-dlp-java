package org.wuyang.ytdlp.service;

import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wuyang.ytdlp.model.DownloadRequest;
import org.wuyang.ytdlp.model.DownloadResponse;
import org.wuyang.ytdlp.model.DownloadTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 管理异步下载任务及其进度 */
@Service
public class DownloadTaskService {

    private static final Logger log = LoggerFactory.getLogger(DownloadTaskService.class);

    private static final Pattern PROGRESS = Pattern.compile("\\[download\\]\\s+(\\d+(?:\\.\\d+)?)%");
    private final ConcurrentMap<String, MutableTask> tasks = new ConcurrentHashMap<>();
    private final DownloadService downloadService;
    private final ExecutorService taskExecutor = Executors.newFixedThreadPool(2);

    public DownloadTaskService(DownloadService downloadService) {
        this.downloadService = downloadService;
    }

    public DownloadTask start(DownloadRequest request) {
        String taskId = UUID.randomUUID().toString();
        MutableTask task = new MutableTask(taskId);
        task.request = request;
        tasks.put(taskId, task);
        log.info("event=task.queued taskId={} url={} mode={}", taskId, request.url(), request.mode());
        CompletableFuture.runAsync(() -> run(task, request), taskExecutor);
        return task.snapshot();
    }

    public DownloadTask find(String taskId) {
        MutableTask task = tasks.get(taskId);
        return task == null ? null : task.snapshot();
    }

    public DownloadTask pause(String taskId) {
        MutableTask task = tasks.get(taskId);
        if (task == null) return null;
        task.control.pause();
        task.status = "PAUSING";
        task.stage = "正在暂停，保留已下载进度";
        return task.snapshot();
    }

    public DownloadTask resume(String taskId) {
        MutableTask task = tasks.get(taskId);
        if (task == null || !"PAUSED".equals(task.status)) return task == null ? null : task.snapshot();
        task.control.resume();
        CompletableFuture.runAsync(() -> run(task, task.request), taskExecutor);
        return task.snapshot();
    }

    @PreDestroy
    public void shutdownTaskExecutor() {
        taskExecutor.shutdownNow();
    }

    private void run(MutableTask task, DownloadRequest request) {
        task.status = "RUNNING";
        task.stage = "正在解析视频格式";
        try {
            DownloadResponse response = downloadService.download(request, line -> task.accept(line), task.control);
            task.result = response;
            task.status = response.success() ? "COMPLETED" : "FAILED";
            task.progress = response.success() ? 100 : task.progress;
            task.stage = response.success() ? "下载完成" : "下载失败";
            task.message = response.message();
            log.info("event=task.finished taskId={} status={} progress={} message={}",
                    task.taskId, task.status, task.progress, task.message);
        } catch (YtDlpRunner.DownloadPausedException e) {
            task.status = "PAUSED";
            task.stage = "已暂停，可继续下载";
            task.message = "已保留当前下载进度";
            log.info("event=task.paused taskId={} progress={}", task.taskId, task.progress);
        } catch (Exception e) {
            task.status = "FAILED";
            task.stage = "下载失败";
            task.message = e.getMessage() == null ? "下载任务异常" : e.getMessage();
            log.error("event=task.failed taskId={} url={} mode={} message={}",
                    task.taskId, request.url(), request.mode(), task.message, e);
        }
    }

    private static final class MutableTask {
        private final String taskId;
        private final DownloadControl control = new DownloadControl();
        private final List<String> logs = new ArrayList<>();
        private DownloadRequest request;
        private volatile String status = "QUEUED";
        private volatile double progress;
        private volatile String speed = "-";
        private volatile String stage = "等待任务开始";
        private volatile String message = "";
        private volatile DownloadResponse result;

        private MutableTask(String taskId) {
            this.taskId = taskId;
        }

        private synchronized void accept(String line) {
            if (!line.isBlank() && logs.size() < 80) logs.add(line);
            Matcher matcher = PROGRESS.matcher(line);
            if (matcher.find()) {
                progress = Double.parseDouble(matcher.group(1));
                int speedIndex = line.indexOf(" at ");
                int speedEnd = speedIndex < 0 ? -1 : line.indexOf("/s", speedIndex);
                if (speedIndex >= 0 && speedEnd > speedIndex) {
                    speed = line.substring(speedIndex + 4, speedEnd + 2).trim();
                }
                stage = progress >= 100 ? "正在合并为 MP4" : "正在下载媒体流";
                message = line;
            }
        }

        private synchronized DownloadTask snapshot() {
            return new DownloadTask(taskId, status, progress, speed, stage, message, result, List.copyOf(logs));
        }
    }
}