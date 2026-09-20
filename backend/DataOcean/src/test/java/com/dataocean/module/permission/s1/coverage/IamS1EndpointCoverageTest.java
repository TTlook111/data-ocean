package com.dataocean.module.permission.s1.coverage;

import com.dataocean.module.permission.s1.annotation.IamS1Global;
import com.dataocean.module.permission.s1.annotation.IamS1Resource;
import com.dataocean.module.permission.s1.annotation.IamS1ScopedList;
import com.dataocean.module.permission.s1.catalog.IamS1FunctionCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1 接口权限覆盖扫描：用 Spring MVC 的 {@link RequestMappingHandlerMapping} 枚举**真实**的
 * {@link HandlerMethod}，而不是源码正则。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>已迁移的 Controller 里**每个** Handler 方法都必须声明一个 S1 注解；</li>
 *   <li>已迁移端点不得保留旧 {@code @PreAuthorize}；</li>
 *   <li>注解里的功能码必须来自固定 54 码目录，且全局/资源语义与 B0 冻结一致；</li>
 *   <li>未迁移的 Controller 必须登记在 {@link #PENDING_MIGRATION_CONTROLLERS}，
 *       禁止用 {@code /api/admin/**} 这类宽泛前缀例外；</li>
 *   <li>新增 Handler 未声明权限时测试失败。</li>
 * </ul>
 *
 * <p><b>例外清单的粒度</b>：已迁移范围内精确到 Handler 方法；未迁移部分按 Controller + 计划批次登记。
 * 每迁移一个批次就把对应 Controller 从清单移出——移出后该 Controller 立即接受“精确到方法”的检查。
 * 这样既保证本轮范围无遗漏，又避免一次性登记 170 余条会随批次快速失效的端点条目。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class IamS1EndpointCoverageTest {

    /** 已完成 S1 注解迁移的 Controller：类内每个 Handler 都必须有 S1 注解。 */
    private static final Set<String> MIGRATED_CONTROLLERS = Set.of(
            "DashboardController",
            "DatasourceAdminController",
            "MetadataCatalogController",
            "MetadataCollectionController",
            "SnapshotVersionController");

    /**
     * 逐端点临时例外清单（键为 `HTTP 方法 + 完整路径`，值为 `Controller#Handler方法|原因`）。
     *
     * <p>由覆盖扫描从真实 HandlerMethod 生成并冻结为安全基线：**每迁移一个端点就删除一条，不新增条目**。
     * 新增 Handler 若未声明 S1 注解、且不在本清单中，测试立即失败——这一点在 Controller 级豁免下做不到。</p>
     */
    private static final Map<String, String> EXEMPTIONS = IamS1EndpointExemptions.EXEMPTIONS;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyMigratedEndpointDeclaresExactlyOneS1Annotation() {
        List<String> violations = new ArrayList<>();
        for (HandlerMethod handler : adminHandlers()) {
            if (!MIGRATED_CONTROLLERS.contains(handler.getBeanType().getSimpleName())) {
                continue;
            }
            int count = s1AnnotationCount(handler);
            if (count == 0) {
                violations.add(describe(handler) + " 缺少 S1 权限注解");
            } else if (count > 1) {
                violations.add(describe(handler) + " 声明了多个 S1 权限注解");
            }
        }
        assertThat(violations).as("已迁移端点必须各自声明一个 S1 注解").isEmpty();
    }

    @Test
    void migratedEndpointsDoNotKeepLegacyPreAuthorize() {
        List<String> violations = new ArrayList<>();
        for (HandlerMethod handler : adminHandlers()) {
            if (!MIGRATED_CONTROLLERS.contains(handler.getBeanType().getSimpleName())) {
                continue;
            }
            Method method = handler.getMethod();
            if (method.isAnnotationPresent(PreAuthorize.class)
                    || handler.getBeanType().isAnnotationPresent(PreAuthorize.class)) {
                violations.add(describe(handler) + " 仍在用旧 @PreAuthorize");
            }
        }
        assertThat(violations).as("已迁移端点不得同时命中新旧两套权限实现").isEmpty();
    }

    @Test
    void annotationFunctionCodesComeFromTheFixedCatalog() {
        List<String> violations = new ArrayList<>();
        for (HandlerMethod handler : adminHandlers()) {
            IamS1Global global = handler.getMethodAnnotation(IamS1Global.class);
            IamS1Resource resource = handler.getMethodAnnotation(IamS1Resource.class);
            IamS1ScopedList scopedList = handler.getMethodAnnotation(IamS1ScopedList.class);

            if (global != null && IamS1FunctionCatalog.scopeOf(global.value())
                    != IamS1FunctionCatalog.FunctionScope.GLOBAL) {
                violations.add(describe(handler) + " 把非全局功能标成了 @IamS1Global：" + global.value());
            }
            if (resource != null && IamS1FunctionCatalog.scopeOf(resource.function())
                    != IamS1FunctionCatalog.FunctionScope.RESOURCE) {
                violations.add(describe(handler) + " 把非源范围功能标成了 @IamS1Resource：" + resource.function());
            }
            if (scopedList != null && IamS1FunctionCatalog.scopeOf(scopedList.value())
                    != IamS1FunctionCatalog.FunctionScope.RESOURCE) {
                violations.add(describe(handler) + " 把非源范围功能标成了 @IamS1ScopedList：" + scopedList.value());
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void everyAnnotatedFunctionCodeExistsInTheCatalog() {
        List<String> violations = new ArrayList<>();
        for (HandlerMethod handler : adminHandlers()) {
            IamS1Global global = handler.getMethodAnnotation(IamS1Global.class);
            IamS1Resource resource = handler.getMethodAnnotation(IamS1Resource.class);
            IamS1ScopedList scopedList = handler.getMethodAnnotation(IamS1ScopedList.class);
            for (String code : new String[]{
                    global == null ? null : global.value(),
                    resource == null ? null : resource.function(),
                    scopedList == null ? null : scopedList.value()}) {
                if (code != null && IamS1FunctionCatalog.find(code) == null) {
                    violations.add(describe(handler) + " 使用了未知功能码：" + code);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void writeEndpointsDoNotDeclareOnlyAViewFunction() {
        // 写接口不能只声明 view 功能：URL 里带管理动作的端点若标了 :view 或 release:view，
        // 说明准入声明与操作语义不匹配。
        List<String> violations = new ArrayList<>();
        for (HandlerMethod handler : adminHandlers()) {
            IamS1Resource resource = handler.getMethodAnnotation(IamS1Resource.class);
            IamS1ScopedList scopedList = handler.getMethodAnnotation(IamS1ScopedList.class);
            String code = resource != null ? resource.function() : (scopedList != null ? scopedList.value() : null);
            if (code == null) {
                continue;
            }
            // 用真实 HTTP 方法判定写操作，不用方法名启发式：
            // publishedSnapshot 这类读端点名字以 publish 开头，按名字判会误报。
            if (isWriteHandler(handler) && code.endsWith(":view")) {
                violations.add(describe(handler) + " 是写操作却只声明了查看功能：" + code);
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void everyAdminEndpointIsAnnotatedOrExplicitlyExempted() {
        TreeSet<String> unknown = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : adminEntries()) {
            HandlerMethod handler = entry.getValue();
            if (s1AnnotationCount(handler) > 0) {
                continue;
            }
            // 精确到 HTTP 方法 + 完整路径：Controller 级豁免会让该类新增的未注解方法自动通过。
            String key = keyOf(entry.getKey());
            if (EXEMPTIONS.containsKey(key)) {
                continue;
            }
            unknown.add(key + "  " + describe(handler));
        }
        assertThat(unknown)
                .as("未声明 S1 注解的端点必须逐条登记在例外清单；新增端点不能静默通过")
                .isEmpty();
    }

    @Test
    void exemptionsMustReferenceRealHandlers() {
        // 防止清单残留过期条目，并核对 value 里的 Controller#method 与真实映射一致：
        // 只校验“路径存在”是不够的——路径不变但 Handler 被替换或重命名时清单仍会通过。
        Map<String, String> actualHandlers = new java.util.HashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : adminEntries()) {
            actualHandlers.put(keyOf(entry.getKey()), describe(entry.getValue()));
        }
        for (Map.Entry<String, String> exemption : EXEMPTIONS.entrySet()) {
            String key = exemption.getKey();
            String declaredHandler = exemption.getValue().split("\\|", 2)[0];
            assertThat(actualHandlers)
                    .as("例外清单出现了当前不存在的端点，请删除过期条目：" + key)
                    .containsKey(key);
            assertThat(actualHandlers.get(key))
                    .as("例外清单的 Handler 与真实映射不一致：" + key)
                    .isEqualTo(declaredHandler);
        }
    }

    @Test
    void exemptionsDoNotCoverMigratedControllers() {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : adminEntries()) {
            HandlerMethod handler = entry.getValue();
            if (!MIGRATED_CONTROLLERS.contains(handler.getBeanType().getSimpleName())) {
                continue;
            }
            assertThat(EXEMPTIONS)
                    .as("已迁移 Controller 不得再出现在例外清单里：" + describe(handler))
                    .doesNotContainKey(keyOf(entry.getKey()));
        }
    }

    private List<HandlerMethod> adminHandlers() {
        return adminEntries().stream().map(Map.Entry::getValue).toList();
    }

    private List<Map.Entry<RequestMappingInfo, HandlerMethod>> adminEntries() {
        List<Map.Entry<RequestMappingInfo, HandlerMethod>> entries = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            String pattern = String.valueOf(entry.getKey().getPathPatternsCondition() != null
                    ? entry.getKey().getPathPatternsCondition().getPatterns()
                    : entry.getKey().getPatternValues());
            if (pattern.contains("/api/admin/") || pattern.contains("/api/iam-s1/")) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /** 与 {@code IamS1EndpointExemptions} 生成时一致的键：`HTTP方法 path`，路径去掉集合括号。 */
    private String keyOf(RequestMappingInfo info) {
        String methods = info.getMethodsCondition().getMethods().isEmpty()
                ? "ANY"
                : info.getMethodsCondition().getMethods().stream()
                .map(RequestMethod::name).sorted().collect(java.util.stream.Collectors.joining(","));
        String path = String.valueOf(info.getPathPatternsCondition() != null
                ? info.getPathPatternsCondition().getPatterns()
                : info.getPatternValues()).replaceAll("[\\[\\]]", "").trim();
        return methods + " " + path;
    }

    private int s1AnnotationCount(HandlerMethod handler) {
        int count = 0;
        if (handler.hasMethodAnnotation(IamS1Global.class)) {
            count++;
        }
        if (handler.hasMethodAnnotation(IamS1Resource.class)) {
            count++;
        }
        if (handler.hasMethodAnnotation(IamS1ScopedList.class)) {
            count++;
        }
        return count;
    }

    /** 该 Handler 是否绑定到写方法（POST/PUT/PATCH/DELETE）。 */
    private boolean isWriteHandler(HandlerMethod handler) {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (!entry.getValue().equals(handler)) {
                continue;
            }
            return entry.getKey().getMethodsCondition().getMethods().stream().anyMatch(method ->
                    method == RequestMethod.POST || method == RequestMethod.PUT
                            || method == RequestMethod.PATCH || method == RequestMethod.DELETE);
        }
        return false;
    }

    private String describe(HandlerMethod handler) {
        return handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
    }
}
