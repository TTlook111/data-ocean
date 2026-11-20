package com.dataocean.module.model.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 已接入的业务节点及其独立分配状态。
 */
@Data
@AllArgsConstructor
public class ModelNodeVO {

    /**
     * 稳定节点编码。
     */
    private String code;

    /**
     * 当前选择，未分配时为空。
     */
    private String modelId;

    /**
     * 节点并发版本。
     */
    private int version;
}
