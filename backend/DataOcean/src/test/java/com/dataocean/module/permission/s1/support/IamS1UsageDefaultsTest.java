package com.dataocean.module.permission.s1.support;

import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.enums.IamS1ColumnUsage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** usage 默认值与字段保护联动：脱敏字段只能直接投影，隐藏字段不参与问数。 */
class IamS1UsageDefaultsTest {

    @Test
    void defaultUsagesAreTheThreeAstEnforcedRoles() {
        assertThat(IamS1UsageDefaults.defaults())
                .containsExactlyInAnyOrder(IamS1ColumnUsage.PROJECTION, IamS1ColumnUsage.FILTER,
                        IamS1ColumnUsage.JOIN);
    }

    @Test
    void maskedColumnMayOnlyBeProjected() {
        assertThat(IamS1UsageDefaults.forProtectionLevel(IamS1Constants.PROTECTION_MASKED))
                .containsExactly(IamS1ColumnUsage.PROJECTION);
        // 勾选扩展位置也不能放开脱敏字段
        assertThat(IamS1UsageDefaults.forProtectionLevel(IamS1Constants.PROTECTION_MASKED, true))
                .containsExactly(IamS1ColumnUsage.PROJECTION);
    }

    @Test
    void hiddenColumnHasNoUsage() {
        assertThat(IamS1UsageDefaults.forProtectionLevel(IamS1Constants.PROTECTION_HIDDEN)).isEmpty();
    }

    @Test
    void normalColumnUsesDefaultOrExtendedRoles() {
        assertThat(IamS1UsageDefaults.forProtectionLevel(IamS1Constants.PROTECTION_NORMAL))
                .isEqualTo(IamS1UsageDefaults.defaults());
        assertThat(IamS1UsageDefaults.forProtectionLevel(IamS1Constants.PROTECTION_NORMAL, true))
                .containsAll(IamS1UsageDefaults.defaults())
                .contains(IamS1ColumnUsage.ORDER, IamS1ColumnUsage.GROUP, IamS1ColumnUsage.HAVING,
                        IamS1ColumnUsage.FUNCTION, IamS1ColumnUsage.SUBQUERY);
        assertThat(IamS1UsageDefaults.forProtectionLevel(null)).isEqualTo(IamS1UsageDefaults.defaults());
    }
}
