package com.dataocean.module.knowledge.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 任务详情的准备范围、当前构建条件及历次记录。
 */
@Data
@AllArgsConstructor
public class KnowledgeReleaseDetailVO {

    /**
     * 当前源显示名。
     */
    private String sourceName;

    /**
     * 固定文档修订的可读编号，自一开始。
     */
    private int documentRevisionNumber;

    /**
     * 任务身份、目标修订及其当前候选。
     */
    private KnowledgeReleaseTaskVO task;

    /**
     * 当前满足范围的审核题数，冻结后显示固定题数。
     */
    private int reviewedTests;

    /**
     * 准备任务可以开始构建时的预检摘要，冻结后为空。
     */
    private String inputFingerprint;

    /**
     * 首次构建需要处理的实际问题。
     */
    private List<String> issues;

    /**
     * 当前候选从最新到最早的实际构建记录。
     */
    private List<KnowledgeReleaseBuildVO> builds;

    /**
     * 调整范围前留下的候选及其独立记录。
     */
    private List<KnowledgeReleaseHistoryVO> history;
}
