package com.dataocean.module.knowledge.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 原任务下已替换候选的固定文档、阶段和构建证据。
 */
@Data
@AllArgsConstructor
public class KnowledgeReleaseHistoryVO {

    /**
     * 曾作为当前候选的稳定编号。
     */
    private String id;

    /**
     * 冻结时的文档名称。
     */
    private String title;

    /**
     * 旧范围的文档编号。
     */
    private String documentId;

    /**
     * 回读旧确认正文时使用的修订编号。
     */
    private String documentRevisionId;

    /**
     * 冻结正文的可读序号。
     */
    private int documentRevisionNumber;

    /**
     * 替换之前实际停留的候选阶段。
     */
    private String status;

    /**
     * 该候选原有的构建尝试，最新在前。
     */
    private List<KnowledgeReleaseBuildVO> builds;
}
