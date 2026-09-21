package com.dataocean.module.permission.s1.entity.vo;

/** 数据源最小引用，只提供业务名称与状态，不包含连接、密码或业务记录。 */
public record IamS1DatasourceRefVO(Long id, String name, boolean enabled) {
}
