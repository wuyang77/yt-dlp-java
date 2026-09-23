package org.wuyang.ytdlp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring 配置入口
 *
 * <p>启用 {@link YtDlpProperties} 的属性绑定，
 * 使其在整个应用中可被注入。</p>
 *
 * @author wuyang
 */
@Configuration
@EnableConfigurationProperties(YtDlpProperties.class)
public class YtDlpConfiguration {
}
