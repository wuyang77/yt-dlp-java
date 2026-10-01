package org.wuyang.ytdlp.model;

import java.util.List;

/** Available video formats, audio tracks, and subtitle languages for preview. */
public record PreviewOptionsResponse(
        boolean success,
        String message,
        List<PreviewFormat> formats,
        List<PreviewSubtitle> subtitles
) {

    public static PreviewOptionsResponse ok(List<PreviewFormat> formats, List<PreviewSubtitle> subtitles) {
        return new PreviewOptionsResponse(true, "预览选项已就绪", formats, subtitles);
    }

    public static PreviewOptionsResponse fail(String message) {
        return new PreviewOptionsResponse(false, message, List.of(), List.of());
    }
}
