package com.dataocean.module.knowledge.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 候选构建前核对已确认内容、题单和向量配置。
 */
@Data
@AllArgsConstructor
public class KnowledgeReleasePreflightVO {

    /**
     * 本次检查的文档及确切确认。
     */
    private String documentId;

    /**
     * 尚待文档确认时为空。
     */
    private String documentRevisionId;

    /**
     * 当前已审核且归属同一确认的标准题数量。
     */
    private int reviewedTests;

    /**
     * 兼容向量节点现在选择的配置修订。
     */
    private String embeddingRevisionId;

    /**
     * 影响构建准备的实际原因。
     */
    private List<String> issues;
}
