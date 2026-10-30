package com.dataocean.module.permission.s1.annotation;

import com.dataocean.module.permission.s1.resource.IamS1ResourceType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 资源范围功能准入：解析资源真实归属，并在同一条启用绑定上同时校验功能与负责源。
 *
 * <pre>
 * // 单资源
 * &#64;IamS1Resource(function = "datasource:manage",
 *              resourceType = IamS1ResourceType.DATASOURCE,
 *              resourceIds = "#id")
 *
 * // 双侧资源：逐个解析校验，任意一个无权则整体拒绝
 * &#64;IamS1Resource(function = "metadata:release:view",
 *              resourceType = IamS1ResourceType.SNAPSHOT,
 *              resourceIds = {"#oldId", "#newId"})
 * </pre>
 *
 * <p>`resourceIds` 是受限的只读 SpEL，只能读取方法参数与 DTO 普通属性
 * （如 `#snapshotId`、`#request.datasourceId`）。表达式**只负责提取资源 ID**，
 * 真实归属由资源解析器重新查询，绝不采信客户端同时传来的 datasourceId。</p>
 *
 * <p>参数为空、表达式失败、结果类型错误、资源不存在或归属断链，一律 fail-closed。</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IamS1Resource {

    /** 固定 54 码目录中的功能码，且必须被定义为「源」范围功能。 */
    String function();

    /** 资源类型，决定使用哪个固定解析器。 */
    IamS1ResourceType resourceType();

    /** 一个或多个受限 SpEL 表达式；每个都解析并校验。 */
    String[] resourceIds();
}
