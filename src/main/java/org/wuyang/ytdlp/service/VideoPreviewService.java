package org.wuyang.ytdlp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.wuyang.ytdlp.model.PreviewFormat;
import org.wuyang.ytdlp.model.PreviewOptionsResponse;
import org.wuyang.ytdlp.model.PreviewSubtitle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves source formats, tracks, and subtitle data for browser playback. */
@Service
public class VideoPreviewService {

    private static final Logger log = LoggerFactory.getLogger(VideoPreviewService.class);
    private static final Pattern VTT_TIME = Pattern.compile(
            "^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+-->\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}).*$");

    private final YtDlpRunner runner;
    private final ObjectMapper objectMapper;

    public VideoPreviewService(YtDlpRunner runner, ObjectMapper objectMapper) {
        this.runner = runner;
        this.objectMapper = objectMapper;
    }

    public PreviewOptionsResponse getOptions(String url, String cookieMode) throws IOException, InterruptedException {
        JsonNode root = objectMapper.readTree(runner.getPreviewInfoJson(url, cookieMode));
        List<PreviewFormat> formats = new ArrayList<>();
        for (JsonNode format : root.path("formats")) {
            String id = text(format, "format_id");
            if (id.isBlank()) continue;
            String videoCodec = text(format, "vcodec");
            String audioCodec = text(format, "acodec");
            formats.add(new PreviewFormat(id, text(format, "ext"), text(format, "resolution"),
                    text(format, "fps"), text(format, "tbr"), videoCodec, audioCodec,
                    firstText(format, "language", "language_preference"), text(format, "format_note"),
                    isMissingCodec(videoCodec), isMissingCodec(audioCodec)));
        }
        if (formats.isEmpty()) {
            throw new IOException("该视频没有可用于预览的格式");
        }

        Map<String, PreviewSubtitle> subtitles = new LinkedHashMap<>();
        addSubtitles(root.path("subtitles"), false, subtitles);
        addSubtitles(root.path("automatic_captions"), true, subtitles);
        return PreviewOptionsResponse.ok(formats, List.copyOf(subtitles.values()));
    }

    public List<String> resolveStreamUrls(String url, String cookieMode, String videoFormatId, String audioFormatId)
            throws IOException, InterruptedException {
        return runner.resolvePreviewUrls(url, cookieMode, videoFormatId, audioFormatId);
    }

