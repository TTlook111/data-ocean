package com.dataocean.module.datasource.config;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.StringUtils;

/**
 * 在附件操作首次使用时创建并复用 OSS 客户端，随应用关闭释放资源。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NoteOssProperties.class)
class NoteOssConfiguration {

    /**
     * 将 OSS 删除等待隔离到附件调度线程，避免拖延平台其他定时任务。
     */
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler noteAttachmentCleanupScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("note-oss-cleanup-");
        return scheduler;
    }

    /**
     * 根据实际部署地域和域名建立有界的 HTTPS 对象存储连接。
     */
    @Bean(destroyMethod = "close")
    @Lazy
    OSSClient noteOssClient(NoteOssProperties properties) {
        var builder = OSSClient.newBuilder()
                .region(properties.getRegion())
                .credentialsProvider(
                        new StaticCredentialsProvider(properties.getAccessKeyId(), properties.getAccessKeySecret()))
                .useCName(properties.isUseCname())
                .connectTimeout(Duration.ofSeconds(5))
                .readWriteTimeout(Duration.ofSeconds(15))
                .retryMaxAttempts(2);
        if (StringUtils.hasText(properties.getEndpoint())) {
            // 部署可以选择可达的公网、内网或已绑定的自定义域名。
            builder.endpoint(properties.getEndpoint());
        }
        return builder.build();
    }
}
