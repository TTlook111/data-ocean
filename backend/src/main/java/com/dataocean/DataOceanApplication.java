package com.dataocean;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan({"com.dataocean.module.iam.mapper", "com.dataocean.module.audit.mapper"})
public class DataOceanApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataOceanApplication.class, args);
    }
}
