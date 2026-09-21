package com.dataocean.module.governance.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.entity.MetadataQualityRule;
import com.dataocean.module.governance.mapper.MetadataQualityRuleMapper;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 4 聚焦测试：全局质量规则启停只允许受保护系统管理员。
 *
 * <p>`metadata_quality_rule` 没有 datasourceId，一条规则的启停会影响**所有**数据源。
 * 因此这里不能只看 `governance:rule:manage`：非系统管理员即使在某些负责源上拥有该功能，
 * 也不能改全局规则。表/列治理状态是另一条链路，不受本限制。</p>
 */
class QualityRuleServiceImplTest {

    @Test
    void rejectsNonSystemAdminEvenWhenTheOperatorHasTheFunction() {
        Fixture fixture = new Fixture();
        fixture.rule(3L, 1);
        // 注解层已放行（该用户确实持有 governance:rule:manage），但全局规则必须再收一层
        when(fixture.authorizationResolver.isSystemAdmin(99L)).thenReturn(false);

        assertThatThrownBy(() -> fixture.service.updateEnabled(3L, false, 99L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(403));

        // 拒绝必须发生在读取与写入之前：没有任何规则被改动
        verify(fixture.ruleMapper, never()).selectById(any());
        verify(fixture.ruleMapper, never()).updateById(any(MetadataQualityRule.class));
    }

    @Test
    void rejectsAnonymousOperator() {
        Fixture fixture = new Fixture();
        fixture.rule(3L, 1);

        assertThatThrownBy(() -> fixture.service.updateEnabled(3L, false, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(403));
        verify(fixture.ruleMapper, never()).updateById(any(MetadataQualityRule.class));
    }

    @Test
    void allowsTheProtectedSystemAdmin() {
        Fixture fixture = new Fixture();
        MetadataQualityRule rule = fixture.rule(3L, 1);
        when(fixture.authorizationResolver.isSystemAdmin(1L)).thenReturn(true);

        fixture.service.updateEnabled(3L, false, 1L);

        assertThat(rule.getEnabled()).isZero();
        verify(fixture.ruleMapper).updateById(rule);
    }

    @Test
    void rejectsMissingRuleInsteadOfSilentlySucceeding() {
        Fixture fixture = new Fixture();
        when(fixture.authorizationResolver.isSystemAdmin(1L)).thenReturn(true);

        assertThatThrownBy(() -> fixture.service.updateEnabled(404L, false, 1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(404));
        verify(fixture.ruleMapper, never()).updateById(any(MetadataQualityRule.class));
    }

    private static final class Fixture {
        private final MetadataQualityRuleMapper ruleMapper = mock(MetadataQualityRuleMapper.class);
        private final IamS1AuthorizationResolver authorizationResolver = mock(IamS1AuthorizationResolver.class);
        private final QualityRuleServiceImpl service = new QualityRuleServiceImpl(ruleMapper, authorizationResolver);

        MetadataQualityRule rule(Long id, int enabled) {
            MetadataQualityRule rule = new MetadataQualityRule();
            rule.setId(id);
            rule.setEnabled(enabled);
            when(ruleMapper.selectById(id)).thenReturn(rule);
            return rule;
        }
    }
}
