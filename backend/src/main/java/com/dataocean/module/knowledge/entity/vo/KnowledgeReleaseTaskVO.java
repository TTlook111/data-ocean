package com.dataocean.module.knowledge.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 发布列表和详情共享的任务范围与固定候选摘要。
 */
@Data
@AllArgsConstructor
public class KnowledgeReleaseTaskVO {

    /**
     * 准备任务的稳定编号。
     */
    private String id;

    /**
     * 首次构建前为空，构建重试沿用同一候选。
     */
    private String candidateId;

    /**
     * 任务选择的同源文档编号。
     */
    private String documentId;

    /**
     * 构建前预览当前生效绑定，冻结后为候选的固定基础，首版为空。
     */
    private String baseBindingId;

    /**
     * 本次选择的已确认正文修订。
     */
    private String documentRevisionId;

    /**
     * 确认时记录的文档名称。
     */
    private String title;

    /**
     * 供后续写动作确认实际输入的指纹，首次构建前为空。
     */
    private String fingerprint;

    /**
     * 当前业务阶段。
     */
    private String status;

    /**
     * 当前任务并发版本。
     */
    private int version;

    /**
     * 任务保存的实际时间。
     */
    private Instant createdAt;
}
