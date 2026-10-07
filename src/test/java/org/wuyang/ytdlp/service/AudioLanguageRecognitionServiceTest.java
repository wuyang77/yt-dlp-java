package org.wuyang.ytdlp.service;

import org.junit.jupiter.api.Test;
import org.wuyang.ytdlp.config.YtDlpProperties;
import org.wuyang.ytdlp.model.Format;
import org.wuyang.ytdlp.model.FormatParser;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AudioLanguageRecognitionServiceTest {

    @Test
    void parsesWhisperCppDetectedLanguage() throws Exception {
        var mapper = JsonMapper.builder().build();

        assertEquals("en", AudioLanguageRecognitionService.detectedLanguage(
                mapper.readTree("{\"result\":{\"language\":\"en\"}}")));
        assertEquals("", AudioLanguageRecognitionService.detectedLanguage(
                mapper.readTree("{\"result\":{\"language\":\"unknown\"}}")));
    }

    @Test
    void reportsMissingLocalEngineAndKeepsMetadataLanguages() throws Exception {
        YtDlpProperties props = new YtDlpProperties(null, null, null, null, null, null,
                new YtDlpProperties.SpeechRecognition("", "", 2, 12));
        AudioLanguageRecognitionService service = new AudioLanguageRecognitionService(
                mock(YtDlpRunner.class), props, JsonMapper.builder().build());
        List<Format> formats = FormatParser.parseJson("""
                {"formats":[
                  {"format_id":"251","ext":"webm","vcodec":"none","acodec":"opus"},
                  {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","language":"ja"}
                ]}
                """, JsonMapper.builder().build());

        try {
            List<Format> recognized = service.recognize("https://youtu.be/abcdefghijk", "NONE", formats);

            assertEquals("not-configured", recognized.get(0).languageStatus());
            assertEquals("ja", recognized.get(1).language());
            assertEquals("metadata", recognized.get(1).languageStatus());
        } finally {
            service.shutdown();
        }
    }
}
