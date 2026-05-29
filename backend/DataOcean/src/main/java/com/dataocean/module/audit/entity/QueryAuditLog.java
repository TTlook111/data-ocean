package com.dataocean.module.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 查询审计日志实体类
 * <p>
 * 记录每次 NL2SQL 查询的完整生命周期信息，包括用户问题、生成的 SQL、
 * 执行耗时、结果行数、是否成功、是否慢查询等。
 * </p>
 */
@Data
@TableName("query_audit_log")
public class QueryAuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long queryTaskId;
    private Long userId;
    private Long datasourceId;
    private String question;
    private String sqlText;
    private String usedTables;
    private String usedFields;
    private String promptVersions;
    private Integer executionTimeMs;
    private Integer rowCount;
    private Boolean isSuccess;
    private String errorMessage;
    private Boolean isSlow;
    private String userFeedback;
    private LocalDateTime createdAt;
}
