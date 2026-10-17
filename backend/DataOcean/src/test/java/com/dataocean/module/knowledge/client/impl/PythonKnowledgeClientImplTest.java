package com.dataocean.module.knowledge.client.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.client.PythonKnowledgeClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PythonKnowledgeClientImplTest {
    @Configuration
    @EnableRetry(proxyTargetClass = true)
    static class Config {
        @Bean RestClient.Builder builder() { return RestClient.builder().baseUrl("http://knowledge.test"); }
        @Bean MockRestServiceServer server(RestClient.Builder builder) { return MockRestServiceServer.bindTo(builder).build(); }
        @Bean PythonKnowledgeClient client(RestClient.Builder builder, MockRestServiceServer server) {
            return new PythonKnowledgeClientImpl(builder.build());
        }
    }

    @Test void billingFailureKeepsBusinessReasonThroughRealRetryProxyAndCallsOnce() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var server = context.getBean(MockRestServiceServer.class);
            server.expect(requestTo("http://knowledge.test/internal/knowledge/supplement-semantics"))
                    .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":\"AI_BILLING_REQUIRED\"}"));
            var error = assertThrows(BusinessException.class,
                    () -> context.getBean(PythonKnowledgeClient.class).supplementSemantics(Map.of()));
            assertTrue(error.getMessage().contains("欠费"));
            assertTrue(error.getMessage().contains("原有目录与人工内容已保留"));
            server.verify();
        }
    }

    @Test void invalidOutputDoesNotRetryOrExposeProviderBody() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var server = context.getBean(MockRestServiceServer.class);
            server.expect(requestTo("http://knowledge.test/internal/knowledge/supplement-semantics"))
                    .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":\"AI_INVALID_OUTPUT\",\"message\":\"private provider detail\"}"));
            var error = assertThrows(BusinessException.class,
                    () -> context.getBean(PythonKnowledgeClient.class).supplementSemantics(Map.of()));
            assertTrue(error.getMessage().contains("字段依据无效"));
            assertFalse(error.getMessage().contains("private provider detail"));
            server.verify();
        }
    }
}
