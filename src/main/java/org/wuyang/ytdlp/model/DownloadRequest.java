package org.wuyang.ytdlp.model;

/**
 * 下载请求 DTO
 *
 * @param url       YouTube 视频 URL
 * @param mode      下载方式
 * @param cookieMode Cookie 来源（FILE / FIREFOX / NONE）
 * @param formatId  自定义格式ID（mode 为 CUSTOM 时使用）
 *
 * @author wuyang
 */
public record DownloadRequest(
        String url,
        DownloadMode mode,
        String cookieMode,
        String formatId
) {
}
