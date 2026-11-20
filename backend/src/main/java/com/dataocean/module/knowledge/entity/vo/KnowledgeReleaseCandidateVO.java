package com.dataocean.module.knowledge.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 发布候选列表回读所需的非秘密冻结信息。
 */
@Data
@AllArgsConstructor
public class KnowledgeReleaseCandidateVO {

    /**
     * 候选稳定标识。
     */
    private String id;

    /**
     * 候选关联的发布范围。
     */
    private String scopeId;

    /**
     * 准备时固定的基础生效绑定，首版为空。
     */
    private String baseBindingId;

    /**
     * 候选固定使用的已确认正文修订。
     */
    private String documentRevisionId;

    /**
     * 固定的向量模型配置修订。
     */
    private String embeddingRevisionId;

    /**
     * 确认时记录的文档名称。
     */
    private String title;

    /**
     * 供后续写动作确认实际输入的指纹。
     */
    private String fingerprint;

    /**
     * 当前业务阶段。
     */
    private String status;

    /**
     * 当前候选并发版本。
     */
    private int version;

    /**
     * 候选准备成功的实际时间。
     */
    private Instant createdAt;
}
