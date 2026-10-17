package com.dataocean.module.glossary.entity.vo;

/** 字段映射固定到真实元数据对象及来源快照。 */
public record TermColumnVO(Long entityId, Long datasourceId, String datasourceName, Long snapshotId,
        Long tableId, Long columnId, String tableName, String columnName, String columnType,
        String description, String fqn) {}
