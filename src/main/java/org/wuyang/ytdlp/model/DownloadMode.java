package org.wuyang.ytdlp.model;

/**
 * 下载方式枚举
 *
 * @author wuyang
 */
public enum DownloadMode {

    /** 最高码率合并：最佳视频 + 最佳音频 → MP4 */
    BEST_MERGE,
    /** 仅视频 */
    VIDEO_ONLY,
    /** 仅音频 */
    AUDIO_ONLY,
    /** 指定格式ID */
    CUSTOM
}
