package com.dataocean.module.permission.s1.catalog;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固定 54 码的范围语义完整性。
 *
 * <p>切面用 {@code scopeOf} 判断“注解语义是否与 B0 冻结一致”。如果某个码漏登记，
 * 切面会把它当未知码拒绝；如果登记错了，源范围功能就会被当成全局功能放行。
 * 所以三类集合必须恰好覆盖 54 码且互不重叠。</p>
 */
class IamS1FunctionCatalogScopeTest {

    @Test
    void everyCatalogCodeHasExactlyOneScope() {
        Set<String> declared = new LinkedHashSet<>();
        int duplicates = 0;
        for (IamS1FunctionCatalog.Definition definition : IamS1FunctionCatalog.definitions()) {
            if (IamS1FunctionCatalog.scopeOf(definition.code()) == null) {
                throw new AssertionError("功能码缺少范围语义：" + definition.code());
            }
            if (!declared.add(definition.code())) {
                duplicates++;
            }
        }
        assertThat(declared).hasSize(54);
        assertThat(duplicates).isZero();
        assertThat(IamS1FunctionCatalog.scopedCodes()).hasSize(54);
    }

    @Test
    void unknownCodeHasNoScope() {
        assertThat(IamS1FunctionCatalog.scopeOf("nope:nope:nope")).isNull();
        assertThat(IamS1FunctionCatalog.scopeOf(null)).isNull();
    }

    @Test
    void sourceRangeFunctionsAreNotMarkedGlobal() {
        // 这几个是切面最容易被错标成全局功能的源范围码。
        for (String code : Set.of("datasource:view", "datasource:manage", "metadata:view",
                "metadata:release:view", "security:mask:view", "lineage:view")) {
            assertThat(IamS1FunctionCatalog.scopeOf(code))
                    .as(code + " 必须保持源范围语义")
                    .isEqualTo(IamS1FunctionCatalog.FunctionScope.RESOURCE);
        }
    }

    @Test
    void organizationAndSystemFunctionsAreGlobal() {
        for (String code : Set.of("admin:workbench:view", "organization:user:view",
                "organization:role:view", "system:runtime:view", "operation-log:view")) {
            assertThat(IamS1FunctionCatalog.scopeOf(code))
                    .as(code + " 必须是全局功能")
                    .isEqualTo(IamS1FunctionCatalog.FunctionScope.GLOBAL);
        }
    }

    @Test
    void mixedGlossaryCodesStayUnresolved() {
        // “源/全”语义未定稿前，注解框架必须拒绝使用，避免把未定语义固化进代码。
        for (String code : Set.of("glossary:view", "glossary:manage", "glossary:approve")) {
            assertThat(IamS1FunctionCatalog.scopeOf(code))
                    .isEqualTo(IamS1FunctionCatalog.FunctionScope.MIXED);
        }
    }
}
