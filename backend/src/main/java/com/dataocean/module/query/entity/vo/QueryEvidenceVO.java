package com.dataocean.module.query.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发布索引的实际命中定位，结果中不输出向量或内部连接信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryEvidenceVO {

    /**
     * 对话或正式知识来源的可见标题。
     */
    private String title;

    /**
     * 所用证据、解释或知识文档的修订序号。
     */
    private int revision;

    /**
     * 实际命中的知识文档修订编号。
     */
    private String revisionId;

    /**
     * 索引中实际命中的片段序号。
     */
    private int fragment;
}
