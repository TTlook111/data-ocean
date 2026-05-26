package com.dataocean.module.audit.entity.dto;

import lombok.Data;

/**
 * 审计日志查询条件 DTO
 */
@Data
public class AuditLogQueryDTO {
    /** 用户ID */
    private Long userId;
    /** 数据源ID */
    private Long datasourceId;
    /** 开始时间 */
    private String startTime;
    /** 结束时间 */
    private String endTime;
    /** 是否成功 */
    private Boolean isSuccess;
    /** 是否慢查询 */
    private Boolean isSlow;
    /** 关键词（模糊搜索 question） */
    private String keyword;
    /** 页码 */
    private Integer pageNo = 1;
    /** 每页大小 */
    private Integer pageSize = 20;
}
