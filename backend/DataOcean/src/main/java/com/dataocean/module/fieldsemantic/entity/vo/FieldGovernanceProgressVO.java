package com.dataocean.module.fieldsemantic.entity.vo;

/** 当前字段治理阶段；不是文档发布或 RAG 构建状态。 */
public record FieldGovernanceProgressVO(
        Long columnMetaId, Long snapshotId, String stage, String nextAction,
        String reason, Long revisionId, Long checkBatchId) {
}
