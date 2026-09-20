package com.dataocean.module.permission.s1.aspect;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.s1.annotation.IamS1Global;
import com.dataocean.module.permission.s1.annotation.IamS1Resource;
import com.dataocean.module.permission.s1.annotation.IamS1ScopedList;
import com.dataocean.module.permission.s1.catalog.IamS1FunctionCatalog;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolver;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolverRegistry;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * S1 接人准入切面：只负责「动作准入」和「单/多资源负责源校验」。
 *
 * <p>职责边界：</p>
 * <ul>
 *   <li>底层复用 {@link IamS1AdminGuard}，**不复制**授权算法；</li>
 *   <li>不拼业务查询条件、不做列表范围下推、不替代领域规则——
 *       列表范围、分页、批量原子校验、审批子集、状态机和最终保护仍在 Service；</li>
 *   <li>只读 S1 事实：不读旧 JWT authority、旧角色权限表、旧数据授权或旧权限缓存。</li>
 * </ul>
 *
 * <p>执行顺序：Spring Security 先确认登录身份（filter 层），本切面在业务事务之前
 * （{@code @Order} 高于事务切面的 {@code LOWEST_PRECEDENCE}），操作日志切面最后记录结果。</p>
 *
 * <p><b>{@code @Aspect} 不能省。</b>只有 {@code @Component} 时 Spring AOP 不会把这个类当成切面，
 * 这些 {@code @Before} 通知一条都不会执行；而接入本框架时对应的旧 {@code @PreAuthorize} 已被删除，
 * {@code /api/admin/**} 在 SecurityConfig 里又只要求 {@code authenticated()}，
 * 结果就是**已迁移端点对任何已登录用户开放**。{@link MetadataGovernanceControllerAuthorizationTest}
 * 用 AspectJ 代理真实 Controller 验证“权限拒绝时 Service 零调用”，防止该注解再次被漏掉。</p>
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class IamS1AuthorizationAspect {

    private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();

    private final IamS1AdminGuard adminGuard;
    private final IamS1ResourceResolverRegistry resolverRegistry;
    private final ParameterNameDiscoverer parameterNameDiscoverer;

    @Autowired
    public IamS1AuthorizationAspect(IamS1AdminGuard adminGuard,
                                    IamS1ResourceResolverRegistry resolverRegistry) {
        this(adminGuard, resolverRegistry, new DefaultParameterNameDiscoverer());
    }

    /** 供测试注入“参数名不可用”的发现器，验证位置别名在那种情况下仍然可用。 */
    IamS1AuthorizationAspect(IamS1AdminGuard adminGuard,
                             IamS1ResourceResolverRegistry resolverRegistry,
                             ParameterNameDiscoverer parameterNameDiscoverer) {
        this.adminGuard = adminGuard;
        this.resolverRegistry = resolverRegistry;
        this.parameterNameDiscoverer = parameterNameDiscoverer;
    }

    /** 全局功能：只校验功能本身。 */
    @Before("@annotation(iamS1Global)")
    public void checkGlobal(JoinPoint joinPoint, IamS1Global iamS1Global) {
        String functionCode = iamS1Global.value();
        requireScope(functionCode, IamS1FunctionCatalog.FunctionScope.GLOBAL, "@IamS1Global");
        adminGuard.requireGlobalFunction(UserContext.currentUserId(), functionCode);
    }

    /**
     * 负责源过滤列表：只确认调用者拥有该功能。
     *
     * <p>**不解析资源、不裁剪数据**：可见数据源由 Service 调
     * {@code responsibleDatasourcesWithFunction()} 取得并下推 SQL。这里刻意不校验负责源，
     * 因为“一个负责源都没有”是合法的（返回空页），不是准入失败。</p>
     *
     * <p>范围语义接受两种：</p>
     * <ul>
     *   <li>{@code RESOURCE}（源范围）：入口没有单一资源 ID，范围由 Service 下推；</li>
     *   <li>{@code MIXED}（源/全混合，如 {@code glossary:*}）：同一功能在“已关联数据源”时按源、
     *       未关联时按全局。这里只做功能级准入，**动态范围由对应 Service 完成**——
     *       切面拿不到“这个术语关联了哪几个源”这类业务事实，也不应该去拼业务查询。</li>
     * </ul>
     *
     * <p>{@code GLOBAL} 功能仍然拒绝：全局功能没有“负责源”概念，用本注解声明会让
     * “范围由 Service 下推”的约定名不副实。</p>
     */
    @Before("@annotation(iamS1ScopedList)")
    public void checkScopedList(JoinPoint joinPoint, IamS1ScopedList iamS1ScopedList) {
        String functionCode = iamS1ScopedList.value();
        requireScopedListScope(functionCode);
        adminGuard.requireGlobalFunction(UserContext.currentUserId(), functionCode);
    }

    /**
     * {@code @IamS1ScopedList} 的范围校验：接受「源」与已定稿的「源/全」混合码。
     *
     * <p>与 {@link #requireScope} 分开的原因是三者语义不同：
     * {@code @IamS1Global} / {@code @IamS1Resource} 都要求**唯一确定**的范围语义，
     * 混合码在这两处都会把未定的动态范围固化成一个错误结论；而
     * {@code @IamS1ScopedList} 本来就只表达“功能级准入 + 范围另算”，
     * 混合码的“范围另算”正好落在它身上。</p>
     */
    private void requireScopedListScope(String functionCode) {
        IamS1FunctionCatalog.FunctionScope actual = IamS1FunctionCatalog.scopeOf(functionCode);
        if (actual == null) {
            throw new BusinessException(500, "@IamS1ScopedList 使用了未知功能码：" + functionCode);
        }
        if (actual == IamS1FunctionCatalog.FunctionScope.GLOBAL) {
            throw new BusinessException(500,
                    "@IamS1ScopedList 与功能码范围语义不一致：" + functionCode + " 实际为 GLOBAL");
        }
    }

    /**
     * 资源范围功能：逐个解析资源真实归属，任意一个无权则整体拒绝。
     */
    @Before("@annotation(iamS1Resource)")
    public void checkResource(JoinPoint joinPoint, IamS1Resource iamS1Resource) {
        String functionCode = iamS1Resource.function();
        requireScope(functionCode, IamS1FunctionCatalog.FunctionScope.RESOURCE, "@IamS1Resource");

        Long userId = UserContext.currentUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录，无法判定 IAM-SIMPLE-1 权限");
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();
        IamS1ResourceResolver resolver = resolverRegistry.require(iamS1Resource.resourceType());

        String[] expressions = iamS1Resource.resourceIds();
        if (expressions == null || expressions.length == 0) {
            throw new BusinessException(500, "@IamS1Resource 必须声明至少一个资源表达式");
        }
        // 逐个解析校验：多资源里任意一个无权就整体拒绝，不能“通过一个就放行”。
        for (String expression : expressions) {
            Object rawId = evaluateResourceId(expression, method, args);
            IamS1ResolvedResource resolved = resolver.resolve(rawId);
            // 解析器返回 null 也要 fail-closed，不能因为实现疏漏就 NPE 或放行。
            if (resolved == null || !resolved.hasDatasource()) {
                throw new BusinessException(403, "资源没有数据源归属，无法判定负责范围");
            }
            adminGuard.requireDatasourceFunction(userId, functionCode, resolved.datasourceId());
        }
    }

    /**
     * 受限 SpEL：只读取方法参数与 DTO 普通属性。
     *
     * <p>用 {@link SimpleEvaluationContext#forReadOnlyDataBinding()}：不允许调用 Bean、
     * 不允许类型引用、不允许构造对象、不允许执行任意方法。表达式**只负责提取资源 ID**，
     * 真实归属由解析器重新查询，绝不把表达式结果当成已验证的 datasourceId。</p>
     */
    private Object evaluateResourceId(String expression, Method method, Object[] args) {
        if (expression == null || expression.isBlank()) {
            throw new BusinessException(500, "@IamS1Resource 资源表达式不能为空");
        }
        SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        // 位置别名**不能**依赖参数名是否可用：参数名不可用（未开 -parameters、编译优化、
        // 或方法来自没有调试信息的类）时，正是需要使用 #p0 / #a0 的场景。
        // 原先把它放在 parameterNames != null 分支里，恰好在这个场景下不设置别名。
        for (int index = 0; index < args.length; index++) {
            context.setVariable("p" + index, args[index]);
            context.setVariable("a" + index, args[index]);
        }
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
        if (parameterNames != null) {
            for (int index = 0; index < parameterNames.length && index < args.length; index++) {
                context.setVariable(parameterNames[index], args[index]);
            }
        }
        try {
            return EXPRESSION_PARSER.parseExpression(expression).getValue(context);
        } catch (Exception exception) {
            // 表达式失败一律 fail-closed，不泄露表达式内部细节
            throw new BusinessException(403, "S1 资源表达式无法解析");
        }
    }

    /**
     * 校验注解语义与 B0 冻结的功能范围一致。
     *
     * <p>把源范围功能标成全局功能等于绕过负责源约束，所以这类声明直接拒绝执行，
     * 而不是“按注解写的那样放行”。</p>
     */
    private void requireScope(String functionCode, IamS1FunctionCatalog.FunctionScope expected, String annotation) {
        IamS1FunctionCatalog.FunctionScope actual = IamS1FunctionCatalog.scopeOf(functionCode);
        if (actual == null) {
            throw new BusinessException(500, annotation + " 使用了未知功能码：" + functionCode);
        }
        if (actual == IamS1FunctionCatalog.FunctionScope.MIXED) {
            // 混合码的范围是动态的（关联了哪些源要查业务事实），
            // 用这两个注解声明会把动态范围固化成一个错误的静态结论。
            throw new BusinessException(500,
                    annotation + " 不能用于「源/全」混合功能码：" + functionCode
                            + "，请使用 @IamS1ScopedList 并由 Service 落实动态范围");
        }
        if (actual != expected) {
            throw new BusinessException(500,
                    annotation + " 与功能码范围语义不一致：" + functionCode + " 实际为 " + actual);
        }
    }
}
