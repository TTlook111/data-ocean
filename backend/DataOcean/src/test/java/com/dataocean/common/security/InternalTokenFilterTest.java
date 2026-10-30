package com.dataocean.common.security;

import com.dataocean.module.knowledge.scheduler.VectorIndexTaskScheduler;
import com.dataocean.module.metadata.scheduler.AutoSyncScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /internal/**} 的令牌保护边界。
 * <p>
 * 这里覆盖的不是"过滤器写了什么"，而是"保护是否真的生效"：
 * 之前的实现把校验分散在三个 Controller 里，其中元数据那处完全遗漏了守卫，
 * 而所有测试都是绿的。因此最关键的是最后一个枚举用例——
 * 它遍历实际注册的 handler，逐个断言匿名请求被拒绝。
 * <p>
 * <b>覆盖边界（2026-09-25 反向验证实测）</b>：
 * <ul>
 *   <li>把 {@code SecurityConfig} 的 {@code hasAuthority(...)} 改成 {@code permitAll()} 而过滤器仍在时，
 *       本测试<b>仍然全绿</b>——403 由过滤器给出，授权层的设置未被覆盖。这是一种已知的覆盖缺口，
 *       但该状态下保护依然有效（过滤器仍在拦截），不构成漏洞。</li>
 *   <li>让过滤器不生效（{@code shouldNotFilter} 恒为 true）时，本测试<b>会失败</b>
 *       （期望 403、实际得到授权层返回的 401）。这证明两件事：授权层的 fail-closed 兜底真的在起作用，
 *       且过滤器一旦失效本测试能够察觉。</li>
 * </ul>
 * 即：真正会导致"完全放行"的组合（{@code permitAll} 且过滤器失效）会被本测试捕获；
 * 单独改回 {@code permitAll} 不会被捕获，因为那种状态下并不开放。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:internal_token_auth;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=never"
})
class InternalTokenFilterTest {

    /** 无路径变量的内部端点，用于固定用例 */
    private static final String INTERNAL_PATH = "/internal/ai-config";

    @Autowired
    private MockMvc mockMvc;

    /** 从容器取实际生效的令牌，避免把测试硬编码值写成第二个事实来源 */
    @Autowired
    private InternalTokenValidator validator;

    /**
     * 必须限定名字：容器里还有一个 actuator 的 controllerEndpointHandlerMapping，
     * 按类型注入会因存在两个候选而失败。
     */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @MockBean
    private StringRedisTemplate redisTemplate;

    /** 调度器的初始化会在 H2 上执行真实 SQL，必须替换为测试替身（与既有集成测试一致） */
    @MockBean
    private AutoSyncScheduler autoSyncScheduler;

    @MockBean
    private VectorIndexTaskScheduler vectorIndexTaskScheduler;

    @Test
    void missingTokenIsRejectedWith403() throws Exception {
        mockMvc.perform(get(INTERNAL_PATH))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void wrongTokenIsRejectedWith403() throws Exception {
        mockMvc.perform(get(INTERNAL_PATH)
                        .header(InternalTokenFilter.HEADER_NAME, "definitely-not-the-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void emptyHeaderIsRejectedWith403() throws Exception {
        mockMvc.perform(get(INTERNAL_PATH)
                        .header(InternalTokenFilter.HEADER_NAME, ""))
                .andExpect(status().isForbidden());
    }

    @Test
    void validTokenPassesTheFilter() throws Exception {
        // 只断言"过滤器放行"，不断言 200：
        // 该端点的下游要读 sys_config，而本测试的 H2 库没有建表
        // （Flyway 关闭、spring.sql.init.mode=never），会返回 500。
        // 那个 500 是测试环境产物，与被测的令牌校验无关——
        // 恰恰相反，它证明请求已经越过了过滤器。
        mockMvc.perform(get(INTERNAL_PATH)
                        .header(InternalTokenFilter.HEADER_NAME, validator.token()))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("携带正确令牌时不应被过滤器拒绝")
                        .isNotIn(401, 403));
    }

    @Test
    void nonInternalPathsAreUnaffectedByThisFilter() throws Exception {
        // /api/** 的规则是 authenticated()，匿名访问应得到 401。
        // 若内部过滤器误拦了非内部路径，这里会变成 403。
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void everyInternalHandlerRejectsAnonymousRequests() throws Exception {
        Set<String> internalPatterns = new LinkedHashSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            for (String pattern : entry.getKey().getPatternValues()) {
                if (pattern.startsWith("/internal/")) {
                    internalPatterns.add(pattern);
                }
            }
        }

        // 防止"扫不到就静默通过"：若注册表里找不到内部端点，说明扫描或注册方式已变，
        // 此时用例必须失败，而不是空跑绿灯。
        assertThat(internalPatterns)
                .as("未扫描到任何 /internal 端点，枚举逻辑已失效")
                .isNotEmpty();

        for (String pattern : internalPatterns) {
            String path = pattern.replaceAll("\\{[^}]+}", "1");
            // 令牌过滤器在 DispatcherServlet 之前执行，因此这里不必匹配真实的 HTTP 方法：
            // 无论方法是否对应，具备令牌保护时都应返回 403，缺乏保护时才会落到 404/405。
            mockMvc.perform(get(path))
                    .andExpect(status().isForbidden());
        }
    }
}
