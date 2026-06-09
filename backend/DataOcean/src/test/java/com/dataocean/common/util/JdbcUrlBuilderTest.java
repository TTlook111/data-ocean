package com.dataocean.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JdbcUrlBuilder 单元测试。
 */
@DisplayName("JdbcUrlBuilder 单元测试")
class JdbcUrlBuilderTest {

    @Test
    @DisplayName("元数据采集 URL 包含正确的超时参数")
    void testMetadataMysqlUrl_timeoutParameters() {
        String url = JdbcUrlBuilder.metadataMysqlUrl("localhost", 3306, "testdb", "utf8");

        assertAll(
                () -> assertTrue(url.contains("connectTimeout=10000"), "应包含 connectTimeout=10000"),
                () -> assertTrue(url.contains("socketTimeout=60000"), "应包含 socketTimeout=60000"),
                () -> assertTrue(url.startsWith("jdbc:mysql://localhost:3306/testdb?"), "URL 前缀正确")
        );
    }

    @Test
    @DisplayName("元数据采集 URL 包含基本安全参数")
    void testMetadataMysqlUrl_securityParameters() {
        String url = JdbcUrlBuilder.metadataMysqlUrl("localhost", 3306, "testdb", "utf8");

        assertAll(
                () -> assertTrue(url.contains("useSSL=false"), "应包含 useSSL=false"),
                () -> assertTrue(url.contains("allowPublicKeyRetrieval=true"), "应包含 allowPublicKeyRetrieval=true")
        );
    }

    @Test
    @DisplayName("连接测试 URL 包含快速失败超时参数")
    void testConnectionTestMysqlUrl_timeoutParameters() {
        String url = JdbcUrlBuilder.connectionTestMysqlUrl("localhost", 3306, "testdb", "utf8");

        assertAll(
                () -> assertTrue(url.contains("connectTimeout=5000"), "应包含 connectTimeout=5000"),
                () -> assertTrue(url.contains("socketTimeout=5000"), "应包含 socketTimeout=5000")
        );
    }

    @Test
    @DisplayName("连接测试 URL 包含安全加固参数")
    void testConnectionTestMysqlUrl_securityHardening() {
        String url = JdbcUrlBuilder.connectionTestMysqlUrl("localhost", 3306, "testdb", "utf8");

        assertAll(
                () -> assertTrue(url.contains("allowLoadLocalInfile=false"), "应禁止 LOAD DATA LOCAL INFILE"),
                () -> assertTrue(url.contains("autoDeserialize=false"), "应禁止自动反序列化"),
                () -> assertTrue(url.contains("allowUrlInLocalInfile=false"), "应禁止 URL INFILE")
        );
    }

    @Test
    @DisplayName("连接测试 URL 包含时区参数")
    void testConnectionTestMysqlUrl_timezone() {
        String url = JdbcUrlBuilder.connectionTestMysqlUrl("localhost", 3306, "testdb", "utf8");

        assertTrue(url.contains("serverTimezone=Asia/Shanghai"), "应包含时区设置");
    }

    @Test
    @DisplayName("utf8mb4 应映射为 utf8")
    void testResolveJdbcEncoding_utf8mb4() {
        assertEquals("utf8", JdbcUrlBuilder.resolveJdbcEncoding("utf8mb4"));
        assertEquals("utf8", JdbcUrlBuilder.resolveJdbcEncoding("UTF8MB4"));
    }

    @Test
    @DisplayName("utf8 应保持不变")
    void testResolveJdbcEncoding_utf8() {
        assertEquals("utf8", JdbcUrlBuilder.resolveJdbcEncoding("utf8"));
    }

    @Test
    @DisplayName("其他编码应保持不变")
    void testResolveJdbcEncoding_other() {
        assertEquals("gbk", JdbcUrlBuilder.resolveJdbcEncoding("gbk"));
        assertEquals("latin1", JdbcUrlBuilder.resolveJdbcEncoding("latin1"));
    }

    @Test
    @DisplayName("null 编码应返回 utf8")
    void testResolveJdbcEncoding_null() {
        assertEquals("utf8", JdbcUrlBuilder.resolveJdbcEncoding(null));
    }

    @Test
    @DisplayName("空字符串编码应返回 utf8")
    void testResolveJdbcEncoding_blank() {
        assertEquals("utf8", JdbcUrlBuilder.resolveJdbcEncoding(""));
        assertEquals("utf8", JdbcUrlBuilder.resolveJdbcEncoding("  "));
    }

    @Test
    @DisplayName("URL 中应包含正确的字符编码")
    void testMetadataMysqlUrl_charset() {
        String url = JdbcUrlBuilder.metadataMysqlUrl("localhost", 3306, "testdb", "utf8mb4");

        assertTrue(url.contains("characterEncoding=utf8"), "utf8mb4 应映射为 utf8");
    }
}
