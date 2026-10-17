package com.dataocean.module.query.entity.query;

import com.dataocean.common.entity.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class QueryHistoryQuery extends PageQuery {

    private Long datasourceId;

    private String status;

    private String keyword;

    @Override
    protected Map<String, String> sortColumns() {
        return Map.of("createdAt", "created_at", "created_at", "created_at", "status", "status");
    }
}
