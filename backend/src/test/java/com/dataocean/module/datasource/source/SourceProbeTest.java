package com.dataocean.module.datasource.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 防止连接失败原因合并，或把未执行的后续测试写为已验证。
 */
class SourceProbeTest {

    /**
     * 分项报告区分连接故障和账号故障，且隐去驱动提供的内部消息。
     */
    @ParameterizedTest
    @MethodSource("connectionFailures")
    void stopsAtFailedConnectionStage(SQLException error, String failedItem, String messagePart) throws SQLException {
        var connection = new SourceConnection(
                SourceConnection.Type.POSTGRESQL, "127.0.0.1", 5433, "fixture", List.of("sales"), "reader", 2);
        var jdbc = mock(SourceJdbc.class);
        when(jdbc.open(eq(connection), anyString(), anyString())).thenThrow(error);

        var report = new SourceProbe(jdbc).check(connection, "synthetic");

        assertThat(report.canEnable()).isFalse();
        assertThat(report.items())
                .filteredOn(item -> "FAIL".equals(item.status()))
                .singleElement()
                .satisfies(item -> {
                    // 公共结果只展示受控的分类和提示。
                    assertThat(item.code()).isEqualTo(failedItem);
                    assertThat(item.message()).contains(messagePart).doesNotContain("internal");
                });
        assertThat(report.items())
                .filteredOn(item -> "ENUMERATION".equals(item.code()))
                .extracting(SourceProbe.Item::status)
                .containsExactly("UNVERIFIED");
    }

    /**
     * 模拟驱动包装后的类型，覆盖包在 TLS 握手错误里的超时。
     */
    private static Stream<Arguments> connectionFailures() {
        var handshakeTimeout = new SSLException("internal", new SocketTimeoutException("internal"));
        return Stream.of(
                Arguments.of(new SQLException("internal", "DO002", new UnknownHostException()), "NETWORK", "无法解析"),
                Arguments.of(new SQLException("internal", "08001", handshakeTimeout), "NETWORK", "超时"),
                Arguments.of(
                        new SQLException("internal", "08001", new SSLHandshakeException("internal")), "NETWORK", "证书"),
                Arguments.of(new SQLException("internal", "28P01"), "AUTHENTICATION", "认证失败"));
    }
}
