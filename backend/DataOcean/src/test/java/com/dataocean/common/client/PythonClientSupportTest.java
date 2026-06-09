package com.dataocean.common.client;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.exception.PythonRetryableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PythonClientSupport 单元测试。
 */
@DisplayName("PythonClientSupport 单元测试")
class PythonClientSupportTest {

    // ========== statusException 测试 ==========

    @Test
    @DisplayName("5xx 状态码应返回 PythonRetryableException")
    void testStatusException_5xx() {
        RuntimeException ex = PythonClientSupport.statusException(
                HttpStatusCode.valueOf(500), "测试错误");

        assertAll(
                () -> assertInstanceOf(PythonRetryableException.class, ex, "5xx 应返回可重试异常"),
                () -> assertTrue(ex.getMessage().contains("Python 服务返回 500"), "消息应包含状态码"),
                () -> assertTrue(ex.getMessage().contains("测试错误"), "消息应包含原始描述")
        );
    }

    @Test
    @DisplayName("502 状态码应返回 PythonRetryableException")
    void testStatusException_502() {
        RuntimeException ex = PythonClientSupport.statusException(
                HttpStatusCode.valueOf(502), "网关错误");

        assertInstanceOf(PythonRetryableException.class, ex, "502 应返回可重试异常");
    }

    @Test
    @DisplayName("503 状态码应返回 PythonRetryableException")
    void testStatusException_503() {
        RuntimeException ex = PythonClientSupport.statusException(
                HttpStatusCode.valueOf(503), "服务不可用");

        assertInstanceOf(PythonRetryableException.class, ex, "503 应返回可重试异常");
    }

    @Test
    @DisplayName("4xx 状态码应返回 BusinessException")
    void testStatusException_4xx() {
        RuntimeException ex = PythonClientSupport.statusException(
                HttpStatusCode.valueOf(400), "请求错误");

        assertAll(
                () -> assertInstanceOf(BusinessException.class, ex, "4xx 应返回业务异常"),
                () -> assertEquals("请求错误", ex.getMessage(), "消息应保持原样")
        );
    }

    @Test
    @DisplayName("404 状态码应返回 BusinessException")
    void testStatusException_404() {
        RuntimeException ex = PythonClientSupport.statusException(
                HttpStatusCode.valueOf(404), "未找到");

        assertInstanceOf(BusinessException.class, ex, "404 应返回业务异常");
    }

    // ========== isReadTimeout 测试 ==========

    @Test
    @DisplayName("SocketTimeoutException 应识别为读超时")
    void testIsReadTimeout_socketTimeoutException() {
        SocketTimeoutException socketEx = new SocketTimeoutException("Read timed out");
        ResourceAccessException ex = new ResourceAccessException("I/O error", socketEx);

        assertTrue(PythonClientSupport.isReadTimeout(ex), "SocketTimeoutException 应识别为读超时");
    }

    @Test
    @DisplayName("消息中包含 'read timed out' 应识别为读超时")
    void testIsReadTimeout_messageContainsReadTimedOut() {
        ResourceAccessException ex = new ResourceAccessException("Read timed out");

        assertTrue(PythonClientSupport.isReadTimeout(ex), "消息包含 'read timed out' 应识别为读超时");
    }

    @Test
    @DisplayName("消息中包含 'connection timeout' 不应识别为读超时")
    void testIsReadTimeout_messageContainsConnectionTimeout() {
        ResourceAccessException ex = new ResourceAccessException("Connection timeout occurred");

        assertFalse(PythonClientSupport.isReadTimeout(ex), "连接超时不是读超时");
    }

    @Test
    @DisplayName("SocketTimeoutException 中的 connect timed out 不应识别为读超时")
    void testIsReadTimeout_socketConnectTimeoutException() {
        SocketTimeoutException socketEx = new SocketTimeoutException("Connect timed out");
        ResourceAccessException ex = new ResourceAccessException("I/O error", socketEx);

        assertFalse(PythonClientSupport.isReadTimeout(ex), "Socket 连接超时不应识别为读超时");
    }

