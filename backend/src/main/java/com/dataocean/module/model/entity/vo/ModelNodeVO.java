package com.dataocean.module.model.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 产品定义的业务节点及其独立分配状态。
 */
@Data
@AllArgsConstructor
public class ModelNodeVO {

    /**
     * 稳定节点编码。
     */
    private String code;

    /**
     * 该节点接受生成、向量化或重排模型中的哪一种能力。
     */
    private String capability;

    /**
     * 当前选择，未分配时为空。
     */
    private String modelId;

    /**
     * 节点并发版本。
     */
    private int version;

    /**
     * 当前业务实现是否存在对应该节点的独立提供方调用入口。
     */
    private boolean connected;
}
