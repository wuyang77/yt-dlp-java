package org.wuyang.ytdlp.model;

/** Browser playback URL returned by the preview endpoint. */
public record PreviewResponse(
        boolean success,
        String message,
        String streamUrl,
        String audioUrl
) {

    public static PreviewResponse ok(String streamUrl) {
        return ok(streamUrl, null);
    }

    public static PreviewResponse ok(String streamUrl, String audioUrl) {
        return new PreviewResponse(true, "预览视频已就绪", streamUrl, audioUrl);
    }

    public static PreviewResponse fail(String message) {
        return new PreviewResponse(false, message, null, null);
    }
}
