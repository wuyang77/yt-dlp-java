package org.wuyang.ytdlp.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 媒体格式数据模型
 *
 * <p>表示 yt-dlp 输出格式列表中的一行，包含格式ID、扩展名、
 * 分辨率、码率、编码器等信息。提供数值解析方法用于排序。</p>
 *
 * @author wuyang
 */
public record Format(
        String id,
        String ext,
        String res,
        String fps,
        String size,
        String tbr,
        String vcodec,
        String acodec,
        String abr,
        boolean audioOnly
) {

    /** 解析视频高度（像素），用于排序和显示 */
    public int height() {
        Matcher m = Pattern.compile("(\\d+)x(\\d+)").matcher(res);
        if (m.find()) return Integer.parseInt(m.group(2));
        Matcher p = Pattern.compile("(\\d{3,5})p").matcher(res);
        return p.find() ? Integer.parseInt(p.group(1)) : 0;
    }

    /** 解析总码率为整数（kbit/s），用于排序 */
    public int tbrValue() {
        return extractInt(tbr);
    }

    /** 解析音频码率为整数（kbit/s），用于排序 */
    public int abrValue() {
        return extractInt(abr);
    }

    /** 解析文件大小为 MiB 数值，用于排序 */
    public double sizeValue() {
        String s = size.toLowerCase();
        Matcher m = Pattern.compile("([\\d.]+)").matcher(s);
        if (!m.find()) return 0;
        double val = Double.parseDouble(m.group(1));
        return s.contains("gib") ? val * 1024 : val;
    }

    private static int extractInt(String text) {
        Matcher m = Pattern.compile("(\\d+)").matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
