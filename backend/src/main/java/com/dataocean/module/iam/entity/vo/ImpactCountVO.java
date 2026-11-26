package com.dataocean.module.iam.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 影响核对的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class ImpactCountVO {

    /**
     * 当前引用或统计对象的业务类别。
     */
    private String kind;

    /**
     * 当前范围内按契约口径计算的对象数量。
     */
    private int count;
}
