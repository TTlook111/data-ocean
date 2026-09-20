package com.dataocean.module.permission.s1.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 全局功能准入：只校验调用者在启用绑定上拥有该功能码，不涉及负责源。
 *
 * <p>**只允许用于 B0 冻结为「全」的功能**（{@code IamS1FunctionCatalog.FunctionScope#GLOBAL}）。
 * 把 {@code datasource:view}、{@code metadata:view} 这类源范围功能错标成全局功能，
 * 等于绕过负责源约束——切面会直接拒绝这种声明。</p>
 *
 * <p>只能标注在方法上：一个 Controller 内通常同时存在 view / manage / review / publish
 * 等不同功能码，类级注解容易一次性过度授权。</p>
 *
 * <p>注解只做准入声明。列表字段、数据范围和业务规则仍由 Service 负责。</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IamS1Global {

    /** 固定 54 码目录中的功能码。 */
    String value();
}
