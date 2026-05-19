package com.dataocean.module.datasource.entity.query;

import lombok.Data;

@Data
public class DatasourceQueryRequest {

    private String name;
    private Integer status;
    private String healthStatus;
    private Long pageNum = 1L;
    private Long page = 1L;
    private Long pageSize = 20L;

    public Long resolvedPage() {
        Long current = page != null ? page : pageNum;
        return current == null || current < 1 ? 1L : current;
    }

    public Long resolvedPageSize() {
        if (pageSize == null || pageSize < 1) {
            return 20L;
        }
        return Math.min(pageSize, 100L);
    }
}
