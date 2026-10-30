package com.dataocean.module.permission.s1.entity.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 为用户角色绑定批量设置“后台负责数据源”。负责源只限制后台工作范围，不授予业务查询权。 */
@Data
public class IamS1DatasourceBindingDTO {

    @NotEmpty(message = "至少需要一个后台负责数据源")
    private List<@NotNull(message = "后台负责数据源 ID 不能为空") Long> datasourceIds = new ArrayList<>();

    private String reason;
}