    public String getSubtitles(String url, String cookieMode, String languages)
            throws IOException, InterruptedException {
        validateLanguages(languages);
        Path tempDir = Files.createTempDirectory("yt-dlp-preview-subs-");
        try {
            Path template = tempDir.resolve("subtitle.%(language)s.%(ext)s");
            runner.downloadPreviewSubtitles(url, cookieMode, languages, template);
            List<String> requested = List.of(languages.split(","));
            Map<String, String> vttByLanguage = new LinkedHashMap<>();
            try (var files = Files.walk(tempDir)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vtt"))
                        .toList()) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    for (String language : requested) {
                        if (name.contains("." + language.toLowerCase(Locale.ROOT) + ".")) {
                            vttByLanguage.put(language, Files.readString(file));
                        }
                    }
                }
            }
            if (requested.stream().anyMatch(language -> !vttByLanguage.containsKey(language))) {
                throw new IOException("视频未提供所选字幕轨道；请检查可用字幕列表");
            }
            if (requested.size() == 1) return vttByLanguage.get(requested.get(0));
            return mergeSubtitles(vttByLanguage.get(requested.get(0)), vttByLanguage.get(requested.get(1)));
        } finally {
            deleteTemporaryDirectory(tempDir);
        }
    }

    private static void addSubtitles(JsonNode tracks, boolean automatic, Map<String, PreviewSubtitle> result) {
        if (!tracks.isObject()) return;
        for (var entry : tracks.properties()) {
            String language = entry.getKey();
            String label = language;
            JsonNode trackList = entry.getValue();
            if (trackList.isArray() && !trackList.isEmpty()) {
                label = firstText(trackList.get(0), "name", "language");
                if (label.isBlank()) label = language;
            }
            PreviewSubtitle subtitle = new PreviewSubtitle(language, label, automatic);
            if (!result.containsKey(language) || !automatic) result.put(language, subtitle);
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? "" : value.asString("");
    }

    private static String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = text(node, key);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static boolean isMissingCodec(String codec) {
        return codec.isBlank() || codec.equalsIgnoreCase("none");
    }

    private static void validateLanguages(String languages) throws IOException {
        if (languages == null || !languages.matches("[A-Za-z0-9._-]{1,35}(,[A-Za-z0-9._-]{1,35})?")) {
            throw new IOException("无效的字幕语言");
        }
    }

    private static String mergeSubtitles(String first, String second) {
        List<Cue> firstCues = parseCues(first);
        List<Cue> secondCues = parseCues(second);
        boolean[] matched = new boolean[secondCues.size()];
        List<Cue> merged = new ArrayList<>();
        for (Cue firstCue : firstCues) {
            int bestIndex = -1;
            long bestOverlap = 0;
            for (int i = 0; i < secondCues.size(); i++) {
                if (matched[i]) continue;
                Cue secondCue = secondCues.get(i);
                long overlap = Math.min(firstCue.end(), secondCue.end()) - Math.max(firstCue.start(), secondCue.start());
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    bestIndex = i;
                }
            }
            if (bestIndex >= 0) {
                Cue secondCue = secondCues.get(bestIndex);
                matched[bestIndex] = true;
                merged.add(new Cue(Math.min(firstCue.start(), secondCue.start()),
                        Math.max(firstCue.end(), secondCue.end()), secondCue.text() + "\n" + firstCue.text()));
            } else {
                merged.add(firstCue);
            }
        }
        for (int i = 0; i < secondCues.size(); i++) {
            if (!matched[i]) merged.add(secondCues.get(i));
        }
        merged.sort((left, right) -> Long.compare(left.start(), right.start()));
        StringBuilder vtt = new StringBuilder("WEBVTT\n\n");
        for (Cue cue : merged) {
            vtt.append(toVttTime(cue.start())).append(" --> ").append(toVttTime(cue.end()))
                    .append('\n').append(cue.text()).append("\n\n");
        }
        return vtt.toString();
    }

    private static List<Cue> parseCues(String vtt) {
        List<Cue> cues = new ArrayList<>();
        String[] blocks = vtt.replace("\r\n", "\n").split("\n\n");
        for (String block : blocks) {
            String[] lines = block.split("\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher matcher = VTT_TIME.matcher(lines[i].trim());
                if (!matcher.matches()) continue;
                String text = String.join("\n", java.util.Arrays.copyOfRange(lines, i + 1, lines.length)).trim();
                if (!text.isBlank()) cues.add(new Cue(parseVttTime(matcher.group(1)),
                        parseVttTime(matcher.group(2)), text));
                break;
            }
        }
        return cues;
    }

    private static long parseVttTime(String value) {
        String[] parts = value.split("[:.]");
        return Long.parseLong(parts[0]) * 3_600_000 + Long.parseLong(parts[1]) * 60_000
                + Long.parseLong(parts[2]) * 1_000 + Long.parseLong(parts[3]);
    }

    private static String toVttTime(long millis) {
        long hours = millis / 3_600_000;
        long minutes = millis / 60_000 % 60;
        long seconds = millis / 1_000 % 60;
        long remainder = millis % 1_000;
        return "%02d:%02d:%02d.%03d".formatted(hours, minutes, seconds, remainder);
    }

    private void deleteTemporaryDirectory(Path directory) {
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            log.warn("event=preview.subtitles_temp_cleanup_failed directory={} message={}", directory, e.getMessage());
        }
    }

    private record Cue(long start, long end, String text) {
    }
}
