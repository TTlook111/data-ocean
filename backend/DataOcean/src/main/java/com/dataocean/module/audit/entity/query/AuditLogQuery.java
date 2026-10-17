package com.dataocean.module.audit.entity.query;

import com.dataocean.common.entity.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 审计日志查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AuditLogQuery extends PageQuery {
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

    @Override
    protected Map<String, String> sortColumns() {
        return Map.of("id", "id", "createdAt", "created_at", "created_at", "created_at",
                "executionTimeMs", "execution_time_ms");
    }
}
