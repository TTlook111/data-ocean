package com.dataocean.module.datasource.entity.query;

import com.dataocean.common.entity.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 数据源分页查询条件。
 * <p>
 * 支持按名称、启停状态、健康状态筛选。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DatasourceQuery extends PageQuery {

    private String name;
    private Integer status;
    private String healthStatus;

    @Override
    protected Map<String, String> sortColumns() {
        return Map.of("id", "id", "name", "name", "status", "status",
                "healthStatus", "health_status", "createdAt", "created_at", "created_at", "created_at");
    }
}
