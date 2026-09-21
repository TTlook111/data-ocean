package com.dataocean.common.health.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 重置 SQL 连接池的确认参数。
 *
 * <p>重置会中断目标数据源正在执行的查询，因此要求调用方显式确认并说明原因；
 * 这份原因会进审计日志，便于事后追溯「谁在什么时候为什么重置了哪个源」。</p>
 */
@Data
public class SqlPoolResetDTO {

    /** 显式确认标记：缺失或非 true 一律拒绝，避免误触。 */
    private Boolean confirmed;

    /** 重置原因，进入审计记录。 */
    @NotBlank(message = "必须说明重置原因")
    private String reason;
}
