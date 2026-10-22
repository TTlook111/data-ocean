package com.dataocean.module.fieldsemantic.service;

/** Exact snapshot qualification result used by semantic checks and knowledge/RAG validators. */
public record FieldSemanticEvaluation(
        String outcome,
        String ruleCode,
        String reason,
        Long definitionId,
        Long bindingId,
        Long revisionId,
        Long reviewId,
        String contentHash,
        String dependencyHash,
        String evidenceHash) {
    public boolean usable() { return "VALID".equals(outcome); }
}
