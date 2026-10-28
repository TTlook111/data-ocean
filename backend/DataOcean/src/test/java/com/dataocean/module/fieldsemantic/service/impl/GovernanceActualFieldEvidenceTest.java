package com.dataocean.module.fieldsemantic.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.query.entity.po.QueryTask;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GovernanceActualFieldEvidenceTest {
    private QueryTask task(String actual) {
        return QueryTask.builder().status("COMPLETED").usedColumns(actual)
                .iamResourceRequest("[{\"tableName\":\"orders\",\"referencedColumns\":[\"id\",\"region\"],\"columnUsages\":{\"id\":[\"PROJECTION\"],\"region\":[\"FILTER\"]}}]").build();
    }
    @Test void countStarAuthorizationAnchorIsNotAnActualUsedField() {
        assertThat(GovernanceFieldProfileServiceImpl.usedFields(task("[]"))).isEmpty();
    }
    @Test void onlyAstUsedFieldsCanBeAttributedIncludingFilters() {
        assertThat(GovernanceFieldProfileServiceImpl.usedFields(task("[\"orders.region\"]")))
                .containsExactly("orders.region");
    }
    @Test void absentOrInvalidActualEvidenceCannotInheritAuthorizationFields() {
        assertThat(GovernanceFieldProfileServiceImpl.usedFields(task(null))).isEmpty();
        assertThatThrownBy(() -> GovernanceFieldProfileServiceImpl.usedFields(task("[\"orders.secret\"]")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> GovernanceFieldProfileServiceImpl.usedFields(task("not-json")))
                .isInstanceOf(BusinessException.class);
    }
}
