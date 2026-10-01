package org.wuyang.ytdlp.controller;

import org.junit.jupiter.api.Test;
import org.wuyang.ytdlp.model.PreviewOptionsResponse;
import org.wuyang.ytdlp.model.PreviewFormat;
import org.wuyang.ytdlp.model.PreviewSubtitle;
import org.wuyang.ytdlp.service.VideoPreviewService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VideoPreviewControllerTest {

    @Test
    void rejectsNonYouTubeUrls() throws Exception {
        VideoPreviewService service = mock(VideoPreviewService.class);
        VideoPreviewController controller = new VideoPreviewController(service);

        assertFalse(controller.preview("https://example.com/video", "FILE", null, null).success());
        verifyNoInteractions(service);
    }

    @Test
    void resolvesYouTubeShortLinks() throws Exception {
        VideoPreviewService service = mock(VideoPreviewService.class);
        VideoPreviewController controller = new VideoPreviewController(service);
        String videoUrl = "https://youtu.be/abcdefghijk";
        String streamUrl = "https://rr1.googlevideo.com/videoplayback?signature=temporary";
        when(service.resolveStreamUrls(videoUrl, "FILE", null, null)).thenReturn(List.of(streamUrl));

        var response = controller.preview(videoUrl, "FILE", null, null);

        assertTrue(response.success());
        assertEquals(streamUrl, response.streamUrl());
    }

    @Test
    void returnsSelectedVideoAndAudioStreams() throws Exception {
        VideoPreviewService service = mock(VideoPreviewService.class);
        VideoPreviewController controller = new VideoPreviewController(service);
        String videoUrl = "https://youtu.be/abcdefghijk";
        String videoStream = "https://rr1.googlevideo.com/videoplayback?video=temporary";
        String audioStream = "https://rr2.googlevideo.com/videoplayback?audio=temporary";
        when(service.resolveStreamUrls(videoUrl, "FILE", "137", "140"))
                .thenReturn(List.of(videoStream, audioStream));

        var response = controller.preview(videoUrl, "FILE", "137", "140");

        assertEquals(videoStream, response.streamUrl());
        assertEquals(audioStream, response.audioUrl());
    }

    @Test
    void exposesFormatAndSubtitleChoices() throws Exception {
        VideoPreviewService service = mock(VideoPreviewService.class);
        VideoPreviewController controller = new VideoPreviewController(service);
        String videoUrl = "https://youtu.be/abcdefghijk";
        var options = PreviewOptionsResponse.ok(
                List.of(new PreviewFormat("137", "mp4", "1920x1080", "30", "5000",
                        "avc1", "none", "", "", false, true)),
                List.of(new PreviewSubtitle("en", "English", true)));
        when(service.getOptions(videoUrl, "FILE")).thenReturn(options);

        var response = controller.options(videoUrl, "FILE");

        assertTrue(response.success());
        assertEquals("137", response.formats().get(0).id());
        assertEquals("en", response.subtitles().get(0).language());
    }
}
