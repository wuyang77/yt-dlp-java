package org.wuyang.ytdlp.model;

import java.util.List;

/**
 * 下载响应 DTO
 *
 * @param success     是否成功
 * @param message     结果消息
 * @param filePath    输出文件路径（成功时）
 * @param fileName    输出文件名（成功时）
 * @param bestVideo   最佳视频格式信息（成功时）
 * @param bestAudio   最佳音频格式信息（成功时）
 * @param diagnostics 诊断信息列表
 *
 * @author wuyang
 */
public record DownloadResponse(
        boolean success,
        String message,
        String filePath,
        String fileName,
        String bestVideo,
        String bestAudio,
        List<String> diagnostics
) {

    /** 构造失败响应 */
    public static DownloadResponse fail(String message) {
        return new DownloadResponse(false, message, null, null, null, null, List.of());
    }

    /** 构造成功响应 */
    public static DownloadResponse ok(String filePath, String fileName, String bestVideo, String bestAudio,
                                      List<String> diagnostics) {
        return new DownloadResponse(true, "下载完成", filePath, fileName, bestVideo, bestAudio, diagnostics);
    }
}
