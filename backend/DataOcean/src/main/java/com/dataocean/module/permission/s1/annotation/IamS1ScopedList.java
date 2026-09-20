package com.dataocean.module.permission.s1.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 源范围功能的功能级准入：用于**没有单一资源 ID 可解析**的入口。
 *
 * <p>典型场景两类：</p>
 * <ul>
 *   <li><b>列表 / 搜索 / 统计</b>（如 {@code GET /api/admin/datasources}、
 *       {@code GET /api/admin/catalog/search}、{@code GET /api/admin/metadata/snapshots}）：
 *       返回结果必须由 Service 按负责源裁剪；</li>
 *   <li><b>无现成资源 ID 的创建 / 探测入口</b>（如创建数据源、测试尚未保存的连接）：
 *       目标资源还不存在，没有归属可解析。</li>
 * </ul>
 *
 * <p>切面只做一件事：确认调用者在启用绑定上拥有该功能。**它不解析资源、也不裁剪数据**——
 * 可见数据源必须由 Service 调用
 * {@code IamS1CapabilityService.responsibleDatasourcesWithFunction()} 取得，
 * 并**下推到 SQL**（先分页再在内存过滤会让总数和分页边界出错）。</p>
 *
 * <p>与 {@link IamS1Global} 的区别：该功能本身是「源」范围功能，只是入口没有单一资源 ID；
 * 与 {@link IamS1Resource} 的区别：不做单资源归属校验。三种语义必须严格区分。</p>
 *
 * <p>没有该功能时拒绝；有功能但没有同绑定负责源时返回**空范围**，
 * 不能退化成全局查询。</p>
 *
 * <p><b>范围语义</b>：接受 {@code RESOURCE}（「源」）与 {@code MIXED}（「源/全」混合，如
 * {@code glossary:view/manage/approve}）。混合码在“术语关联了数据源”时按源校验、未关联时按全局；
 * 这个动态范围**必须由对应 Service 落实**（切面拿不到业务事实，也不应该去拼业务查询）。
 * {@code GLOBAL} 功能仍然拒绝——它没有“负责源”概念，声明成本注解会让语义名不副实。</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IamS1ScopedList {

    /** 固定 54 码目录中的功能码，且必须被定义为「源」或已定稿的「源/全」混合功能。 */
    String value();
}
