package com.dataocean.module.fieldsemantic.service.impl;
import com.dataocean.module.fieldsemantic.entity.po.FieldSemanticRevision;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
class FieldSemanticCompletenessPolicyTest {
    @Test void unitsAndEnumsAreRequiredOnlyForClassifiedRoles(){
        var revision=new FieldSemanticRevision();
        assertThat(FieldSemanticCompletenessPolicy.missing(revision,Set.of("TEXT"))).isEmpty();
        assertThat(FieldSemanticCompletenessPolicy.missing(revision,Set.of("AMOUNT"))).contains("金额单位");
        assertThat(FieldSemanticCompletenessPolicy.missing(revision,Set.of("STATUS"))).contains("枚举含义");
        revision.setUnit("分");revision.setEnumSemantics("1=已支付，2=已退款");
        assertThat(FieldSemanticCompletenessPolicy.missing(revision,Set.of("AMOUNT","STATUS"))).isEmpty();
    }
}
