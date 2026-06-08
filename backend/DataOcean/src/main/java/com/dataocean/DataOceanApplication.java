package com.dataocean;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DataOcean 应用主启动类。
 * <p>
 * 启用以下能力：
 * <ul>
 *   <li>{@link EnableScheduling} — 开启定时任务支持（如元数据采集调度）</li>
 *   <li>{@link EnableAsync} — 开启异步方法支持，proxyTargetClass=true 使用 CGLIB 代理</li>
 * </ul>
 * </p>
 *
 * @author dataocean
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync(proxyTargetClass = true)
@EnableRetry
public class DataOceanApplication {

    /**
     * 应用程序入口方法。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(DataOceanApplication.class, args);
    }

}
