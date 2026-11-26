package com.dataocean.module.audit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dataocean.common.exception.GlobalExceptionHandler;
import com.dataocean.common.security.IamPrincipal;
import com.dataocean.module.audit.entity.query.AuditQuery;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.iam.config.MethodPermissionConfiguration;
import com.dataocean.module.iam.entity.po.PermissionPO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.PermissionMapper;
import com.dataocean.module.iam.mapper.RoleAssignmentMapper;
import com.dataocean.module.iam.mapper.RoleMapper;
import com.dataocean.module.iam.service.impl.PermissionServiceImpl;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * 通过实际 MVC 路由、方法鉴权和权限服务验证审计接口的放行与拒绝契约。
 */
@SpringJUnitWebConfig(AuditPermissionTest.MvcConfiguration.class)
@MockitoBean(types = {GuardMapper.class, RoleAssignmentMapper.class, RoleMapper.class})
class AuditPermissionTest {

    /**
     * 承载正式控制器代理与异常处理器的测试上下文。
     */
    @Autowired
    private WebApplicationContext context;

    /**
     * 模拟审计查询服务，观察未授权请求是否进入业务查询。
     */
    @MockitoBean
    private AuditService audits;

    /**
     * 提供当前用户权限及目录记录，判断仍由正式权限服务完成。
     */
    @MockitoBean
    private PermissionMapper permissions;

    /**
     * 执行真实的 MVC 参数绑定、方法调用与 JSON 响应渲染。
     */
    private MockMvc mvc;

    /**
     * 构造身份过滤器完成登录核验后交给接口的可信身份及功能目录。
     */
    @BeforeEach
    void prepareRequestIdentity() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        var principal = new IamPrincipal(17L, 1L, "audit-test", "审计测试人员");
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        SecurityContextHolder.setContext(securityContext);

        var permission = new PermissionPO();
        permission.setCode("audit.view");
        permission.setEnabled(true);
        when(permissions.selectById("audit.view")).thenReturn(permission);
    }

    /**
     * 清理可信身份，避免影响同一线程后续请求测试。
     */
    @AfterEach
    void clearRequestIdentity() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 有审计查看权时，列表和详情都正常调用审计查询并返回成功。
     */
    @Test
    void allowsBothEndpointsWithAuditPermission() throws Exception {
        when(permissions.used(17L)).thenReturn(List.of("audit.view"));

        mvc.perform(get("/api/v1/iam/audit-records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/api/v1/iam/audit-records/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(audits).list(any(AuditQuery.class));
        verify(audits).detail(42L);
    }

    /**
     * 登录但缺少审计查看权时，两个入口返回原有 403 响应且不读取审计。
     */
    @Test
    void rejectsBothEndpointsWithOriginalForbiddenResponse() throws Exception {
        when(permissions.used(17L)).thenReturn(List.of("roles.view"));

        for (String path : List.of("/api/v1/iam/audit-records", "/api/v1/iam/audit-records/42")) {
            // 拒绝保留原有 HTTP 状态、业务码、提示和空数据。
            mvc.perform(get(path))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403003))
                    .andExpect(jsonPath("$.message").value("当前没有执行此操作的权限"))
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        verifyNoInteractions(audits);
    }

    /**
     * 组装审计 MVC 入口与真实权限服务，存储依赖由测试替身提供。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({
        AuditController.class,
        MethodPermissionConfiguration.class,
        PermissionServiceImpl.class,
        GlobalExceptionHandler.class
    })
    static class MvcConfiguration {}
}
