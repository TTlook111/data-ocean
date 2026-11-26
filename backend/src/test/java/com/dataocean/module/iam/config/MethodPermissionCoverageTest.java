package com.dataocean.module.iam.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * 验证实际 MVC 注册的业务接口必须包含有效的权限声明。
 */
class MethodPermissionCoverageTest {

    /**
     * 新增接口漏写注解时，在应用接收请求前拒绝启动。
     */
    @Test
    void refusesToExposeAnUndeclaredEndpoint() {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            // 使用真实的注册器，确保路由漏标注时也进入校验。
            context.setServletContext(new MockServletContext());
            context.register(CoverageConfiguration.class);
            assertThatThrownBy(context::refresh)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("接口缺少可执行的权限声明")
                    .hasMessageContaining("UndeclaredController");
        }
    }

    /**
     * 仅装配真实 MVC 与授权基础设施，避免引入运行库依赖。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({MethodPermissionConfiguration.class, UndeclaredController.class})
    static class CoverageConfiguration {}

    /**
     * 模拟未来新增但遗漏权限声明的应用控制器。
     */
    @RestController
    static class UndeclaredController {

        /**
         * 模拟一条应当在启动时被拒绝的业务路由。
         */
        @GetMapping("/api/v1/test/undeclared")
        public String undeclared() {
            return "未声明";
        }
    }
}
