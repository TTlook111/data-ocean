package com.dataocean.module.system.entity.dto;

import lombok.Data;

/**
 * 操作日志查询条件 DTO
 */
@Data
public class OperationLogQueryDTO {
    /** 操作人姓名（模糊匹配） */
    private String operatorName;
    /** 操作类型：CREATE/UPDATE/DELETE/QUERY */
    private String operationType;
    /** 是否成功 */
    private Boolean isSuccess;
    /** 开始时间（yyyy-MM-dd HH:mm:ss） */
    private String startTime;
    /** 结束时间（yyyy-MM-dd HH:mm:ss） */
    private String endTime;
    /** IP 地址（模糊匹配） */
    private String ipAddress;
    /** 请求路径（模糊匹配） */
    private String requestPath;
    /** 目标资源 */
    private String targetResource;
    /** 目标资源 ID */
    private String targetId;
    /** 关键词（跨字段模糊：操作人/目标资源/请求路径） */
    private String keyword;
    /** 页码 */
    private Integer page = 1;
    /** 每页大小 */
    private Integer pageSize = 20;
}
