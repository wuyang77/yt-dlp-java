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

    // 反机器人验证配置
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.youtube.com/";

    // 4K格式标识
    private static final Set<String> UHD_FORMATS = Set.of("2160", "1440", "1440p", "2160p", "4k", "uhd");
    private static final Set<String> HD_FORMATS = Set.of("1080", "1080p", "720", "720p", "hd");

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        try {
            createOutputDirectory();
            ensureYtDlpExists();

            System.out.println("=== 增强版YouTube视频下载器（支持4K/反机器人验证） ===");
            String videoUrl = getVideoUrl(scanner);

            // 获取并解析格式列表（带反机器人验证）
            List<VideoFormat> formats = getAvailableFormatsWithAntiBot(videoUrl);
            if (formats.isEmpty()) {
                System.out.println("⚠ 未找到可用格式，尝试直接下载...");
                directDownload(videoUrl);
            } else {
                // 显示格式列表并特别标注4K格式
                displayFormatsWith4KHighlight(formats);
                String selectedFormat = getFormatChoiceWith4KRecommendation(scanner, formats);

                // 执行下载（带反机器人验证）
                downloadWithRetryAndAntiBot(videoUrl, selectedFormat, MAX_RETRIES);
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
        } finally {
            scanner.close();
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
        final boolean is4K;
        final boolean isHD;

        VideoFormat(String formatId, String extension, String resolution, String fps,
                    String fileSize, String bitrate, String codec) {
            this.formatId = formatId;
            this.extension = extension;
            this.resolution = resolution;
            this.fps = fps;
            this.fileSize = fileSize;
            this.bitrate = bitrate;
            this.codec = codec;

            // 检测是否为4K或高清格式
            String resolutionLower = resolution.toLowerCase();
            this.is4K = UHD_FORMATS.stream().anyMatch(resolutionLower::contains);
            this.isHD = HD_FORMATS.stream().anyMatch(resolutionLower::contains) || this.is4K;
        }

        @Override
        public String toString() {
            String prefix = is4K ? "🎯 " : (isHD ? "📺 " : "    ");
            return String.format("%s%-8s %-4s %-10s %-4s | %-10s %-6s | %s",
                    prefix, formatId, extension, resolution, fps, fileSize, bitrate, codec);
        }

        public String toSimpleString() {
            return String.format("%s (%s, %s, %s)", formatId, resolution, fileSize, codec);
        }
    }

    /**
     * 带反机器人验证的格式获取
     */
    private static List<VideoFormat> getAvailableFormatsWithAntiBot(String videoUrl) throws DownloadException {
        System.out.println("\n正在获取可用格式（带反机器人验证）...");

        try {
            List<String> command = buildAntiBotCommand();
            command.add("-F");
            command.add(videoUrl);

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            String output = captureProcessOutput(process, "格式列表");

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.out.println("⚠ 标准方法失败，尝试备用方法...");
                return getAvailableFormatsFallback(videoUrl);
            }

            return parseFormats(output);

        } catch (IOException | InterruptedException e) {
            throw new DownloadException("获取格式列表时发生错误", e);
        }
    }

    /**
     * 备用格式获取方法
     */
    private static List<VideoFormat> getAvailableFormatsFallback(String videoUrl) throws DownloadException {
        try {
            // 尝试不同的客户端配置
            String[][] clientOptions = {
                    {"--extractor-args", "youtube:player_client=android"},
                    {"--extractor-args", "youtube:player_client=web"},
                    {"--extractor-args", "youtube:player_client=tv"},
                    {"--extractor-args", "youtube:player_client=ios"}
            };

            for (String[] clientOption : clientOptions) {
                System.out.println("尝试客户端: " + clientOption[1]);

                List<String> command = buildAntiBotCommand();
                command.add(clientOption[0]);
                command.add(clientOption[1]);
                command.add("-F");
                command.add(videoUrl);

                Process process = new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start();

                String output = captureProcessOutput(process, "格式列表（备用）");

                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    List<VideoFormat> formats = parseFormats(output);
                    if (!formats.isEmpty()) {
                        System.out.println("✓ 使用客户端 " + clientOption[1] + " 成功");
                        return formats;
                    }
                }

                // 短暂延迟避免请求过快
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            throw new DownloadException("所有备用方法都失败");

        } catch (IOException | InterruptedException e) {
            throw new DownloadException("备用格式获取失败", e);
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
        command.add("2");
        command.add("--throttled-rate");
        command.add("100K");
        command.add("--force-ipv4");

        // 添加cookies（如果存在）
        File cookiesFile = new File(COOKIES_PATH);
        if (cookiesFile.exists()) {
            command.add("--cookies");
            command.add(COOKIES_PATH);
            System.out.println("✓ 使用cookies文件进行验证");
        } else {
            System.out.println("⚠ 未找到cookies文件，使用基础验证");
        }

        return command;
    }

    /**
     * 捕获进程输出
     */
    private static String captureProcessOutput(Process process, String processName) throws DownloadException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                System.out.println(line);
            }
        } catch (IOException e) {
            throw new DownloadException("读取" + processName + "输出时发生错误", e);
        }
        return output.toString();
    }

    /**
     * 解析格式列表
     */
    private static List<VideoFormat> parseFormats(String output) {
        List<VideoFormat> formats = new ArrayList<>();
        String[] lines = output.split("\n");
        boolean inFormatSection = false;

        for (String line : lines) {
            line = line.trim();

            // 检测是否进入格式列表区域
            if (line.contains("Available formats") || line.contains("格式代码") ||
                    (line.startsWith("ID") && line.contains("EXT"))) {
                inFormatSection = true;
                continue;
            }

            if (inFormatSection && line.length() > 10 && !line.startsWith("--")) {
                // 尝试解析格式行
                try {
                    // 使用正则表达式匹配格式行
                    Pattern pattern = Pattern.compile(
                            "^(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+\\|\\s+([^|]+)\\|\\s+(.*)$"
                    );
                    Matcher matcher = pattern.matcher(line);

                    if (matcher.find()) {
                        String formatId = matcher.group(1).trim();
                        String extension = matcher.group(2).trim();
                        String resolution = matcher.group(3).trim();
                        String fps = matcher.group(4).trim();
                        String sizeBitrate = matcher.group(5).trim();
                        String codec = matcher.group(6).trim();

                        // 解析文件大小和比特率
                        String[] sizeParts = sizeBitrate.split("\\s+");
                        String fileSize = sizeParts.length > 0 ? sizeParts[0] : "N/A";
                        String bitrate = sizeParts.length > 1 ? sizeParts[1] : "N/A";

                        // 清理数据
                        if (fps.equals("0") || fps.equals("-")) fps = "";
                        if (resolution.equals("audio")) resolution = "音频";

                        formats.add(new VideoFormat(formatId, extension, resolution, fps,
                                fileSize, bitrate, codec));
                    }
                } catch (Exception e) {
                    // 忽略解析失败的行
                }
            }

            // 如果遇到空行且已经在格式区域，可能表示结束
            if (inFormatSection && line.isEmpty()) {
                inFormatSection = false;
            }
        }

        return formats;
    }

    /**
     * 高亮显示4K格式
     */
    private static void displayFormatsWith4KHighlight(List<VideoFormat> formats) {
        System.out.println("\n" + "=".repeat(100));
        System.out.println("可用的视频格式 (🎯 = 4K/UHD, 📺 = HD, 空白 = 标清)");
        System.out.println("=".repeat(100));
        System.out.printf("%-8s %-4s %-12s %-4s %-12s %-8s %s%n",
                "ID", "EXT", "分辨率", "FPS", "大小", "码率", "编码");
        System.out.println("-".repeat(100));

        // 先显示4K格式
        List<VideoFormat> uhdFormats = new ArrayList<>();
        List<VideoFormat> hdFormats = new ArrayList<>();
        List<VideoFormat> sdFormats = new ArrayList<>();

        for (VideoFormat format : formats) {
            if (format.is4K) {
                uhdFormats.add(format);
            } else if (format.isHD) {
                hdFormats.add(format);
            } else {
                sdFormats.add(format);
            }
        }

        // 显示4K格式
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
            System.out.println("\n   标清格式:");
            for (VideoFormat format : sdFormats) {
                System.out.println(format);
            }
        }

        System.out.println("=".repeat(100));

        // 统计信息
        System.out.printf("找到 %d 个4K格式, %d 个HD格式, %d 个标清格式%n",
                uhdFormats.size(), hdFormats.size(), sdFormats.size());

        if (uhdFormats.isEmpty()) {
            System.out.println("⚠ 未找到4K格式，视频可能不支持4K或需要特殊访问权限");
        }
    }

    /**
     * 带4K推荐的格式选择
     */
    private static String getFormatChoiceWith4KRecommendation(Scanner scanner, List<VideoFormat> formats) {
        // 查找4K格式
        List<VideoFormat> uhdFormats = formats.stream()
                .filter(f -> f.is4K)
                .sorted((f1, f2) -> {
                    // 按分辨率排序（2160 > 1440）
                    int res1 = extractResolutionValue(f1.resolution);
                    int res2 = extractResolutionValue(f2.resolution);
                    return Integer.compare(res2, res1);
                })
                .toList();

        // 查找最佳HD格式
        List<VideoFormat> hdFormats = formats.stream()
                .filter(f -> f.isHD && !f.is4K)
                .sorted((f1, f2) -> {
                    int res1 = extractResolutionValue(f1.resolution);
                    int res2 = extractResolutionValue(f2.resolution);
                    return Integer.compare(res2, res1);
                })
                .toList();

        while (true) {
            System.out.println("\n请选择下载选项:");

            // 显示4K推荐（如果有）
            if (!uhdFormats.isEmpty()) {
                System.out.println("🎯 4K选项:");
                for (int i = 0; i < Math.min(uhdFormats.size(), 3); i++) {
                    VideoFormat format = uhdFormats.get(i);
                    System.out.printf("  %d. 格式 %s%n", i + 1, format.toSimpleString());
                }
            }

            // 显示HD选项
            if (!hdFormats.isEmpty()) {
                System.out.println("📺 HD选项:");
                int startIndex = uhdFormats.isEmpty() ? 1 : uhdFormats.size() + 1;
                for (int i = 0; i < Math.min(hdFormats.size(), 3); i++) {
                    VideoFormat format = hdFormats.get(i);
                    System.out.printf("  %d. 格式 %s%n", startIndex + i, format.toSimpleString());
                }
            }

            System.out.println("\n快捷选项:");
            System.out.println("  best    - 下载最佳质量");
            System.out.println("  best4k  - 下载最佳4K质量（如果可用）");
            System.out.println("  besthd  - 下载最佳HD质量");
            System.out.println("  worst   - 下载最差质量");
            System.out.println("  auto    - 自动选择最佳可用格式");
            System.out.println("  或直接输入格式ID (如: 301-1)");

            System.out.print("\n请输入您的选择: ");
            String choice = scanner.nextLine().trim().toLowerCase();

            if (choice.isEmpty()) {
                System.out.println("❌ 输入不能为空，请重新输入");
                continue;
            }

            // 处理快捷选项
            switch (choice) {
                case "best":
                    return "best[height<=2160]";
                case "best4k":
                    if (!uhdFormats.isEmpty()) {
                        return uhdFormats.get(0).formatId;
                    } else {
                        System.out.println("❌ 未找到4K格式，请选择其他选项");
                        continue;
                    }
                case "besthd":
                    if (!hdFormats.isEmpty()) {
                        return hdFormats.get(0).formatId;
                    } else if (!formats.isEmpty()) {
                        return formats.get(0).formatId;
                    } else {
                        return "best[height<=1080]";
                    }
                case "worst":
                    return "worst";
                case "auto":
                    return getAutoFormatChoice(uhdFormats, hdFormats, formats);
                default:
                    // 检查是否是数字选择
                    try {
                        int index = Integer.parseInt(choice);
                        if (index >= 1 && index <= uhdFormats.size()) {
                            return uhdFormats.get(index - 1).formatId;
                        } else if (index > uhdFormats.size() &&
                                index <= uhdFormats.size() + hdFormats.size()) {
                            return hdFormats.get(index - uhdFormats.size() - 1).formatId;
                        } else {
                            System.out.println("❌ 数字超出范围，请重新选择");
                            continue;
                        }
                    } catch (NumberFormatException e) {
                        // 不是数字，可能是直接格式ID
                        if (isValidFormatId(choice, formats)) {
                            return choice;
                        } else {
                            System.out.println("❌ 无效的格式ID或选择，请重新输入");
                            continue;
                        }
                    }
            }
        }
    }

    /**
     * 自动选择最佳格式
     */
    private static String getAutoFormatChoice(List<VideoFormat> uhdFormats,
                                              List<VideoFormat> hdFormats,
                                              List<VideoFormat> allFormats) {
        if (!uhdFormats.isEmpty()) {
            System.out.println("✅ 自动选择: " + uhdFormats.get(0).toSimpleString());
            return uhdFormats.get(0).formatId;
        } else if (!hdFormats.isEmpty()) {
            System.out.println("✅ 自动选择: " + hdFormats.get(0).toSimpleString());
            return hdFormats.get(0).formatId;
        } else if (!allFormats.isEmpty()) {
            System.out.println("✅ 自动选择: " + allFormats.get(0).toSimpleString());
            return allFormats.get(0).formatId;
        } else {
            System.out.println("✅ 自动选择: 最佳可用质量");
            return "best";
        }
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
        System.out.println("正在使用默认设置下载...");
        downloadVideoWithAntiBot(videoUrl, "best[height<=2160]");
    }

    /**
     * 带重试机制和反机器人验证的下载
     */
    private static void downloadWithRetryAndAntiBot(String videoUrl, String format, int maxRetries)
            throws DownloadException {

        DownloadException lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                System.out.printf("\n尝试下载 (第%d次/%d次)...\n", attempt, maxRetries);
                downloadVideoWithAntiBot(videoUrl, format);
                System.out.println("✓ 下载完成！");
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
     * 带反机器人验证的视频下载
     */
    private static void downloadVideoWithAntiBot(String videoUrl, String format) throws DownloadException {
        System.out.println("下载配置: " + format);

        try {
            List<String> command = buildAntiBotCommand();
            command.add("-f");
            command.add(format);
            command.add("--merge-output-format");
            command.add("mp4");
            command.add("-o");
            command.add(OUTPUT_DIR + File.separator + "%(title)s.%(ext)s");
            command.add("--no-overwrites");
            command.add("--console-title");

            // 添加重试参数
            command.add("--retries");
            command.add("10");
            command.add("--fragment-retries");
            command.add("10");
            command.add("--skip-unavailable-fragments");

            command.add(videoUrl);

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            printProcessOutput(process, "下载进度");

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
            throw new DownloadException("yt-dlp.exe 未找到，请确保文件已放置在: " + YT_DLP_PATH);
        }

        // 检查文件大小是否合理
        long fileSize = ytDlpFile.length();
        if (fileSize < 4_500_000) { // 至少4.5MB
            throw new DownloadException("yt-dlp.exe 文件大小异常: " + fileSize + " 字节 (预期至少4.5MB)");
        }

        // 尝试设置可执行权限
        if (!ytDlpFile.setExecutable(true)) {
            System.out.println("⚠ 无法设置可执行权限，但将继续尝试使用");
        }

        System.out.println("✓ 使用用户提供的yt-dlp.exe: " + YT_DLP_PATH);
    }

    /**
     * 创建输出目录
     */
    private static void createOutputDirectory() throws DownloadException {
        File outputDir = new File(OUTPUT_DIR);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new DownloadException("无法创建输出目录: " + OUTPUT_DIR);
        }
        System.out.println("输出目录: " + outputDir.getAbsolutePath());
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
                if (line.contains("[download]") || line.contains("%")) {
                    System.out.print("\r" + line);
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