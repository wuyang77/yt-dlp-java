package org.wuyang.ytdlp;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.wuyang.ytdlp.service.PotProvider;

/**
 * Spring Boot 应用启动类
 *
 * <p>基于 yt-dlp 的增强版下载服务，集成 PO Token Provider 以获取高清格式。
 * 设计原则：</p>
 * <ol>
 *   <li>Java 只做编排，不替 OS 管 socket</li>
 *   <li>PO Provider 必须真连通才算就绪（/ping 健康检查）</li>
 *   <li>yt-dlp 只认 HTTP Provider，不依赖 pip 插件</li>
 *   <li>路径配置外置到 application.yml，代码中不硬编码</li>
 *   <li>分层架构：config / model / service / controller</li>
 * </ol>
 *
 * @author wuyang
 */
@SpringBootApplication
public class YouTubeDownloaderApplication {

    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");
        SpringApplication.run(YouTubeDownloaderApplication.class, args);
    }

    /**
     * 应用启动时初始化 PO Token Provider
     *
     * <p>确保二进制存在 → 启动进程 → 健康检查 → 就绪标记</p>
     */
    @Bean
    CommandLineRunner initPotProvider(PotProvider potProvider) {
        return args -> {
            potProvider.ensureBinary();
            potProvider.start();
        };
    }
}
