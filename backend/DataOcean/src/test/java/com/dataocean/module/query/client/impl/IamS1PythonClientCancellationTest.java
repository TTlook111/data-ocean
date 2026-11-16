package com.dataocean.module.query.client.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 真实客户端配合内存 HTTP 替身，不建立网络连接，也不启动 Python 或数据库。 */
class IamS1PythonClientCancellationTest {
    private static final String CANCEL_URL = "http://python.test/internal/iam-s1/query/tasks/task-1/cancel";

    private MockRestServiceServer server;
    private IamS1PythonClientImpl client;

    @BeforeEach
    void setUp() {
        // MockRestServiceServer 拦截全部请求，测试只检查客户端是否如实上报交付失败。
        RestClient.Builder builder = RestClient.builder().baseUrl("http://python.test");
        server = MockRestServiceServer.bindTo(builder).build();
        // 取消使用第二个短超时入口；测试将两类客户端绑定到同一内存请求替身。
        RestClient restClient = builder.build();
        client = new IamS1PythonClientImpl(new ObjectMapper(), restClient, restClient);
    }

    @AfterEach
    void verifyRequestWasSent() {
        // 确认每例都走到真实客户端请求路径，避免空桩产生假通过。
        server.verify();
    }

    @Test
    void cancellationHttpFailureMustBeObservableByTheCaller() {
        // 远端明确拒绝时，调用方必须能保留待重试意图，不能得到无异常的成功返回。
        server.expect(requestTo(CANCEL_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.cancelTask("task-1")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void cancellationTimeoutMustRemainAnUnknownOutcome() {
        // 超时无法证明请求是否到达，暴露失败供上层核对，而不是宣称执行已停止。
        server.expect(requestTo(CANCEL_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withException(new SocketTimeoutException("模拟取消请求超时")));

        assertThatThrownBy(() -> client.cancelTask("task-1")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void acceptedCancellationDoesNotRequireAnExecutionResult() {
        // Python 当前回执仅确认接收标记；本例不将回执等同于执行或 SQL 已停止。
        server.expect(requestTo(CANCEL_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"taskId\":\"task-1\",\"protocolVersion\":\"IAM-SIMPLE-1\",\"cancelled\":true}",
                        MediaType.APPLICATION_JSON));

        assertThatCode(() -> client.cancelTask("task-1")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"taskId\":\"other-task\",\"protocolVersion\":\"IAM-SIMPLE-1\",\"cancelled\":true}",
            "{\"taskId\":\"task-1\",\"protocolVersion\":\"IAM-SIMPLE-1\",\"cancelled\":false}"})
    void successfulHttpWithoutAnExactAcceptanceReceiptRemainsPending(String receipt) {
        // 错任务、残缺或拒绝回执都不能把本任务的持久意图标成已接收。
        server.expect(requestTo(CANCEL_URL)).andRespond(withSuccess(receipt, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.cancelTask("task-1")).isInstanceOf(IllegalStateException.class);
    }
}
