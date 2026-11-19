package com.dataocean.module.governance.entity.vo;

import com.dataocean.module.governance.entity.dto.EvidenceLocationDTO;

/**
 * 受当前权限约束的具体引用。
 *
 * @param kind 依据类型
 * @param contentPath 对应业务字段
 * @param redacted 当前用户是否只可见遮蔽摘要
 * @param structure 获准的固定结构依据
 * @param noteId 获准资料编号
 * @param revisionId 获准资料修订主键
 * @param revision 资料修订序号
 * @param attachmentId 获准附件编号
 * @param location 实际引用位置
 * @param manualBasis 可披露的人工核对依据
 */
public record GovernanceEvidenceVO(
        String kind,
        String contentPath,
        boolean redacted,
        GovernanceBasisVO structure,
        String noteId,
        String revisionId,
        Integer revision,
        String attachmentId,
        EvidenceLocationDTO location,
        String manualBasis) {}
