package com.dataocean.module.datasource.client.impl;

import com.dataocean.module.datasource.client.PythonPoolClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Python AI 服务连接池管理客户端实现类。
 * <p>
 * 通过 HTTP 请求通知 Python 服务执行连接池查看/重置/销毁操作。
 * 调用失败时仅记录警告日志，不抛出异常，保证主业务流程不受影响。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PythonPoolClientImpl implements PythonPoolClient {

    private final RestClient.Builder restClientBuilder;

    /** Python AI 服务的基础 URL */
    @Value("${dataocean.python-service.base-url:http://localhost:8000}")
    private String pythonServiceBaseUrl;

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getPoolDashboard() {
        try {
            Map<String, Object> body = restClientBuilder.build()
                    .get()
                    .uri(pythonServiceBaseUrl + "/internal/sql/pools/dashboard")
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                return emptyDashboard();
            }
            return body;
        } catch (Exception exception) {
            log.warn("获取 Python 连接池仪表盘失败: {}", exception.getMessage());
            Map<String, Object> dashboard = emptyDashboard();
            dashboard.put("error", exception.getMessage());
            return dashboard;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resetPool(Long datasourceId) {
        try {
            restClientBuilder.build()
                    .post()
                    .uri(pythonServiceBaseUrl + "/internal/sql/pools/{datasourceId}/reset", datasourceId)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) ->
                            log.warn("Python 连接池重置接口返回异常 datasourceId={} status={}",
                                    datasourceId, response.getStatusCode()))
                    .toBodilessEntity();
            log.info("已通知 Python 服务重置数据源连接池 datasourceId={}", datasourceId);
        } catch (Exception exception) {
            log.warn("通知 Python 服务重置连接池失败 datasourceId={} reason={}", datasourceId, exception.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroyPool(Long datasourceId) {
        try {
            restClientBuilder.build()
                    .delete()
                    .uri(pythonServiceBaseUrl + "/internal/sql/pools/{datasourceId}", datasourceId)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) ->
                            log.warn("Python 连接池销毁接口返回异常 datasourceId={} status={}",
                                    datasourceId, response.getStatusCode()))
                    .toBodilessEntity();
            log.info("已通知 Python 服务销毁数据源连接池 datasourceId={}", datasourceId);
        } catch (Exception exception) {
            log.warn("通知 Python 服务销毁连接池失败 datasourceId={} reason={}", datasourceId, exception.getMessage());
        }
    }

    /** 构建空的仪表盘响应 */
    private Map<String, Object> emptyDashboard() {
        return new java.util.HashMap<>(Map.of(
                "activePools", 0,
                "pools", List.of()
        ));
    }
}
