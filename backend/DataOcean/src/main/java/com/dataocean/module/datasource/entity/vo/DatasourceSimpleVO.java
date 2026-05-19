package com.dataocean.module.datasource.entity.vo;

import lombok.Data;

@Data
public class DatasourceSimpleVO {

    private Long id;
    private String name;
    private String databaseName;
    private String description;
}
