package com.dataocean.module.query.entity.query;

import lombok.Data;

@Data
public class QueryHistoryQuery {

    private Long datasourceId;

    private String status;

    private String keyword;

    private Integer page = 1;

    private Integer pageSize = 20;
}