    @Test
    @DisplayName("ConnectException 不应识别为读超时")
    void testIsReadTimeout_connectException() {
        ConnectException connectEx = new ConnectException("Connection refused");
        ResourceAccessException ex = new ResourceAccessException("I/O error", connectEx);

        assertFalse(PythonClientSupport.isReadTimeout(ex), "ConnectException 不应识别为读超时");
    }

    @Test
    @DisplayName("普通异常不应识别为读超时")
    void testIsReadTimeout_genericException() {
        ResourceAccessException ex = new ResourceAccessException("some error");

        assertFalse(PythonClientSupport.isReadTimeout(ex), "普通异常不应识别为读超时");
    }

    // ========== isConnectFailure 测试 ==========

    @Test
    @DisplayName("ConnectException 应识别为连接失败")
    void testIsConnectFailure_connectException() {
        ConnectException connectEx = new ConnectException("Connection refused");
        ResourceAccessException ex = new ResourceAccessException("I/O error", connectEx);

        assertTrue(PythonClientSupport.isConnectFailure(ex), "ConnectException 应识别为连接失败");
    }

    @Test
    @DisplayName("消息包含 'Connection refused' 不应识别为连接失败（需检查异常链）")
    void testIsConnectFailure_messageNotUsed() {
        ResourceAccessException ex = new ResourceAccessException("Connection refused");

        // isConnectFailure 只检查异常链，不检查消息
        assertFalse(PythonClientSupport.isConnectFailure(ex), "仅消息包含不代表连接失败");
    }

    @Test
    @DisplayName("SocketTimeoutException 不应识别为连接失败")
    void testIsConnectFailure_socketTimeoutException() {
        SocketTimeoutException socketEx = new SocketTimeoutException("Read timed out");
        ResourceAccessException ex = new ResourceAccessException("I/O error", socketEx);

        assertFalse(PythonClientSupport.isConnectFailure(ex), "SocketTimeoutException 不应识别为连接失败");
    }

    @Test
    @DisplayName("普通异常不应识别为连接失败")
    void testIsConnectFailure_genericException() {
        ResourceAccessException ex = new ResourceAccessException("some error");

        assertFalse(PythonClientSupport.isConnectFailure(ex), "普通异常不应识别为连接失败");
    }

    // ========== executeWithDefaultMapping 测试 ==========

    @Test
    @DisplayName("正常调用应返回结果")
    void testExecuteWithDefaultMapping_success() {
        String result = PythonClientSupport.executeWithDefaultMapping(() -> "success", "测试任务");

        assertEquals("success", result);
    }

    @Test
    @DisplayName("读超时应返回 BusinessException")
    void testExecuteWithDefaultMapping_readTimeout() throws IOException {
        SocketTimeoutException socketEx = new SocketTimeoutException("Read timed out");
        ResourceAccessException rae = new ResourceAccessException("I/O error", socketEx);

        assertThrows(BusinessException.class, () -> {
            PythonClientSupport.executeWithDefaultMapping(() -> {
                throw rae;
            }, "测试任务");
        });
    }

    @Test
    @DisplayName("连接失败应返回 PythonRetryableException")
    void testExecuteWithDefaultMapping_connectFailure() throws IOException {
        ConnectException connectEx = new ConnectException("Connection refused");
        ResourceAccessException rae = new ResourceAccessException("I/O error", connectEx);

        assertThrows(PythonRetryableException.class, () -> {
            PythonClientSupport.executeWithDefaultMapping(() -> {
                throw rae;
            }, "测试任务");
        });
    }

    @Test
    @DisplayName("BusinessException 应直接抛出")
    void testExecuteWithDefaultMapping_businessException() {
        BusinessException be = new BusinessException("业务错误");

        assertThrows(BusinessException.class, () -> {
            PythonClientSupport.executeWithDefaultMapping(() -> {
                throw be;
            }, "测试任务");
        });
    }

    @Test
    @DisplayName("其他异常应返回 BusinessException")
    void testExecuteWithDefaultMapping_genericException() {
        assertThrows(BusinessException.class, () -> {
            PythonClientSupport.executeWithDefaultMapping(() -> {
                throw new RuntimeException("未知错误");
            }, "测试任务");
        });
    }
}
