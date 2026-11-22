package com.dataocean;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动 Java 业务应用，排除未使用的默认内存账号认证。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@MapperScan({
    "com.dataocean.module.iam.mapper",
    "com.dataocean.module.audit.mapper",
    "com.dataocean.module.access.mapper",
    "com.dataocean.module.datasource.mapper",
    "com.dataocean.module.governance.mapper",
    "com.dataocean.module.model.mapper",
    "com.dataocean.module.knowledge.mapper",
    "com.dataocean.module.query.mapper"
})
public class DataOceanApplication {

    /**
     * 启动正式 Java 应用，业务模块由统一配置装配。
     */
    public static void main(String[] args) {
        SpringApplication.run(DataOceanApplication.class, args);
    }
}
