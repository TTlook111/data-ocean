package com.dataocean.module.permission.s1.support;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.entity.dto.IamS1RowConditionDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S1 结构化记录条件操作符、值类型和 SQL 文本隔离测试。 */
class IamS1RowConditionValidatorTest {

    @Test
    void comparisonAndCollectionOperatorsAreAllowed() {
        assertThat(IamS1RowConditionValidator.validateAndCanonicalize(
                condition("EQ", "STRING", "\"华东\""), "VARCHAR(20)")).isEqualTo("\"华东\"");
        assertThat(IamS1RowConditionValidator.validateAndCanonicalize(
                condition("IN", "STRING_LIST", "[\"华东\",\"华南\"]"), "VARCHAR(20)")).contains("华南");
    }

    @Test
    void nullOperatorsUseNullValueTypeWithoutValue() {
        assertThat(IamS1RowConditionValidator.validateAndCanonicalize(
                condition("IS_NULL", "NULL", null), "VARCHAR(20)")).isNull();
    }

    @Test
    void unknownOperatorIsRejected() {
        assertThatThrownBy(() -> IamS1RowConditionValidator.validateAndCanonicalize(
                condition("LIKE", "STRING", "\"x\""), "VARCHAR(20)"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void illegalValueTypeAndRawSqlAreRejected() {
        assertThatThrownBy(() -> IamS1RowConditionValidator.validateAndCanonicalize(
                condition("EQ", "INTEGER", "\"x\""), "INT"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> IamS1RowConditionValidator.validateAndCanonicalize(
                condition("EQ", "STRING", "region = '华东'"), "VARCHAR(20)"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void parameterReferenceIsServerOnlyAndHasNoRawValue() {
        IamS1RowConditionDTO condition = condition("EQ", "STRING", null);
        condition.setParameterReference("REGION_PARAMETER");
        assertThat(IamS1RowConditionValidator.validateAndCanonicalize(condition, "VARCHAR(20)")).isNull();
        condition.setParameterReference("CURRENT_USER_ID");
        assertThatThrownBy(() -> IamS1RowConditionValidator.validateAndCanonicalize(condition, "VARCHAR(20)"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void collectionRequiresCollectionValueType() {
        assertThatThrownBy(() -> IamS1RowConditionValidator.validateAndCanonicalize(
                condition("IN", "STRING", "\"x\""), "VARCHAR(20)"))
                .isInstanceOf(BusinessException.class);
    }

    private IamS1RowConditionDTO condition(String operator, String valueType, String value) {
        return new IamS1RowConditionDTO(1L, "region", operator, valueType, value, null);
    }
}
