package org.wuyang.ytdlp.model;

import java.util.ArrayList;
import java.util.List;

/**
 * yt-dlp 格式列表解析器
 *
 * <p>将 yt-dlp -F 命令的文本输出解析为 {@link Format} 对象列表。
 * 自动过滤 storyboard 行和表头行。</p>
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
        List<Format> list = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (!line.contains("|")) continue;
            if (line.toLowerCase().contains("storyboard")) continue;
            if (line.toLowerCase().matches("id .*|.*resolution fps.*")) continue;

            String[] blocks = line.split("\\|");
            if (blocks.length < 3) continue;

            String[] header = blocks[0].trim().split("\\s+");
            if (header.length < 3 || header[0].equalsIgnoreCase("ID")) continue;

            String id = header[0];
            String ext = header[1];
            String res = header[2];
            String fps = header.length > 3 ? header[3].replaceAll("[^0-9.]", "") : "";

            String[] meta = blocks[1].trim().split("\\s+");
            String size = meta.length > 0 ? meta[0] : "N/A";
            String tbr = meta.length > 1 ? meta[1] : "N/A";

            String part3 = blocks[2].trim();
            String[] parts = part3.split("\\s+");
            boolean audioOnly = res.toLowerCase().contains("audio only")
                    || part3.toLowerCase().startsWith("audio only");

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

            list.add(new Format(id, ext, res, fps, size, tbr, vcodec, acodec, abr, audioOnly));
        }
        return list;
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
