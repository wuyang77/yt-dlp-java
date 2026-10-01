package org.wuyang.ytdlp.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoPreviewServiceTest {

    @Test
    void parsesFormatsAndManualAndAutomaticSubtitleTracks() throws Exception {
        YtDlpRunner runner = mock(YtDlpRunner.class);
        when(runner.getPreviewInfoJson("https://youtu.be/abcdefghijk", "FILE")).thenReturn("""
                {
                  "formats": [
                    {"format_id":"137","ext":"mp4","resolution":"1920x1080","fps":30,
                     "tbr":5000,"vcodec":"avc1","acodec":"none"},
                    {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2",
                     "language":"en","abr":128}
                  ],
                  "subtitles":{"en":[{"name":"English","ext":"vtt"}]},
                  "automatic_captions":{"zh-Hans":[{"name":"简体中文","ext":"vtt"}]}
                }
                """);
        VideoPreviewService service = new VideoPreviewService(runner, JsonMapper.builder().build());

        var response = service.getOptions("https://youtu.be/abcdefghijk", "FILE");

        assertEquals(2, response.formats().size());
        assertTrue(response.formats().get(0).videoOnly());
        assertTrue(response.formats().get(1).audioOnly());
        assertEquals(2, response.subtitles().size());
        assertTrue(response.subtitles().get(1).automatic());
    }

    @Test
    void combinesEnglishAndSimplifiedChineseWebVttTracks() throws Exception {
        YtDlpRunner runner = mock(YtDlpRunner.class);
        doAnswer(invocation -> {
            Path template = invocation.getArgument(3);
            Path directory = template.getParent();
            Files.writeString(directory.resolve("subtitle.en.vtt"),
                    "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello there\n");
            Files.writeString(directory.resolve("subtitle.zh-Hans.vtt"),
                    "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n你好\n");
            return null;
        }).when(runner).downloadPreviewSubtitles(eq("https://youtu.be/abcdefghijk"), eq("FILE"),
                eq("en,zh-Hans"), any(Path.class));
        VideoPreviewService service = new VideoPreviewService(runner, JsonMapper.builder().build());

        String subtitles = service.getSubtitles("https://youtu.be/abcdefghijk", "FILE", "en,zh-Hans");

        assertTrue(subtitles.startsWith("WEBVTT"));
        assertTrue(subtitles.contains("你好\nHello there"));
    }
}
