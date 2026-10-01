package org.wuyang.ytdlp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * yt-dlp-java 业务配置属性
 *
 * <p>通过 Spring Boot {@code @ConfigurationProperties} 机制，
 * 将 application.yml 中 {@code ytdlp} 前缀的配置绑定到 Java 对象。
 * 代码中不硬编码任何环境相关路径，符合可移植性要求。</p>
 *
 * @author wuyang
 */
@ConfigurationProperties(prefix = "ytdlp")
public record YtDlpProperties(
        Output output,
        Bin bin,
        Pot pot,
        Http http,
        Youtube youtube,
        Download download
) {

    public YtDlpProperties {
        if (output == null) {
            output = new Output("", "%(title)s [%(resolution)s].%(ext)s");
        }
        if (bin == null) {
            bin = new Bin("", "", "", "");
        }
        if (pot == null) {
            pot = new Pot("", 49300, "");
        }
        if (http == null) {
            http = new Http("", "", null);
        }
        if (youtube == null) {
            youtube = new Youtube("", 0);
        }
        if (download == null) {
            download = new Download(3, 10, 10, 0, false);
        }
    }

    /**
     * 输出目录配置
     *
     * @param dir       输出目录路径
     * @param template  文件名模板（yt-dlp -o 参数）
     */
    public record Output(String dir, String template) {
    }

    /**
     * 二进制依赖路径
     *
     * @param ytDlp   yt-dlp 可执行文件路径
     * @param ffmpeg  ffmpeg 可执行文件路径
     * @param cookies cookies.txt 路径
     * @param node    Node.js 可执行文件路径
     */
    public record Bin(String ytDlp, String ffmpeg, String cookies, String node) {
    }

    /**
     * PO Token Provider 配置
     *
     * @param path        bgutil-pot 可执行文件路径
     * @param port        PO Provider 监听端口
     * @param downloadUrl 下载地址
     */
    public record Pot(String path, int port, String downloadUrl) {
    }

    /**
     * HTTP 头配置
     *
      * @param userAgent 浏览器标识
      * @param referer   来源地址
      * @param proxy     可选代理地址
     */
     public record Http(String userAgent, String referer, String proxy) {
    }

    /**
     * YouTube 策略配置
     *
        * @param clients     客户端列表（逗号分隔）
        * @param parallelism 格式探测并发数
     */
    public record Youtube(String clients, int parallelism) {

        /** 获取客户端数组 */
        public String[] clientArray() {
            if (clients == null || clients.isBlank()) {
                return new String[0];
            }
            List<String> clientList = new ArrayList<>();
            for (String client : clients.split(",")) {
                String trimmedClient = client.trim();
                if (!trimmedClient.isEmpty()) {
                    clientList.add(trimmedClient);
                }
            }
            return clientList.toArray(new String[0]);
        }
    }

    /**
     * 下载参数配置
     *
        * @param retries         提取和下载重试次数
        * @param fragmentRetries 媒体分片重试次数
        * @param httpRetries     文件访问重试次数
        * @param httpChunkSize   HTTP 分块大小，0 表示不启用
        * @param forceIpv4       是否强制使用 IPv4
     */
    public record Download(int retries, int fragmentRetries, int httpRetries,
                          int httpChunkSize, boolean forceIpv4) {
    }
}
