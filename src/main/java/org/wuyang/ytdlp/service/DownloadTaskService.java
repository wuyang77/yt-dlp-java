package org.wuyang.ytdlp.service;

import org.springframework.stereotype.Service;
import org.wuyang.ytdlp.model.DownloadRequest;
import org.wuyang.ytdlp.model.DownloadResponse;
import org.wuyang.ytdlp.model.DownloadTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 管理异步下载任务及其进度 */
@Service
public class DownloadTaskService {

    private static final Pattern PROGRESS = Pattern.compile("\\[download\\]\\s+(\\d+(?:\\.\\d+)?)%");
    private final ConcurrentMap<String, MutableTask> tasks = new ConcurrentHashMap<>();
    private final DownloadService downloadService;

    public DownloadTaskService(DownloadService downloadService) {
        this.downloadService = downloadService;
    }

    public DownloadTask start(DownloadRequest request) {
        String taskId = UUID.randomUUID().toString();
        MutableTask task = new MutableTask(taskId);
        tasks.put(taskId, task);
        CompletableFuture.runAsync(() -> run(task, request));
        return task.snapshot();
    }

    public DownloadTask find(String taskId) {
        MutableTask task = tasks.get(taskId);
        return task == null ? null : task.snapshot();
    }

    private void run(MutableTask task, DownloadRequest request) {
        task.status = "RUNNING";
        task.stage = "正在解析视频格式";
        try {
            DownloadResponse response = downloadService.download(request, line -> task.accept(line));
            task.result = response;
            task.status = response.success() ? "COMPLETED" : "FAILED";
            task.progress = response.success() ? 100 : task.progress;
            task.stage = response.success() ? "下载完成" : "下载失败";
            task.message = response.message();
        } catch (Exception e) {
            task.status = "FAILED";
            task.stage = "下载失败";
            task.message = e.getMessage() == null ? "下载任务异常" : e.getMessage();
        }
    }

    private static final class MutableTask {
        private final String taskId;
        private final List<String> logs = new ArrayList<>();
        private volatile String status = "QUEUED";
        private volatile double progress;
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
                stage = progress >= 100 ? "正在合并为 MP4" : "正在下载媒体流";
                message = line;
            }
        }

        private synchronized DownloadTask snapshot() {
            return new DownloadTask(taskId, status, progress, stage, message, result, List.copyOf(logs));
        }
    }
}