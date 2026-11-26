package com.dataocean.module.governance.entity.vo;

import java.util.List;

import tools.jackson.databind.JsonNode;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 向知识模块交付可复核的当前确认，原始资料留在所属模块。
 */
@Data
@AllArgsConstructor
public class GovernanceFactVO {

    /**
     * 源内稳定治理目标。
     */
    private GovernanceTargetVO target;

    /**
     * 本次可引用的实际确认内容修订。
     */
    private String revisionId;

    /**
     * 当前对象世代和固定结构。
     */
    private int generation;

    /**
     * 表使用对象修订，字段使用列修订。
     */
    private String structureRevisionId;

    /**
     * 便于从文档回到对应治理条目。
     */
    private String label;

    /**
     * 仅提供确认后的定义内容。
     */
    private JsonNode content;

    /**
     * 按当前确认读取时必须纳入的稳定依赖。
     */
    private List<GovernanceTargetVO> dependencies;
}
