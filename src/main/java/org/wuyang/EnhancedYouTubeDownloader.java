package org.wuyang;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class EnhancedYouTubeDownloader {
    private static final String COOKIES_PATH = "src/main/resources/cookies.txt";
    private static final String OUTPUT_DIR = "F:\\学习资料\\套图\\打碟";
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_SECONDS = 5;
    private static final String YT_DLP_PATH = "src/main/resources/yt-dlp.exe";

    // 新增：依赖检查配置
    private static final String[] YT_DLP_REQUIRED_PATHS = {
            "src/main/resources/yt-dlp.exe",
            "src/main/resources/ffmpeg.exe",
            "src/main/resources/ffprobe.exe"
    };
    private static final String YT_DLP_MIN_VERSION = "2024.12.10"; // 支持最新YouTube加密的版本

    // 反机器人验证配置
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.youtube.com/";

    // 4K格式标识 - 改进匹配
    private static final Set<String> UHD_FORMATS = Set.of("2160", "1440", "1440p", "2160p", "4k", "uhd", "3840x2160", "2560x1440");
    private static final Set<String> HD_FORMATS = Set.of("1080", "1080p", "720", "720p", "hd", "1920x1080", "1280x720");
    // 新增：常见4K编码标识
    private static final Set<String> HEVC_CODECS = Set.of("h265", "hevc", "vp9", "av01", "av1");

    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in)) {
            createOutputDirectory();
            ensureYtDlpExists();
            checkYtDlpDependencies();

            // 检查yt-dlp版本
            try {
                String version = getYtDlpVersion();
                System.out.println("✓ 当前yt-dlp版本: " + version);
                if (isVersionOutdated(version, YT_DLP_MIN_VERSION)) {
                    System.out.println("⚠ 警告: 当前版本可能过旧，4K视频支持可能不完整");
                    System.out.println("  建议更新到版本 " + YT_DLP_MIN_VERSION + " 或更高");
                }
            } catch (Exception e) {
                System.out.println("⚠ 无法获取yt-dlp版本: " + e.getMessage());
            }

            System.out.println("=== 增强版YouTube视频下载器（支持4K/音视频合并/反机器人验证） ===");
            String videoUrl = getVideoUrl(scanner);

            // 获取并解析格式列表（带反机器人验证）
            List<VideoFormat> formats = getAvailableFormatsWithAntiBot(videoUrl);
            if (formats.isEmpty()) {
                System.out.println("⚠ 未找到可用格式，尝试直接下载...");
                directDownload(videoUrl);
            } else {
                // 显示格式列表并特别标注4K格式
                displayFormatsWith4KHighlight(formats);
                String selectedFormat = getFormatChoiceWithAudioRecommendation(scanner, formats);

                // 执行下载（带音视频合并和反机器人验证）
                downloadWithRetryAndAudioMerging(videoUrl, selectedFormat, MAX_RETRIES);
            }

            openExplorer();

        } catch (DownloadException e) {
            System.err.println("下载失败: " + e.getMessage());
            if (e.getCause() != null) {
                e.getCause().printStackTrace();
            }
        } catch (Exception e) {
            System.err.println("发生意外错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 视频格式信息类
     */
    private static class VideoFormat {
        final String formatId;
        final String extension;
        final String resolution;
        final String fps;
        final String fileSize;
        final String bitrate;
        final String codec;
        final String vcodec;
        final String acodec;
        final String note;
        final boolean is4K;
        final boolean isHD;
        final boolean isAudioOnly;
        final boolean isHEVC;
        final boolean isVideoOnly;
        final boolean hasAudio;

        VideoFormat(String formatId, String extension, String resolution, String fps,
                    String fileSize, String bitrate, String codec, String note) {
            this.formatId = formatId;
            this.extension = extension;
            this.resolution = resolution;
            this.fps = fps;
            this.fileSize = fileSize;
            this.bitrate = bitrate;
            this.codec = codec;
            this.note = note != null ? note : "";

            // 解析视频和音频编码
            this.vcodec = parseVideoCodec(codec);
            this.acodec = parseAudioCodec(codec);

            // 检测是否为4K或高清格式
            String resolutionLower = resolution.toLowerCase();
            String codecLower = codec.toLowerCase();
            this.is4K = UHD_FORMATS.stream().anyMatch(resolutionLower::contains) ||
                    resolution.matches(".*(3840|2560).*");
            this.isHD = HD_FORMATS.stream().anyMatch(resolutionLower::contains) || this.is4K;
            this.isAudioOnly = resolutionLower.contains("audio only") ||
                    codecLower.contains("audio only") ||
                    formatId.matches(".*[a-z]\\d+.*") && codecLower.contains("mp4a");
            this.isHEVC = HEVC_CODECS.stream().anyMatch(codecLower::contains);
            this.isVideoOnly = resolutionLower.contains("video only") ||
                    (codecLower.contains("avc") || codecLower.contains("vp9") || codecLower.contains("av01")) &&
                            !codecLower.contains("mp4a");
            this.hasAudio = !isAudioOnly && !isVideoOnly;
        }

        private String parseVideoCodec(String codec) {
            String lower = codec.toLowerCase();
            if (lower.contains("av01")) return "AV1";
            if (lower.contains("vp9")) return "VP9";
            if (lower.contains("h265") || lower.contains("hevc")) return "HEVC";
            if (lower.contains("h264") || lower.contains("avc")) return "H.264";
            if (lower.contains("vp8")) return "VP8";
            return "未知";
        }

        private String parseAudioCodec(String codec) {
            String lower = codec.toLowerCase();
            if (lower.contains("mp4a")) return "AAC";
            if (lower.contains("opus")) return "Opus";
            if (lower.contains("vorbis")) return "Vorbis";
            if (lower.contains("mp3")) return "MP3";
            if (lower.contains("ac-3") || lower.contains("ac3")) return "AC-3";
            if (lower.contains("e-ac-3")) return "E-AC-3";
            return "未知";
        }

        @Override
        public String toString() {
            String prefix = isAudioOnly ? "🎵 " : (is4K ? "🎯 " : (isHD ? "📺 " : "    "));
            if (isHEVC && is4K) prefix = "🔥 " + prefix;

            String codecInfo = codec;
            if (!vcodec.equals("未知") || !acodec.equals("未知")) {
                codecInfo = vcodec;
                if (!acodec.equals("未知") && !isVideoOnly) {
                    codecInfo += "/" + acodec;
                }
                if (!note.isEmpty()) {
                    codecInfo += " (" + note + ")";
                }
            }

            return String.format("%s%-8s %-5s %-12s %-4s | %-10s %-8s | %s",
                    prefix, formatId, extension, resolution, fps,
                    fileSize.isEmpty() ? "N/A" : fileSize,
                    bitrate.isEmpty() ? "N/A" : bitrate,
                    codecInfo);
        }

        public String toSimpleString() {
            return String.format("%s (%s, %s, %s)", formatId, resolution, fileSize, codec);
        }

        public String getQualityInfo() {
            if (is4K) return "4K超清";
            if (isHD) return "高清";
            return "标清";
        }
    }

    /**
     * 获取yt-dlp版本
     */
    private static String getYtDlpVersion() throws DownloadException {
        try {
            List<String> command = new ArrayList<>();
            command.add(YT_DLP_PATH);
            command.add("--version");

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            String output = captureProcessOutput(process, "版本检查");
            process.waitFor();

            if (output != null && !output.trim().isEmpty()) {
                return output.trim().split("\n")[0];
            }
            return "未知";
        } catch (IOException | InterruptedException e) {
            throw new DownloadException("获取yt-dlp版本失败", e);
        }
    }

    /**
     * 检查版本是否过时
     */
    private static boolean isVersionOutdated(String currentVersion, String minVersion) {
        try {
            String[] currentParts = currentVersion.split("\\.");
            String[] minParts = minVersion.split("\\.");

            for (int i = 0; i < Math.min(currentParts.length, minParts.length); i++) {
                int current = Integer.parseInt(currentParts[i]);
                int min = Integer.parseInt(minParts[i]);
                if (current < min) return true;
                if (current > min) return false;
            }
            return false;
        } catch (Exception e) {
            return true; // 解析失败时假定版本过旧
        }
    }

    /**
     * 检查yt-dlp依赖
     */
    private static void checkYtDlpDependencies() throws DownloadException {
        System.out.println("检查yt-dlp依赖...");

        for (String path : YT_DLP_REQUIRED_PATHS) {
            File file = new File(path);
            if (!file.exists()) {
                System.out.println("⚠ 缺少依赖: " + file.getName());
                if (file.getName().equals("ffmpeg.exe") || file.getName().equals("ffprobe.exe")) {
                    System.out.println("   请从 https://github.com/BtbN/FFmpeg-Builds/releases 下载");
                    System.out.println("   或运行: yt-dlp -U 自动更新");
                }
            } else {
                System.out.println("✓ 找到: " + file.getName());
            }
        }

        // 检查Node.js是否可用（用于解密）
        try {
            Process process = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true)
                    .start();
            String output = captureProcessOutput(process, "Node.js检查");
            int exitCode = process.waitFor();
            if (exitCode == 0 && output.contains("v")) {
                System.out.println("✓ Node.js 已安装: " + output.trim());
            } else {
                System.out.println("⚠ Node.js 未安装或不可用");
                System.out.println("  某些YouTube视频需要Node.js进行解密");
                System.out.println("  从 https://nodejs.org/ 下载并安装Node.js");
            }
        } catch (IOException | InterruptedException e) {
            System.out.println("⚠ Node.js 未安装或不可用");
            System.out.println("  某些YouTube视频需要Node.js进行解密");
            System.out.println("  从 https://nodejs.org/ 下载并安装Node.js");
        }
    }

    /**
     * 带反机器人验证的格式获取
     */
    private static List<VideoFormat> getAvailableFormatsWithAntiBot(String videoUrl) throws DownloadException {
        System.out.println("\n正在获取可用格式（带反机器人验证）...");
        System.out.println("这可能需要几秒钟，请耐心等待...");

        List<VideoFormat> allFormats = new ArrayList<>();

        // 尝试多种策略获取格式
        String[][] strategies = {
                {"基础策略", ""},
                {"Android客户端", "--extractor-args", "youtube:player_client=android"},
                {"Web客户端", "--extractor-args", "youtube:player_client=web"},
                {"TV客户端", "--extractor-args", "youtube:player_client=tv"},
                {"iOS客户端", "--extractor-args", "youtube:player_client=ios"}
        };

        for (String[] strategy : strategies) {
            System.out.printf("尝试策略: %s...\n", strategy[0]);

            try {
                List<String> command = buildAntiBotCommand();

                // 添加策略特定参数
                if (strategy.length > 1 && !strategy[1].isEmpty()) {
                    command.add(strategy[1]);
                    command.add(strategy[2]);
                }

                command.add("-F");
                command.add(videoUrl);

                // 添加详细输出参数以便调试
                command.add("-v");

                Process process = new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start();

                String output = captureProcessOutput(process, "格式列表 - " + strategy[0]);

                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    List<VideoFormat> formats = parseFormats(output);
                    if (!formats.isEmpty()) {
                        System.out.printf("✓ 策略 [%s] 成功获取到 %d 个格式\n", strategy[0], formats.size());

                        // 检查是否包含4K格式
                        boolean has4K = formats.stream().anyMatch(f -> f.is4K);
                        boolean hasVideo = formats.stream().anyMatch(f -> !f.isAudioOnly);

                        if (hasVideo && (has4K || allFormats.isEmpty())) {
                            allFormats.addAll(formats);
                        }

                        if (has4K) {
                            System.out.println("🎯 成功找到4K格式！");
                            break; // 找到4K格式，停止尝试其他策略
                        }
                    }
                }

                // 短暂延迟避免请求过快
                TimeUnit.SECONDS.sleep(2);

            } catch (Exception e) {
                System.err.println("策略 " + strategy[0] + " 失败: " + e.getMessage());
                continue;
            }
        }

        if (allFormats.isEmpty()) {
            System.out.println("❌ 所有策略都失败了，尝试最终备用方案...");
            allFormats.addAll(getAvailableFormatsFallback(videoUrl));
        }

        // 去重
        Map<String, VideoFormat> uniqueFormats = new LinkedHashMap<>();
        for (VideoFormat format : allFormats) {
            String key = format.formatId + "-" + format.resolution + "-" + format.codec;
            uniqueFormats.putIfAbsent(key, format);
        }

        List<VideoFormat> result = new ArrayList<>(uniqueFormats.values());
        result.sort((f1, f2) -> {
            // 4K优先，然后按分辨率降序
            if (f1.is4K && !f2.is4K) return -1;
            if (!f1.is4K && f2.is4K) return 1;

            int res1 = extractResolutionValue(f1.resolution);
            int res2 = extractResolutionValue(f2.resolution);
            if (res1 != res2) return Integer.compare(res2, res1);

            // 相同分辨率时，有音频的优先
            if (f1.hasAudio && !f2.hasAudio) return -1;
            if (!f1.hasAudio && f2.hasAudio) return 1;

            return 0;
        });

        return result;
    }

    /**
     * 备用格式获取方法
     */
    private static List<VideoFormat> getAvailableFormatsFallback(String videoUrl) throws DownloadException {
        System.out.println("尝试备用方法获取格式...");

        try {
            // 尝试使用不同的用户代理
            String[] userAgents = {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
            };

            for (String userAgent : userAgents) {
                System.out.println("尝试用户代理: " + userAgent.substring(0, Math.min(50, userAgent.length())) + "...");

                try {
                    List<String> command = new ArrayList<>();
                    command.add(YT_DLP_PATH);
                    command.add("--user-agent");
                    command.add(userAgent);
                    command.add("--referer");
                    command.add(REFERER);
                    command.add("--throttled-rate");
                    command.add("0"); // 不限速
                    command.add("--no-check-certificate");
                    command.add("-F");
                    command.add(videoUrl);

                    Process process = new ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .start();

                    String output = captureProcessOutput(process, "备用格式列表");

                    int exitCode = process.waitFor();
                    if (exitCode == 0) {
                        List<VideoFormat> formats = parseFormats(output);
                        if (!formats.isEmpty()) {
                            System.out.println("✓ 备用方法成功获取到格式");
                            return formats;
                        }
                    }

                    TimeUnit.SECONDS.sleep(3);

                } catch (Exception e) {
                    continue;
                }
            }

            throw new DownloadException("所有备用方法都失败，请检查网络连接和yt-dlp配置");

        } catch (Exception e) {
            Thread.currentThread().interrupt();
            throw new DownloadException("备用格式获取被中断", e);
        }
    }

    /**
     * 构建反机器人验证命令基础
     */
    private static List<String> buildAntiBotCommand() {
        List<String> command = new ArrayList<>();
        command.add(YT_DLP_PATH);

        // 添加反机器人验证参数
        command.add("--user-agent");
        command.add(USER_AGENT);
        command.add("--referer");
        command.add(REFERER);
        command.add("--sleep-requests");
        command.add("3"); // 增加请求间隔
        command.add("--throttled-rate");
        command.add("500K"); // 降低限速
        command.add("--force-ipv4");
        command.add("--no-check-certificate");

        // 添加解密相关参数
        command.add("--extractor-retries");
        command.add("5");
        command.add("--fragment-retries");
        command.add("10");
        command.add("--retries");
        command.add("10");
        command.add("--socket-timeout");
        command.add("30");

        // 尝试启用所有格式
        command.add("--list-formats");

        // 添加cookies（如果存在）
        File cookiesFile = new File(COOKIES_PATH);
        if (cookiesFile.exists()) {
            command.add("--cookies");
            command.add(COOKIES_PATH);
            System.out.println("✓ 使用cookies文件进行验证");
        } else {
            System.out.println("⚠ 未找到cookies文件，使用基础验证");
            System.out.println("  提示: 从浏览器导出cookies.txt可访问更多内容");
        }

        return command;
    }

    /**
     * 捕获进程输出
     */
    private static String captureProcessOutput(Process process, String processName) throws DownloadException {
        StringBuilder output = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
             BufferedReader errorReader = new BufferedReader(
                     new InputStreamReader(process.getErrorStream()))) {

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                // 显示重要信息
                if (line.contains("ERROR") || line.contains("WARNING") ||
                        line.contains("Available formats") || line.contains("格式代码")) {
                    System.out.println(line);
                }
            }

            while ((line = errorReader.readLine()) != null) {
                errors.append(line).append("\n");
                if (line.contains("ERROR") || line.contains("WARNING")) {
                    System.err.println("错误: " + line);
                }
            }

        } catch (IOException e) {
            throw new DownloadException("读取" + processName + "输出时发生错误", e);
        }

        // 检查是否有签名解密错误
        String fullOutput = output.toString() + errors.toString();
        if (fullOutput.contains("Signature solving failed") ||
                fullOutput.contains("n challenge solving failed") ||
                fullOutput.contains("Only images are available for download")) {

            System.err.println("\n⚠⚠⚠ 警告: 检测到签名解密问题！");
            System.err.println("可能的原因和解决方案:");
            System.err.println("1. yt-dlp版本过旧 - 运行: yt-dlp -U 或从 https://github.com/yt-dlp/yt-dlp/releases 下载最新版");
            System.err.println("2. 缺少JavaScript运行时 - 安装Node.js: https://nodejs.org/");
            System.err.println("3. 视频需要登录 - 确保cookies.txt文件有效");
            System.err.println("4. 网络限制 - 尝试使用代理: yt-dlp --proxy 代理地址");
            System.err.println("5. 地区限制 - 视频可能在您所在地区不可用");
        }

        return output.toString();
    }

    /**
     * 改进的格式解析
     */
    private static List<VideoFormat> parseFormats(String output) {
        List<VideoFormat> formats = new ArrayList<>();
        String[] lines = output.split("\n");
        boolean inFormatSection = false;
        boolean foundRealFormats = false;

        for (String line : lines) {
            line = line.trim();

            // 检测是否进入格式列表区域
            if (line.contains("Available formats") || line.contains("ID") ||
                    line.contains("format code") || line.contains("格式代码")) {
                inFormatSection = true;
                continue;
            }

            if (inFormatSection) {
                // 跳过表头和分隔线
                if (line.isEmpty() || line.startsWith("--") || line.matches("^-+$") ||
                        line.startsWith("format code") || line.contains("EXT")) {
                    continue;
                }

                // 跳过故事板图片格式
                if (line.contains("storyboard") || line.contains("mhtml") ||
                        line.contains("images")) {
                    continue;
                }

                // 尝试多种格式行模式
                VideoFormat format = parseFormatLine(line);
                if (format != null) {
                    formats.add(format);
                    foundRealFormats = true;
                }
            }

            // 如果遇到空行且已经找到真实格式，可能表示结束
            if (inFormatSection && line.isEmpty() && foundRealFormats) {
                break;
            }
        }

        return formats;
    }

    /**
     * 解析单行格式信息
     */
    private static VideoFormat parseFormatLine(String line) {
        try {
            // 多种可能的格式模式
            Pattern[] patterns = {
                    // 模式1: ID EXT RESOLUTION FPS | FILESIZE TBR PROTO | VCODEC ACODEC MORE
                    Pattern.compile("^(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+\\|\\s+([^|]+?)\\s+\\|\\s+(.*)$"),
                    // 模式2: ID EXT RESOLUTION FPS |  FILESIZE | TBR | VCODEC
                    Pattern.compile("^(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+\\|\\s+(\\S+)\\s+\\|\\s+(\\S+)\\s+\\|\\s+(.*)$"),
                    // 模式3: ID  EXT  RESOLUTION FPS  CH  FILESIZE  TBR  VCODEC
                    Pattern.compile("^(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(.*)$")
            };

            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    String formatId = matcher.group(1).trim();
                    String extension = matcher.groupCount() > 1 ? matcher.group(2).trim() : "";
                    String resolution = matcher.groupCount() > 2 ? matcher.group(3).trim() : "";
                    String fps = matcher.groupCount() > 3 ? matcher.group(4).trim() : "";

                    // 解析文件大小、比特率、编码
                    String fileSize = "N/A";
                    String bitrate = "N/A";
                    String codec = "";
                    String note = "";

                    if (matcher.groupCount() >= 5) {
                        String remaining = matcher.group(5);
                        for (int i = 6; i <= matcher.groupCount(); i++) {
                            if (matcher.group(i) != null) {
                                remaining += " " + matcher.group(i);
                            }
                        }

                        // 尝试提取文件大小和比特率
                        String[] parts = remaining.split("\\s+");
                        for (String part : parts) {
                            if (part.matches("\\d+(\\.\\d+)?[KMG]iB")) {
                                fileSize = part;
                            } else if (part.matches("\\d+k")) {
                                bitrate = part;
                            } else if (!part.equals("|") && !part.isEmpty()) {
                                if (codec.isEmpty()) {
                                    codec = part;
                                } else {
                                    codec += " " + part;
                                }
                            }
                        }
                    }

                    // 清理数据
                    if (fps.equals("0") || fps.equals("-") || fps.equals("")) {
                        fps = "";
                    }
                    if (resolution.equals("audio")) {
                        resolution = "音频";
                    }

                    return new VideoFormat(formatId, extension, resolution, fps,
                            fileSize, bitrate, codec, note);
                }
            }

            // 如果正则匹配失败，尝试简单解析
            String[] parts = line.split("\\s+");
            if (parts.length >= 7) {
                String formatId = parts[0];
                String extension = parts[1];
                String resolution = parts[2];
                String fps = parts[3];
                String fileSize = parts.length > 4 ? parts[4] : "N/A";
                String bitrate = parts.length > 5 ? parts[5] : "N/A";
                String codec = parts.length > 6 ? parts[6] : "";

                for (int i = 7; i < parts.length; i++) {
                    codec += " " + parts[i];
                }

                return new VideoFormat(formatId, extension, resolution, fps,
                        fileSize, bitrate, codec, "");
            }

        } catch (Exception e) {
            // 忽略解析失败的行
        }

        return null;
    }

    /**
     * 高亮显示4K格式和音频格式
     */
    private static void displayFormatsWith4KHighlight(List<VideoFormat> formats) {
        if (formats.isEmpty()) {
            System.out.println("未找到任何视频或音频格式");
            return;
        }

        System.out.println("\n" + "=".repeat(140));
        System.out.println("可用的视频格式 (🔥🎯 = 4K/HEVC, 🎯 = 4K/UHD, 📺 = HD, 🎵 = 音频, 📹 = 纯视频, 空白 = 标清)");
        System.out.println("=".repeat(140));
        System.out.printf("%-8s %-5s %-12s %-4s %-12s %-8s %s%n",
                "ID", "EXT", "分辨率", "FPS", "大小", "码率", "编码/备注");
        System.out.println("-".repeat(140));

        // 分类格式
        List<VideoFormat> hevc4KFormats = new ArrayList<>();
        List<VideoFormat> uhdFormats = new ArrayList<>();
        List<VideoFormat> hdFormats = new ArrayList<>();
        List<VideoFormat> sdFormats = new ArrayList<>();
        List<VideoFormat> videoOnlyFormats = new ArrayList<>();
        List<VideoFormat> audioFormats = new ArrayList<>();
        List<VideoFormat> audioVideoFormats = new ArrayList<>();

        for (VideoFormat format : formats) {
            if (format.isAudioOnly) {
                audioFormats.add(format);
            } else if (format.isVideoOnly) {
                videoOnlyFormats.add(format);
            } else if (format.hasAudio) {
                audioVideoFormats.add(format);
            } else {
                sdFormats.add(format);
            }
        }

        // 进一步分类
        for (VideoFormat format : audioVideoFormats) {
            if (format.is4K && format.isHEVC) {
                hevc4KFormats.add(format);
            } else if (format.is4K) {
                uhdFormats.add(format);
            } else if (format.isHD) {
                hdFormats.add(format);
            } else {
                sdFormats.add(format);
            }
        }

        for (VideoFormat format : videoOnlyFormats) {
            if (format.is4K && format.isHEVC) {
                hevc4KFormats.add(format);
            } else if (format.is4K) {
                uhdFormats.add(format);
            } else if (format.isHD) {
                hdFormats.add(format);
            } else {
                sdFormats.add(format);
            }
        }

        // 显示HEVC 4K格式
        if (!hevc4KFormats.isEmpty()) {
            System.out.println("\n🔥🎯 HEVC 4K/UHD 格式 (推荐):");
            for (VideoFormat format : hevc4KFormats) {
                System.out.println(format);
            }
        }

        // 显示普通4K格式
        if (!uhdFormats.isEmpty()) {
            System.out.println("\n🎯 4K/UHD 格式:");
            for (VideoFormat format : uhdFormats) {
                System.out.println(format);
            }
        }

        // 显示HD格式
        if (!hdFormats.isEmpty()) {
            System.out.println("\n📺 HD 格式:");
            for (VideoFormat format : hdFormats) {
                System.out.println(format);
            }
        }

        // 显示标清格式
        if (!sdFormats.isEmpty()) {
            System.out.println("\n    标清格式:");
            for (VideoFormat format : sdFormats) {
                System.out.println(format);
            }
        }

        // 显示纯视频格式
        if (!videoOnlyFormats.isEmpty()) {
            System.out.println("\n📹 纯视频格式 (需单独下载音频):");
            for (VideoFormat format : videoOnlyFormats) {
                if (!format.is4K && !format.isHD) {
                    System.out.println(format);
                }
            }
        }

        // 显示音频格式
        if (!audioFormats.isEmpty()) {
            System.out.println("\n🎵 音频格式:");
            for (VideoFormat format : audioFormats) {
                System.out.println(format);
            }
        }

        System.out.println("=".repeat(140));

        // 统计信息
        int total4K = hevc4KFormats.size() + uhdFormats.size();
        System.out.printf("找到 %d 个4K格式 (%d HEVC), %d 个HD格式, %d 个标清格式, %d 个音频格式%n",
                total4K, hevc4KFormats.size(), hdFormats.size(), sdFormats.size(), audioFormats.size());

        if (total4K == 0) {
            System.out.println("\n⚠ 未找到4K格式，可能原因:");
            System.out.println("1. 视频本身不支持4K");
            System.out.println("2. 需要YouTube Premium会员");
            System.out.println("3. 网络或地区限制");
            System.out.println("4. yt-dlp版本过旧，请运行: yt-dlp -U");
            System.out.println("5. 需要Node.js进行解密，请安装Node.js");
        } else {
            System.out.println("\n💡 提示: 🔥 标记的HEVC/H.265格式画质更好，但需要设备支持解码");
        }
    }

    /**
     * 带音频推荐的格式选择
     */
    private static String getFormatChoiceWithAudioRecommendation(Scanner scanner, List<VideoFormat> formats) {
        // 查找最佳音频格式
        List<VideoFormat> audioFormats = formats.stream()
                .filter(f -> f.isAudioOnly)
                .sorted((f1, f2) -> {
                    int quality1 = extractBitrateValue(f1.bitrate);
                    int quality2 = extractBitrateValue(f2.bitrate);
                    return Integer.compare(quality2, quality1);
                })
                .toList();

        // 查找HEVC 4K格式
        List<VideoFormat> hevc4KFormats = formats.stream()
                .filter(f -> f.is4K && f.isHEVC && !f.isAudioOnly)
                .sorted((f1, f2) -> {
                    int res1 = extractResolutionValue(f1.resolution);
                    int res2 = extractResolutionValue(f2.resolution);
                    return Integer.compare(res2, res1);
                })
                .toList();

        // 查找普通4K格式
        List<VideoFormat> uhdFormats = formats.stream()
                .filter(f -> f.is4K && !f.isHEVC && !f.isAudioOnly)
                .sorted((f1, f2) -> {
                    int res1 = extractResolutionValue(f1.resolution);
                    int res2 = extractResolutionValue(f2.resolution);
                    return Integer.compare(res2, res1);
                })
                .toList();

        // 查找最佳HD格式
        List<VideoFormat> hdFormats = formats.stream()
                .filter(f -> f.isHD && !f.is4K && !f.isAudioOnly)
                .sorted((f1, f2) -> {
                    int res1 = extractResolutionValue(f1.resolution);
                    int res2 = extractResolutionValue(f2.resolution);
                    return Integer.compare(res2, res1);
                })
                .toList();

        String bestAudioId = audioFormats.isEmpty() ? "bestaudio" : audioFormats.get(0).formatId;
        String bestVideoFormat = "bestvideo";

        // 如果有HEVC 4K，优先推荐
        if (!hevc4KFormats.isEmpty()) {
            bestVideoFormat = hevc4KFormats.get(0).formatId;
        } else if (!uhdFormats.isEmpty()) {
            bestVideoFormat = uhdFormats.get(0).formatId;
        } else if (!hdFormats.isEmpty()) {
            bestVideoFormat = hdFormats.get(0).formatId;
        }

        while (true) {
            System.out.println("\n🎵 请选择下载选项（支持音视频合并）:");

            // 显示智能推荐组合
            System.out.println("🚀 智能推荐组合:");
            int optionIndex = 1;

            if (!hevc4KFormats.isEmpty()) {
                System.out.printf("  %d. 最佳HEVC 4K视频 + 最佳音频 (最高质量推荐)\n", optionIndex++);
            }
            if (!uhdFormats.isEmpty()) {
                System.out.printf("  %d. 最佳4K视频 + 最佳音频\n", optionIndex++);
            }
            if (!hdFormats.isEmpty()) {
                System.out.printf("  %d. 最佳1080p视频 + 最佳音频\n", optionIndex++);
            }
            System.out.printf("  %d. 自动选择最佳视频+音频组合\n", optionIndex);
            int autoOptionIndex = optionIndex;

            // 显示高级选项
            System.out.println("\n🎯 高级选项:");
            System.out.println("  B. 仅下载最佳视频（无音频）");
            System.out.println("  C. 仅下载最佳音频");
            System.out.println("  D. 自定义视频格式 + 最佳音频");
            System.out.println("  E. 自定义音频格式 + 最佳视频");
            System.out.println("  F. 自定义视频+音频格式组合");

            // 显示快捷命令
            System.out.println("\n💡 快捷命令:");
            System.out.println("  best     - 最佳视频+音频组合");
            System.out.println("  4k       - 最佳4K视频+最佳音频");
            System.out.println("  hevc4k   - 最佳HEVC 4K视频+最佳音频");
            System.out.println("  hd       - 最佳高清视频+最佳音频");
            System.out.println("  audio    - 仅最佳音频");
            System.out.println("  list     - 重新显示格式列表");

            System.out.print("\n请输入您的选择: ");
            String choice = scanner.nextLine().trim().toLowerCase();

            if (choice.isEmpty()) {
                System.out.println("❌ 输入不能为空，请重新输入");
                continue;
            }

            // 处理智能推荐组合
            int choiceNum = -1;
            try {
                choiceNum = Integer.parseInt(choice);
            } catch (NumberFormatException e) {
                // 不是数字，继续处理
            }

            if (choiceNum >= 1 && choiceNum <= optionIndex) {
                int formatIndex = 0;
                if (choiceNum == 1 && !hevc4KFormats.isEmpty()) {
                    return hevc4KFormats.get(0).formatId + "+" + bestAudioId;
                } else if ((choiceNum == 1 && hevc4KFormats.isEmpty()) ||
                        (choiceNum == 2 && !uhdFormats.isEmpty())) {
                    formatIndex = hevc4KFormats.isEmpty() ? 0 : 1;
                    if (!uhdFormats.isEmpty() && formatIndex < uhdFormats.size()) {
                        return uhdFormats.get(formatIndex).formatId + "+" + bestAudioId;
                    }
                } else if ((choiceNum == 2 && hevc4KFormats.isEmpty() && uhdFormats.isEmpty()) ||
                        (choiceNum == 3 && !hdFormats.isEmpty())) {
                    formatIndex = 0;
                    if (choiceNum == 2 && hevc4KFormats.isEmpty() && uhdFormats.isEmpty()) {
                        formatIndex = 0; // 直接使用第一个HD格式
                    }
                    if (!hdFormats.isEmpty() && formatIndex < hdFormats.size()) {
                        return hdFormats.get(formatIndex).formatId + "+" + bestAudioId;
                    }
                } else if (choiceNum == autoOptionIndex) {
                    return getAutoFormatChoiceWithAudio(hevc4KFormats, uhdFormats, hdFormats, formats, bestAudioId);
                }
            }

            // 处理字母选项
            switch (choice) {
                case "a":
                case "auto":
                    return getAutoFormatChoiceWithAudio(hevc4KFormats, uhdFormats, hdFormats, formats, bestAudioId);
                case "b":
                    return "bestvideo";
                case "c":
                case "audio":
                    return "bestaudio";
                case "d":
                    String videoFormat = getCustomVideoFormat(scanner, formats);
                    if (videoFormat != null) {
                        return videoFormat + "+" + bestAudioId;
                    }
                    continue;
                case "e":
                    String audioFormat = getCustomAudioFormat(scanner, formats);
                    if (audioFormat != null) {
                        return bestVideoFormat + "+" + audioFormat;
                    }
                    continue;
                case "f":
                    return getCustomVideoAudioCombination(scanner, formats);
                case "list":
                    displayFormatsWith4KHighlight(formats);
                    continue;
            }

            // 处理快捷命令
            switch (choice) {
                case "best":
                    return "bestvideo+" + bestAudioId + "/best";
                case "4k":
                    return "bestvideo[height<=2160]+" + bestAudioId + "/best[height<=2160]";
                case "hevc4k":
                    return "bestvideo[height<=2160][vcodec*=h265]+" + bestAudioId + "/best[height<=2160]";
                case "hd":
                    return "bestvideo[height<=1080]+" + bestAudioId + "/best[height<=1080]";
                default:
                    // 检查是否是直接格式ID
                    if (isValidFormatId(choice, formats)) {
                        VideoFormat selectedFormat = formats.stream()
                                .filter(f -> f.formatId.equals(choice))
                                .findFirst()
                                .orElse(null);

                        if (selectedFormat != null) {
                            if (selectedFormat.isAudioOnly) {
                                return choice; // 直接返回音频格式ID
                            } else {
                                return choice + "+" + bestAudioId; // 视频格式加上最佳音频
                            }
                        }
                    } else {
                        System.out.println("❌ 无效的选择或格式ID，请重新输入");
                        continue;
                    }
            }

            return choice;
        }
    }

    /**
     * 自定义视频格式选择
     */
    private static String getCustomVideoFormat(Scanner scanner, List<VideoFormat> formats) {
        while (true) {
            System.out.print("请输入视频格式ID (或输入 'back' 返回): ");
            String formatId = scanner.nextLine().trim();

            if (formatId.equalsIgnoreCase("back")) {
                return null;
            }

            if (isValidFormatId(formatId, formats)) {
                VideoFormat format = formats.stream()
                        .filter(f -> f.formatId.equals(formatId))
                        .findFirst()
                        .orElse(null);

                if (format != null && !format.isAudioOnly) {
                    return formatId;
                } else {
                    System.out.println("❌ 请输入有效的视频格式ID（非音频格式）");
                }
            } else {
                System.out.println("❌ 无效的格式ID，请重新输入");
            }
        }
    }

    /**
     * 自定义音频格式选择
     */
    private static String getCustomAudioFormat(Scanner scanner, List<VideoFormat> formats) {
        List<VideoFormat> audioFormats = formats.stream()
                .filter(f -> f.isAudioOnly)
                .toList();

        if (audioFormats.isEmpty()) {
            System.out.println("未找到可用的音频格式");
            return null;
        }

        System.out.println("\n可用的音频格式:");
        for (VideoFormat format : audioFormats) {
            System.out.println(format);
        }

        while (true) {
            System.out.print("请输入音频格式ID (或输入 'back' 返回): ");
            String formatId = scanner.nextLine().trim();

            if (formatId.equalsIgnoreCase("back")) {
                return null;
            }

            if (isValidFormatId(formatId, formats)) {
                VideoFormat format = formats.stream()
                        .filter(f -> f.formatId.equals(formatId))
                        .findFirst()
                        .orElse(null);

                if (format != null && format.isAudioOnly) {
                    return formatId;
                } else {
                    System.out.println("❌ 请输入有效的音频格式ID");
                }
            } else {
                System.out.println("❌ 无效的格式ID，请重新输入");
            }
        }
    }

    /**
     * 自定义音视频组合
     */
    private static String getCustomVideoAudioCombination(Scanner scanner, List<VideoFormat> formats) {
        System.out.println("\n自定义音视频组合:");
        String videoFormat = getCustomVideoFormat(scanner, formats);
        if (videoFormat == null) {
            return null;
        }

        String audioFormat = getCustomAudioFormat(scanner, formats);
        if (audioFormat == null) {
            return null;
        }

        return videoFormat + "+" + audioFormat;
    }

    /**
     * 自动选择最佳格式（带音频）
     */
    private static String getAutoFormatChoiceWithAudio(List<VideoFormat> hevc4KFormats,
                                                       List<VideoFormat> uhdFormats,
                                                       List<VideoFormat> hdFormats,
                                                       List<VideoFormat> allFormats,
                                                       String bestAudioId) {
        if (!hevc4KFormats.isEmpty()) {
            System.out.println("✅ 自动选择: 最佳HEVC 4K视频 + 最佳音频");
            return hevc4KFormats.get(0).formatId + "+" + bestAudioId;
        } else if (!uhdFormats.isEmpty()) {
            System.out.println("✅ 自动选择: 最佳4K视频 + 最佳音频");
            return uhdFormats.get(0).formatId + "+" + bestAudioId;
        } else if (!hdFormats.isEmpty()) {
            System.out.println("✅ 自动选择: 最佳1080p视频 + 最佳音频");
            return hdFormats.get(0).formatId + "+" + bestAudioId;
        } else {
            System.out.println("✅ 自动选择: 最佳视频 + 最佳音频");
            return "bestvideo+" + bestAudioId + "/best";
        }
    }

    /**
     * 提取比特率数值
     */
    private static int extractBitrateValue(String bitrate) {
        if (bitrate == null || bitrate.equals("N/A") || bitrate.isEmpty()) return 0;

        try {
            if (bitrate.contains("k")) {
                return Integer.parseInt(bitrate.replace("k", "").trim());
            }
            if (bitrate.contains("K")) {
                return Integer.parseInt(bitrate.replace("K", "").trim());
            }
            if (bitrate.contains("m")) {
                return (int)(Float.parseFloat(bitrate.replace("m", "").trim()) * 1000);
            }
            if (bitrate.contains("M")) {
                return (int)(Float.parseFloat(bitrate.replace("M", "").trim()) * 1000);
            }
        } catch (NumberFormatException e) {
            // 忽略解析错误
        }
        return 0;
    }

    /**
     * 提取分辨率数值
     */
    private static int extractResolutionValue(String resolution) {
        try {
            Pattern pattern = Pattern.compile("(\\d+)");
            Matcher matcher = pattern.matcher(resolution);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return 0;
    }

    /**
     * 检查格式ID是否有效
     */
    private static boolean isValidFormatId(String formatId, List<VideoFormat> formats) {
        return formats.stream().anyMatch(f -> f.formatId.equals(formatId));
    }

    /**
     * 直接下载（当无法解析格式时使用）
     */
    private static void directDownload(String videoUrl) throws DownloadException {
        System.out.println("正在使用默认设置下载（最佳视频+音频）...");
        System.out.println("如果下载失败，请尝试更新yt-dlp: yt-dlp -U");
        downloadVideoWithAudioMerging(videoUrl, "bestvideo+bestaudio/best");
    }

    /**
     * 带重试机制和音视频合并的下载
     */
    private static void downloadWithRetryAndAudioMerging(String videoUrl, String format, int maxRetries)
            throws DownloadException {

        DownloadException lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                System.out.printf("\n🎵 尝试下载和合并音视频 (第%d次/%d次)...\n", attempt, maxRetries);
                downloadVideoWithAudioMerging(videoUrl, format);
                System.out.println("✅ 下载和合并完成！");
                return;

            } catch (DownloadException e) {
                lastException = e;
                System.err.printf("第%d次下载失败: %s\n", attempt, e.getMessage());

                if (attempt < maxRetries) {
                    System.out.printf("等待%d秒后重试...\n", RETRY_DELAY_SECONDS);
                    try {
                        TimeUnit.SECONDS.sleep(RETRY_DELAY_SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new DownloadException("下载被中断", ie);
                    }
                }
            }
        }

        throw new DownloadException(String.format(
                "下载失败，已重试%d次。最后错误: %s", maxRetries,
                lastException != null ? lastException.getMessage() : "未知错误"), lastException);
    }

    /**
     * 带音视频合并的视频下载
     */
    private static void downloadVideoWithAudioMerging(String videoUrl, String format) throws DownloadException {
        System.out.println("下载配置: " + format);
        System.out.println("开始下载，请稍候...");

        try {
            List<String> command = buildAntiBotCommand();

            // 设置格式选择
            command.add("-f");
            command.add(format);

            // 设置合并输出格式
            command.add("--merge-output-format");
            command.add("mp4");

            // 音频质量设置
            command.add("--audio-quality");
            command.add("0"); // 最佳质量
            command.add("--audio-format");
            command.add("best");

            // 输出模板
            String outputTemplate = OUTPUT_DIR + File.separator + "%(title)s [%(resolution)s][%(fps)s].%(ext)s";
            command.add("-o");
            command.add(outputTemplate);

            // 合并后删除临时文件
            command.add("--no-keep-video");

            // 添加元数据
            command.add("--embed-thumbnail");
            command.add("--add-metadata");

            // 添加字幕
            command.add("--embed-subs");
            command.add("--sub-langs");
            command.add("all");

            // 其他参数
            command.add("--no-overwrites");
            command.add("--console-title");
            command.add("--progress");

            // 添加下载重试参数
            command.add("--retries");
            command.add("10");
            command.add("--fragment-retries");
            command.add("10");
            command.add("--skip-unavailable-fragments");

            // 添加网络优化参数
            command.add("--buffer-size");
            command.add("16K");
            command.add("--http-chunk-size");
            command.add("1M");
            command.add("--no-part");

            command.add(videoUrl);

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            printProcessOutput(process, "下载和合并进度");

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new DownloadException("下载过程失败，退出码: " + exitCode);
            }

        } catch (IOException | InterruptedException e) {
            throw new DownloadException("下载过程中发生错误", e);
        }
    }

    /**
     * 确保yt-dlp可执行文件存在且有效
     */
    private static void ensureYtDlpExists() throws DownloadException {
        File ytDlpFile = new File(YT_DLP_PATH);

        if (!ytDlpFile.exists()) {
            System.out.println("❌ yt-dlp.exe 未找到，正在检查备用位置...");

            // 检查当前目录
            File currentDir = new File("yt-dlp.exe");
            if (currentDir.exists()) {
                System.out.println("✓ 在当前目录找到 yt-dlp.exe");
                return;
            }

            // 检查系统PATH
            String path = System.getenv("PATH");
            if (path != null) {
                String[] paths = path.split(File.pathSeparator);
                for (String p : paths) {
                    File testFile = new File(p, "yt-dlp.exe");
                    if (testFile.exists()) {
                        System.out.println("✓ 在PATH中找到 yt-dlp.exe: " + testFile.getAbsolutePath());
                        return;
                    }
                }
            }

            throw new DownloadException("yt-dlp.exe 未找到，请执行以下操作之一:\n" +
                    "1. 从 https://github.com/yt-dlp/yt-dlp/releases/latest 下载最新版 yt-dlp.exe\n" +
                    "2. 将 yt-dlp.exe 放置在: " + YT_DLP_PATH + "\n" +
                    "3. 或将 yt-dlp.exe 添加到系统PATH环境变量");
        }

        // 检查文件大小是否合理
        long fileSize = ytDlpFile.length();
        if (fileSize < 4_000_000) { // 至少4MB
            System.out.println("⚠ yt-dlp.exe 文件大小异常: " + fileSize + " 字节 (可能不完整)");
            System.out.println("  建议从 https://github.com/yt-dlp/yt-dlp/releases 重新下载");
        } else {
            System.out.println("✓ 使用用户提供的yt-dlp.exe: " + YT_DLP_PATH + " (" + (fileSize / 1024 / 1024) + " MB)");
        }

        // 尝试设置可执行权限
        if (!ytDlpFile.setExecutable(true)) {
            System.out.println("⚠ 无法设置可执行权限，但将继续尝试使用");
        }
    }

    /**
     * 创建输出目录
     */
    private static void createOutputDirectory() throws DownloadException {
        File outputDir = new File(OUTPUT_DIR);
        if (!outputDir.exists()) {
            System.out.println("创建输出目录: " + OUTPUT_DIR);
            if (!outputDir.mkdirs()) {
                throw new DownloadException("无法创建输出目录: " + OUTPUT_DIR);
            }
        }
        System.out.println("输出目录: " + outputDir.getAbsolutePath());

        // 检查磁盘空间
        long freeSpace = outputDir.getFreeSpace();
        if (freeSpace < 2L * 1024 * 1024 * 1024) { // 2GB
            System.out.println("⚠ 警告: 磁盘空间不足，剩余 " + (freeSpace / 1024 / 1024 / 1024) + " GB");
            System.out.println("  下载4K视频可能需要更多空间");
        }
    }

    /**
     * 获取视频URL
     */
    private static String getVideoUrl(Scanner scanner) {
        String videoUrl = "";
        while (videoUrl.isEmpty()) {
            System.out.print("请输入YouTube视频URL: ");
            videoUrl = scanner.nextLine().trim();

            if (videoUrl.isEmpty()) {
                System.out.println("错误：输入不能为空，请重新输入视频地址！");
            } else if (!videoUrl.contains("youtube.com") && !videoUrl.contains("youtu.be")) {
                System.out.println("⚠ 警告: 这可能不是YouTube URL，继续吗？(y/n): ");
                String confirm = scanner.nextLine().trim().toLowerCase();
                if (!confirm.equals("y") && !confirm.equals("yes")) {
                    videoUrl = "";
                }
            }
        }
        return videoUrl;
    }

    /**
     * 打印进程输出
     */
    private static void printProcessOutput(Process process, String processName) throws DownloadException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 显示下载进度
                if (line.contains("[download]") || line.contains("%") ||
                        line.contains("[info]") || line.contains("[Merger]")) {
                    System.out.print("\r" + line);
                } else if (line.contains("ERROR") || line.contains("WARNING")) {
                    System.err.println("\n" + line);
                } else {
                    System.out.println(line);
                }
            }
            System.out.println();
        } catch (IOException e) {
            throw new DownloadException("读取" + processName + "输出时发生错误", e);
        }
    }

    /**
     * 打开资源管理器
     */
    private static void openExplorer() {
        try {
            File outputDir = new File(OUTPUT_DIR);
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                Runtime.getRuntime().exec(new String[]{"explorer.exe", outputDir.getAbsolutePath()});
            } else if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", outputDir.getAbsolutePath()});
            } else {
                Runtime.getRuntime().exec(new String[]{"xdg-open", outputDir.getAbsolutePath()});
            }
            System.out.println("✓ 已打开下载目录: " + outputDir.getAbsolutePath());
        } catch (IOException e) {
            System.out.println("⚠ 无法打开资源管理器: " + e.getMessage());
        }
    }

    /**
     * 自定义下载异常类
     */
    private static class DownloadException extends Exception {
        public DownloadException(String message) {
            super(message);
        }

        public DownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}