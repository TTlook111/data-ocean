package com.dataocean.module.query.controller;

import com.dataocean.common.security.LoginUser;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.mapper.IamS1PermissionRevisionMapper;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.query.entity.po.QueryTask;
import com.dataocean.module.query.entity.vo.QueryTaskVO;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.query.service.impl.IamS1QueryNotificationServiceImpl;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/** 通过真实 MVC 异步回调复现连接交错，不依赖通知组件内部容器结构。 */
@ExtendWith(MockitoExtension.class)
class IamS1QuerySseControllerTest {
    @Mock private QueryTaskMapper queryTaskMapper;
    @Mock private IamS1PermissionRevisionMapper permissionRevisionMapper;
    @Mock private IamS1AuthorizationResolver authorizationResolver;

    private IamS1QuerySseController controller;
    private IamS1QueryNotificationServiceImpl notifications;
    private MockMvc mvc;
    private QueryTask task;

    @BeforeEach
    void setUp() {
        // 仅替换身份与持久化边界；SSE 使用真实 MVC 处理器，避免测试只验证 Map 操作。
        notifications = new IamS1QueryNotificationServiceImpl();
        controller = new IamS1QuerySseController(queryTaskMapper, permissionRevisionMapper,
                authorizationResolver, notifications);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        LoginUser user = new LoginUser(7L, "test-user", "unused", "测试用户", List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        task = QueryTask.builder().taskId("task-1").userId(7L)
                .iamProtocolVersion("IAM-SIMPLE-1").permissionRevision(100L).build();
        // 拒绝分支允许提前结束，默认桩不要求每种短路路径访问所有权限依赖。
        lenient().when(queryTaskMapper.selectOne(any())).thenReturn(task);
        lenient().when(permissionRevisionMapper.selectCurrentRevision()).thenReturn(100L);
        lenient().when(authorizationResolver.hasGlobalFunction(7L, "query:use")).thenReturn(true);
    }

    @AfterEach
    void clearIdentity() {
        // 认证上下文属于当前测试，不能泄漏到后续权限用例。
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(ConnectionEnd.class)
    void oldConnectionCallbacksDoNotDisconnectNewSubscriber(ConnectionEnd reason) throws Exception {
        // 强制旧连接的回调晚于新连接注册，稳定复现重连时的归属竞争。
        MvcResult first = subscribe();
        MvcResult second = subscribe();
        endConnection(first, (MockAsyncContext) first.getRequest().getAsyncContext(), reason);

        notifications.sendProgress("task-1", payload("PROCESSING"));

        assertThat(second.getResponse().getContentAsString()).contains("event:progress", "PROCESSING");
    }

    @Test
    void bothTabsReceiveProgressAndTheFinalResult() throws Exception {
        // 同一任务可以在两个标签页查看；新订阅不得静默替换旧订阅。
        MvcResult first = subscribe();
        MvcResult second = subscribe();

        notifications.sendProgress("task-1", payload("PROCESSING"));
        notifications.sendResult("task-1", payload("COMPLETED"));

        for (MvcResult result : List.of(first, second)) {
            assertThat(result.getResponse().getContentAsString())
                    .contains("event:progress", "event:result", "COMPLETED");
        }
    }

    @Test
    void completedConnectionCannotRemoveALaterSubscription() throws Exception {
        // 终态发送后的容器完成回调也可能迟到，不能误删之后建立的连接。
        MvcResult completed = subscribe();
        MockAsyncContext completedContext = (MockAsyncContext) completed.getRequest().getAsyncContext();
        notifications.sendResult("task-1", payload("COMPLETED"));
        MvcResult reconnected = subscribe();
        endConnection(completed, completedContext, ConnectionEnd.COMPLETION);

        notifications.sendProgress("task-1", payload("PROCESSING"));

        assertThat(reconnected.getResponse().getContentAsString()).contains("event:progress");
    }

    @Test
    void permissionFailureNotifiesEveryConnectedTabWithoutResultData() throws Exception {
        // 权限读取失败通过原有错误协议通知所有订阅，不沿用先前的结果载荷。
        MvcResult first = subscribe();
        MvcResult second = subscribe();

        notifications.sendError("task-1", "当前无权读取查询结果");

        for (MvcResult result : List.of(first, second)) {
            assertThat(result.getResponse().getContentAsString()).contains("event:error", "FAILED")
                    .doesNotContain("event:result", "\"columns\"", "\"sql\"");
        }
    }

    @ParameterizedTest
    @EnumSource(RejectedSubscription.class)
    void subscriptionStillRequiresTheOriginalSecurityChecks(RejectedSubscription reason) {
        // 通知职责提取不能削弱 Controller 原有的归属、协议、功能与权限修订门禁。
        switch (reason) {
            case MISSING_TASK -> when(queryTaskMapper.selectOne(any())).thenReturn(null);
            case WRONG_PROTOCOL -> task.setIamProtocolVersion("UNSUPPORTED");
            case REVOKED_FUNCTION -> when(authorizationResolver.hasGlobalFunction(7L, "query:use"))
                    .thenReturn(false);
            case CHANGED_PERMISSION -> when(permissionRevisionMapper.selectCurrentRevision()).thenReturn(101L);
        }

        assertThatThrownBy(() -> controller.stream("task-1")).isInstanceOf(BusinessException.class);
    }

    private MvcResult subscribe() throws Exception {
        // 不启动应用端口或数据库，通过 MVC 模拟浏览器建立长连接。
        return mvc.perform(get("/api/iam-s1/query/tasks/task-1/stream"))
                .andExpect(request().asyncStarted()).andReturn();
    }

    private void endConnection(MvcResult result, MockAsyncContext context, ConnectionEnd reason) throws Exception {
        // 容器通知按指定顺序触发，确保竞争条件无需等待超时或依赖线程调度。
        AsyncEvent event = new AsyncEvent(context, result.getRequest(), result.getResponse(),
                reason == ConnectionEnd.ERROR ? new IOException("测试连接已断开") : null);
        for (AsyncListener listener : List.copyOf(context.getListeners())) {
            switch (reason) {
                case COMPLETION -> listener.onComplete(event);
                case TIMEOUT -> listener.onTimeout(event);
                case ERROR -> listener.onError(event);
            }
        }
    }

    private QueryTaskVO payload(String status) {
        // 测试载荷只包含任务身份和状态，不使用真实业务结果或账号数据。
        return QueryTaskVO.builder().taskId("task-1").status(status).build();
    }

    private enum ConnectionEnd { COMPLETION, TIMEOUT, ERROR }
    private enum RejectedSubscription { MISSING_TASK, WRONG_PROTOCOL, REVOKED_FUNCTION, CHANGED_PERMISSION }
}
