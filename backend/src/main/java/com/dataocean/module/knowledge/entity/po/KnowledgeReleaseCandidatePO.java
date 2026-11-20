package com.dataocean.module.knowledge.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 发布候选的固定输入及当前处理状态。
 */
@Data
@TableName("kn_release_candidate")
public class KnowledgeReleaseCandidatePO {

    /**
     * 应用生成的候选标识。
     */
    @TableId(type = IdType.INPUT)
    private Long id;

    /**
     * 同源文档归属的稳定发布范围。
     */
    private Long scopeId;

    /**
     * 准备时的实际生效绑定，首次发布前为空。
     */
    private Long baseBindingId;

    /**
     * 确切确认正文以及依附其上的治理引用。
     */
    private Long documentRevisionId;

    /**
     * 本候选准备时所选向量模型配置修订。
     */
    private Long embeddingRevisionId;

    /**
     * 准备时所读确认正文的名称。
     */
    private String title;

    /**
     * 固定输入的规范指纹，以十六进制编码。
     */
    private String fingerprint;

    /**
     * 实际办理阶段，准备完成时为 PREPARED。
     */
    private String status;

    /**
     * 候选业务版本，从一开始。
     */
    private Integer version;

    /**
     * 发起准备的实际操作人。
     */
    private Long createdBy;

    /**
     * 平台库记录的候选创建时间，UTC 毫秒。
     */
    private LocalDateTime createdAt;
}
