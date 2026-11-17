package com.dataocean.infrastructure.security;

import com.dataocean.common.result.Result;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * 核对本批依赖是否可用，仅披露就绪状态。
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    /**
     * 平台 MySQL 的实际连接。
     */
    private final JdbcTemplate jdbc;

    /**
     * 浏览器会话使用的 Redis。
     */
    private final StringRedisTemplate redis;

    /**
     * 同时验证平台存储和会话存储，失败由统一边界返回。
     */
    @GetMapping("/api/v1/health")
    public Result<HealthVO> health() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        redis.execute(connection -> connection.ping(), true);
        return Result.success(new HealthVO("UP"));
    }

    /**
     * 不包含依赖地址、库名或凭据的最小健康结果。
     */
    @Data
    @AllArgsConstructor
    public static class HealthVO {

        /**
         * 当前依赖就绪状态。
         */
        private String status;
    }
}
