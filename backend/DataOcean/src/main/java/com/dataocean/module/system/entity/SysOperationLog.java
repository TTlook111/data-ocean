package com.dataocean.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 管理操作日志实体类
 */
@Data
@TableName("sys_operation_log")
public class SysOperationLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long operatorId;
    private String operatorName;
    private String operationType;
    private String targetResource;
    private String targetId;
    private String requestMethod;
    private String requestPath;
    private String requestParams;
    private Integer executionMs;
    private Boolean isSuccess;
    private String errorMessage;
    private String ipAddress;
    private LocalDateTime createdAt;
}
