package org.wuyang.ytdlp.model;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * yt-dlp 格式列表解析器
 *
 * <p>将 yt-dlp 结构化元数据或格式列表文本解析为 {@link Format} 对象列表。</p>
 *
 * @author wuyang
 */
public final class FormatParser {

    private FormatParser() {
    }

    /**
     * 解析 yt-dlp -F 输出为格式列表
     *
     * @param text yt-dlp 的原始文本输出
     * @return 解析出的格式列表（可能为空）
     */
    public static List<Format> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<Format> list = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || !line.contains("|")) continue;
            if (line.toLowerCase().contains("storyboard")) continue;
            if (line.toLowerCase().matches("id .*|.*resolution fps.*")) continue;

            String[] blocks = line.split("\\|", 3);
            if (blocks.length < 3) continue;

            String[] header = blocks[0].trim().split("\\s+");
            if (header.length < 3 || header[0].equalsIgnoreCase("ID")) continue;

            String id = header[0];
            String ext = header.length > 1 ? header[1] : "";
            String res = header.length > 2 ? header[2] : "";
            String fps = header.length > 3 ? header[3].replaceAll("[^0-9.]", "") : "";

            String[] meta = blocks[1].trim().split("\\s+");
            String size = meta.length > 0 ? meta[0] : "N/A";
            String tbr = meta.length > 1 ? meta[1] : "N/A";

            String part3 = blocks[2].trim();
            String[] parts = part3.split("\\s+");
            String fullText = (res + " " + part3).toLowerCase();
            boolean audioOnly = fullText.contains("audio only") || fullText.contains("audio-only");

            String vcodec = "";
            String acodec = "";
            String abr = "";

            if (audioOnly) {
                int start = findAudioOnlyIndex(parts);
                if (start < parts.length) {
                    acodec = parts[start];
                    if (start + 1 < parts.length) abr = parts[start + 1];
                }
            } else {
                vcodec = parts.length > 0 ? parts[0] : "";
            }

            if (res.isBlank()) {
                res = audioOnly ? "audio only" : "unknown";
            }

            list.add(new Format(id, ext, res, fps, size, tbr, vcodec, acodec, abr, "",
                    audioOnly ? "unavailable" : "not-applicable", audioOnly));
        }
        return list;
    }

    /** Parse structured yt-dlp metadata so fields such as the audio language are preserved. */
    public static List<Format> parseJson(String json, ObjectMapper objectMapper) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        JsonNode formats = root.path("formats");
        if (!formats.isArray()) return List.of();

        List<Format> result = new ArrayList<>();
        for (JsonNode item : formats) {
            String id = text(item, "format_id");
            if (id.isBlank()) continue;

            String vcodec = text(item, "vcodec");
            String acodec = text(item, "acodec");
            boolean audioOnly = isMissingCodec(vcodec) && !isMissingCodec(acodec);
            String resolution = text(item, "resolution");
            if (resolution.isBlank()) {
                String width = text(item, "width");
                String height = text(item, "height");
                resolution = !width.isBlank() && !height.isBlank()
                        ? width + "x" + height : audioOnly ? "audio only" : "unknown";
            }

            String language = text(item, "language");
            boolean hasLanguage = !language.isBlank() && !language.equalsIgnoreCase("und");
            result.add(new Format(id, text(item, "ext"), resolution, text(item, "fps"),
                    size(item), bitrate(item, "tbr"), vcodec, acodec, bitrate(item, "abr"),
                    hasLanguage ? language : "",
                    audioOnly ? hasLanguage ? "metadata" : "unavailable" : "not-applicable",
                    audioOnly));
        }
        return result;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? "" : value.asString(value.toString());
    }

    private static String size(JsonNode item) {
        JsonNode bytes = item.path("filesize");
        if (bytes.isMissingNode() || bytes.isNull() || bytes.asLong(0) <= 0) {
            bytes = item.path("filesize_approx");
        }
        long value = bytes.asLong(0);
        return value > 0 ? String.format(Locale.ROOT, "%.1fMiB", value / 1048576.0) : "N/A";
    }

    private static String bitrate(JsonNode item, String key) {
        String value = text(item, key);
        if (value.isBlank()) return "N/A";
        try {
            return String.format(Locale.ROOT, "%.0fk", Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static boolean isMissingCodec(String codec) {
        return codec.isBlank() || codec.equalsIgnoreCase("none");
    }

    /** 在 tokens 中定位 "audio only" 后的起始索引 */
    private static int findAudioOnlyIndex(String[] parts) {
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equalsIgnoreCase("audio") && i + 1 < parts.length
                    && parts[i + 1].equalsIgnoreCase("only")) {
                return i + 2;
            }
        }
        return 0;
    }
}
