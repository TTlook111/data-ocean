package com.dataocean.common.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Python AI 服务 RestClient 统一配置。
 */
@Configuration
public class PythonRestClientConfig {

    @Value("${dataocean.python-service.base-url:http://localhost:8000}")
    private String pythonBaseUrl;

    @Value("${dataocean.python-service.connect-timeout:5000}")
    private int connectTimeout;

    @Value("${dataocean.python-service.read-timeout:120000}")
    private int readTimeout;

    @Value("${dataocean.python-service.short-connect-timeout:3000}")
    private int shortConnectTimeout;

    @Value("${dataocean.python-service.short-read-timeout:15000}")
    private int shortReadTimeout;

    @Bean
    @Qualifier("pythonRestClient")
    @Primary
    public RestClient pythonRestClient() {
        return buildClient(connectTimeout, readTimeout);
    }

    @Bean
    @Qualifier("pythonShortTimeoutRestClient")
    public RestClient pythonShortTimeoutRestClient() {
        return buildClient(shortConnectTimeout, shortReadTimeout);
    }

    @Bean
    @Qualifier("pythonHealthRestClient")
    public RestClient pythonHealthRestClient(
            @Value("${dataocean.python-service.health-connect-timeout:3000}") int healthConnectTimeout,
            @Value("${dataocean.python-service.health-read-timeout:3000}") int healthReadTimeout) {
        return buildClient(healthConnectTimeout, healthReadTimeout);
    }

    private RestClient buildClient(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(pythonBaseUrl)
                .build();
    }
}
