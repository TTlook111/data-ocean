package com.dataocean.module.fieldsemantic.service.impl;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GovernanceTaskPolicyTest {
    @Test void failureAndPartialCompletionAreNotSuccessfulGovernance() {
        assertThat(GovernanceTaskPolicy.summary(2, 1, 1, 0)).isEqualTo("PARTIAL");
        assertThat(GovernanceTaskPolicy.summary(2, 0, 0, 2)).isEqualTo("FAILED");
        assertThat(GovernanceTaskPolicy.summary(2, 2, 0, 0)).isEqualTo("COMPLETED");
        assertThat(GovernanceTaskPolicy.summary(0, 0, 0, 0)).isEqualTo("EMPTY");
    }
    @Test void negativeFeedbackRequiresANewRevisionBeforeCompletion() {
        assertThat(GovernanceTaskPolicy.canFinish("FEEDBACK", 10L, 10L, "ROUND_COMPLETED")).isFalse();
        assertThat(GovernanceTaskPolicy.canFinish("FEEDBACK", 10L, 11L, "ROUND_COMPLETED")).isTrue();
        assertThat(GovernanceTaskPolicy.canFinish("DISCOVERY", 10L, 10L, "AWAITING_RECHECK")).isFalse();
    }
}
