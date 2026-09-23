package org.wuyang.ytdlp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
     * @param userAgent User-Agent
     * @param referer   Referer
     */
    public record Http(String userAgent, String referer) {
    }

    /**
     * YouTube 策略配置
     *
     * @param clients 客户端列表（逗号分隔）
     */
    public record Youtube(String clients) {

        /** 获取客户端数组 */
        public String[] clientArray() {
            return clients.split(",");
        }
    }

    /**
     * 下载参数配置
     *
     * @param retries 重试次数
     */
    public record Download(int retries) {
    }
}
