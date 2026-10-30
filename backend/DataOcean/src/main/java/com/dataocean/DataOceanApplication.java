package com.dataocean;

import com.dataocean.module.permission.s1.bootstrap.IamS1BootstrapMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * DataOcean 应用主启动类。
 * <p>
 * 启用以下能力：
 * <ul>
 *   <li>非 IAM-SIMPLE-1 bootstrap 模式由独立配置开启定时任务（如元数据采集调度）</li>
 *   <li>{@link EnableAsync} — 开启异步方法支持，proxyTargetClass=true 使用 CGLIB 代理</li>
 * </ul>
 * </p>
 *
 * @author dataocean
 */
@SpringBootApplication
@EnableAsync(proxyTargetClass = true)
@EnableRetry
public class DataOceanApplication {

    /**
     * 应用程序入口方法。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(DataOceanApplication.class);
        // bootstrap 模式 fail-closed：即使参数不完整，也不启动普通 HTTP 入口。
        if (IamS1BootstrapMode.isEnabled(args)) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        application.run(args);
    }

}
