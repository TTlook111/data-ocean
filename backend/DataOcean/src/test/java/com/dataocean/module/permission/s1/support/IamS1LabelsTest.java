package com.dataocean.module.permission.s1.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** B4：中文模板与摘要句式，页面不暴露 scope/effect/policy 等技术词。 */
class IamS1LabelsTest {

    @Test
    void capabilitySummaryUsesChineseFunctionNames() {
        String summary = IamS1Labels.capabilitySummary(List.of("query:use", "query:sql:view", "query:export"));

        assertThat(summary).isEqualTo("可以使用：使用问数、查看 SQL、导出结果");
    }

    @Test
    void capabilitySummaryExplainsEmptySelection() {
        assertThat(IamS1Labels.capabilitySummary(List.of()))
                .isEqualTo("未选择任何功能，无法进入任何业务工作区");
    }

    @Test
    void departmentGrantSummaryMentionsDescendantsColumnsConditionsAndValidity() {
        String summary = IamS1Labels.grantSummary("DEPARTMENT", "销售部", "INCLUDE_DESCENDANTS", "ALLOW",
                "销售库", "orders", List.of("order_id", "amount"), List.of("region 等于 华东"), false,
                "2026-12-31 23:59");

        assertThat(summary).contains("允许销售部及下级部门查询销售库的 orders 表");
        assertThat(summary).contains("只包含字段：order_id、amount");
        assertThat(summary).contains("只看 region 等于 华东 的记录");
        assertThat(summary).contains("有效期至 2026-12-31 23:59");
    }

    @Test
    void datasourceDenySummaryDoesNotPretendToBeAllowed() {
        String summary = IamS1Labels.grantSummary("USER", "张三", null, "DENY", "人事库", null,
                List.of(), List.of(), true, null);

        assertThat(summary).isEqualTo("禁止张三查询人事库的全部数据，长期有效");
    }

    @Test
    void conditionValueSummaryHandlesNullCollectionsAndScalars() {
        assertThat(IamS1Labels.valueSummary("IS_NULL", null)).isEqualTo("为空");
        assertThat(IamS1Labels.valueSummary("IN", "[\"华东\",\"华南\"]")).isEqualTo("华东、华南");
        assertThat(IamS1Labels.valueSummary("EQ", "\"华东\"")).isEqualTo("华东");
        assertThat(IamS1Labels.valueSummary("EQ", null)).isEqualTo("按服务端参数注入");
    }

    @Test
    void technicalWordsAreTranslatedForAdmins() {
        assertThat(IamS1Labels.protectionLevelName("HIDDEN")).isEqualTo("隐藏字段");
        assertThat(IamS1Labels.maskPolicyName("PHONE")).isEqualTo("手机号掩码");
        assertThat(IamS1Labels.effectName("DENY")).isEqualTo("禁止查询");
        assertThat(IamS1Labels.grantSourceName("APPROVAL")).isEqualTo("访问审批通过");
        assertThat(IamS1Labels.requestStatusName("WITHDRAWN")).isEqualTo("已撤回");
    }
}
