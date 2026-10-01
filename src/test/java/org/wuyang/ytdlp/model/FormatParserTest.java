package org.wuyang.ytdlp.model;

import org.junit.jupiter.api.Test;
import org.wuyang.ytdlp.config.YtDlpProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FormatParserTest {

    @Test
    void parsesAudioAndVideoRows() {
        String output = String.join(System.lineSeparator(),
                "ID  EXT  RESOLUTION  FPS | FILESIZE  TBR  | VCODEC   ACODEC",
                "18   mp4  640x360     30  |  12.3MiB  714  | avc1.4d401e  mp4a.40.2",
                "251  webm audio only      |  120.0KiB  65k  | audio only  mp4a.40.2");

        List<Format> formats = FormatParser.parse(output);

        assertEquals(2, formats.size());
        assertFalse(formats.get(0).audioOnly());
        assertEquals("18", formats.get(0).id());
        assertEquals("640x360", formats.get(0).res());
        assertTrue(formats.get(1).audioOnly());
        assertEquals("mp4a.40.2", formats.get(1).acodec());
    }

    @Test
    void toleratesBlankOrNullInput() {
        assertTrue(FormatParser.parse(null).isEmpty());
        assertTrue(FormatParser.parse("   ").isEmpty());
    }
}

class YtDlpPropertiesTest {

    @Test
    void providesSafeDefaultsAndClientArray() {
        YtDlpProperties props = new YtDlpProperties(null, null, null, null, null, null);

        assertNotNull(props.output());
        assertNotNull(props.bin());
        assertNotNull(props.youtube());
        assertNotNull(props.download());
        assertArrayEquals(new String[] {"web_embedded", "tv"},
                new YtDlpProperties.Youtube(" web_embedded, tv , , ", 0).clientArray());
    }
}
