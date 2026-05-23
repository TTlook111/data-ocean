package com.dataocean.module.governance.entity.dto;

import lombok.Data;

import java.util.List;

/**
 * 质量校验触发请求参数。
 * <p>
 * 可指定校验维度和表名范围；为空时执行默认全量校验。
 * </p>
 */
@Data
public class QualityCheckRequestDTO {

    private Long snapshotId;
    private List<String> dimensions;
    private List<String> tableNames;
}
