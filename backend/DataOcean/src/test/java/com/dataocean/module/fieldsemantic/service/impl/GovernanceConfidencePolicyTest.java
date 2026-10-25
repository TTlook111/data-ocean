package com.dataocean.module.fieldsemantic.service.impl;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class GovernanceConfidencePolicyTest {
    @Test void repeatedOpinionsFromSameEvidenceSourceCountOnlyOnce() {
        var first=new com.dataocean.module.fieldsemantic.entity.po.GovernanceConfidenceEvent();first.setId(1L);first.setEvidenceKey("same-query-user");first.setDirection("LIKE");
        var second=new com.dataocean.module.fieldsemantic.entity.po.GovernanceConfidenceEvent();second.setId(2L);second.setEvidenceKey("same-query-user");second.setDirection("DISLIKE");
        var counts=GovernanceConfidencePolicy.summarize(java.util.List.of(first,second));
        assertThat(counts.positive()).isZero();assertThat(counts.negative()).isEqualTo(1);
    }
    @Test void noUsageEvidenceIsUnknownRatherThanZero() { assertThat(GovernanceConfidencePolicy.score(0,0)).isNull(); }
    @Test void evidenceMovesScoreButDoesNotPretendCertainty() {
        assertThat(GovernanceConfidencePolicy.score(1,0)).isBetween(50,99);
        assertThat(GovernanceConfidencePolicy.score(0,1)).isBetween(1,49);
        assertThat(GovernanceConfidencePolicy.score(1,1)).isEqualTo(50);
    }
}
