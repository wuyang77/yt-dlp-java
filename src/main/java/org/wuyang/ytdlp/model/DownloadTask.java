package org.wuyang.ytdlp.model;

import java.util.List;

/** 前端轮询使用的下载任务状态 */
public record DownloadTask(
        String taskId,
        String status,
        double progress,
        String stage,
        String message,
        DownloadResponse result,
        List<String> logs
) {
}