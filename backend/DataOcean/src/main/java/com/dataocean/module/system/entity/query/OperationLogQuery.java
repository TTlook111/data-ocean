package com.dataocean.module.system.entity.query;

import com.dataocean.common.entity.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 操作日志查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OperationLogQuery extends PageQuery {
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

    @Override
    protected Map<String, String> sortColumns() {
        return Map.of("id", "id", "createdAt", "created_at", "created_at", "created_at",
                "operatorName", "operator_name", "operationType", "operation_type");
    }
}
