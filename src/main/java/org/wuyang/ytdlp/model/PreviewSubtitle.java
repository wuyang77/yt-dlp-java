package org.wuyang.ytdlp.model;

/** A subtitle language available from the source video. */
public record PreviewSubtitle(String language, String label, boolean automatic) {
}
