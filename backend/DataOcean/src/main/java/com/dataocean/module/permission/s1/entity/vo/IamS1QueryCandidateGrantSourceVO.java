package com.dataocean.module.permission.s1.entity.vo;

import java.util.List;

/** Candidate grant facts with ISO strings for the strict Java/Python boundary. */
public record IamS1QueryCandidateGrantSourceVO(
        Long grantId,
        String subjectType,
        Long subjectId,
        String sourceSummary,
        String departmentScope,
        String grantSource,
        Long sourceReferenceId,
        String validFrom,
        String validUntil,
        List<String> explicitColumns,
        IamS1RowConditionVO rowCondition) {
}
