package com.dataocean.module.knowledge.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 发布任务详情首屏的固定范围与历次构建。
 */
@Data
@AllArgsConstructor
public class KnowledgeReleaseDetailVO {

    /**
     * 当前源显示名。
     */
    private String sourceName;

    /**
     * 任务绑定的文档编号。
     */
    private String documentId;

    /**
     * 固定文档修订的可读编号，自一开始。
     */
    private int documentRevisionNumber;

    /**
     * 本次候选身份、基础绑定及阶段。
     */
    private KnowledgeReleaseCandidateVO candidate;

    /**
     * 本次候选固定的审核题版本数，作为范围核对事实。
     */
    private int reviewedTests;

    /**
     * 从最新到最早的实际构建记录。
     */
    private List<KnowledgeReleaseBuildVO> builds;
}
