package com.dataocean.module.glossary.entity.query;

import com.dataocean.common.entity.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class TermFieldQuery extends PageQuery {
    private Long datasourceId;
    private String tableName;
    private String keyword;
    @Override
    protected Map<String, String> sortColumns() { return Map.of("name", "c.column_name"); }
}
