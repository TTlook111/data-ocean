package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.IamPrincipal;
import com.dataocean.module.iam.service.StreamSessionPermission;
import com.dataocean.module.query.controller.QueryController;
import com.dataocean.module.query.service.QueryService.Progress;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 通过真实 MVC SSE 响应验证说明修订和重新核权。
 */
class QueryStreamTest {

    /**
     * 清除测试会话主体，避免影响同线程执行的其他用例。
     */
    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 重复快照只发一次，说明完成或失权时收束原连接。
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sendsConfirmedExplanationOnlyWhileSessionRemainsAuthorized(boolean revoked) throws Exception {
        var query = mock(QueryService.class);
        var access = mock(StreamSessionPermission.class);
        var stream = new QueryStream(query, access);
        var route = MockMvcBuilders.standaloneSetup(new QueryController(query, mock(QueryExplanations.class), stream))
                .build();

        var session = new MockHttpSession();
        var actor = new IamPrincipal(7L, 1L, "review", "测试会话");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null));

        var now = Instant.parse("2026-11-26T10:00:00Z");
        var queued = new Progress("request", "RESULT_DELIVERED", "SUCCEEDED", false, now, 1, 2, "QUEUED", null);
        var completed = new Progress(
                "request", "RESULT_DELIVERED", "SUCCEEDED", false, now.plusMillis(1), 1, 2, "COMPLETED", "已核验结果");
        when(query.progress("request", 7)).thenReturn(queued, queued, completed);
        var dispatched = route.perform(
                        get("/api/v1/query/requests/request/events").session(session))
                .andExpect(request().asyncStarted())
                .andReturn();

        stream.tick();
        if (revoked) {
            // 会话撤权发生在下一次发布前，已完成详情也不能再传至原连接。
            doThrow(BizIllegalException.denied()).when(access).require(session.getId(), actor, "query.view");
        }
        stream.tick();
        stream.tick();

        var response = route.perform(asyncDispatch(dispatched))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        String text = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).contains("event:query.updated");
        assertThat(text).containsOnlyOnce("\"explanationStatus\":\"QUEUED\"");
        if (revoked) {
            // 失权后关闭连接，阻断状态和解释的后续查询。
            assertThat(text).doesNotContain("COMPLETED", "已核验结果");
        } else {
            // 当前仍有权时，同一修订交付完整正文。
            assertThat(text).contains("COMPLETED", "已核验结果");
        }
        verify(query, times(revoked ? 2 : 3)).progress("request", 7);
        verify(access, times(3)).require(session.getId(), actor, "query.view");
    }
}
