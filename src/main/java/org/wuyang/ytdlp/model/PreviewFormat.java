package org.wuyang.ytdlp.model;

/** Sanitized format metadata for the preview player. */
public record PreviewFormat(
        String id,
        String ext,
        String resolution,
        String fps,
        String bitrate,
        String videoCodec,
        String audioCodec,
        String language,
        String note,
        boolean audioOnly,
        boolean videoOnly
) {
}
