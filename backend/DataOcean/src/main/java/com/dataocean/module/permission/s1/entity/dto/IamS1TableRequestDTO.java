package com.dataocean.module.permission.s1.entity.dto;

import com.dataocean.module.permission.s1.enums.IamS1ColumnUsage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 一张表在本次查询中的全部字段引用。 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class IamS1TableRequestDTO {
    private String tableName;
    private Set<String> referencedColumns = new LinkedHashSet<>();
    private Map<String, Set<IamS1ColumnUsage>> columnUsages = new LinkedHashMap<>();

    public IamS1TableRequestDTO(String tableName, Set<String> referencedColumns) {
        this.tableName = tableName;
        this.referencedColumns = referencedColumns;
    }

    public IamS1TableRequestDTO(String tableName, String... columns) {
        this.tableName = tableName;
        for (String column : columns) {
            this.referencedColumns.add(column);
        }
    }
}
