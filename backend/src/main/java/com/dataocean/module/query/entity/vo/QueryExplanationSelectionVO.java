package com.dataocean.module.query.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型选择说明事实，Java 核对编号并使用原事实构成说明。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryExplanationSelectionVO {

    /**
     * 本次选中的事实编号，从 1 开始。
     */
    private List<Integer> factIds;
}
