package com.dataocean.module.datasource.client.impl;

import com.dataocean.module.datasource.client.PythonPoolClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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

    @Qualifier("pythonRestClient")
    private final RestClient restClient;

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getPoolDashboard() {
        try {
            Map<String, Object> body = restClient
                    .get()
                    .uri("/internal/sql/pools/dashboard")
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
        executePoolOperation("重置", datasourceId, () ->
                restClient
                        .post()
                        .uri("/internal/sql/pools/{datasourceId}/reset", datasourceId)
                        .retrieve()
                        .toBodilessEntity());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroyPool(Long datasourceId) {
        executePoolOperation("销毁", datasourceId, () ->
                restClient
                        .delete()
                        .uri("/internal/sql/pools/{datasourceId}", datasourceId)
                        .retrieve()
                        .toBodilessEntity());
    }

    /**
     * 执行连接池操作的通用模板方法。
     * <p>
     * 统一处理成功/失败日志，避免重复代码。
     * HTTP 错误时记录警告而非成功日志。
     * </p>
     *
     * @param operation    操作名称（如"重置"、"销毁"）
     * @param datasourceId 数据源 ID
     * @param action       实际的 HTTP 调用
     */
    private void executePoolOperation(String operation, Long datasourceId, Runnable action) {
        try {
            action.run();
            log.info("已通知 Python 服务{}数据源连接池 datasourceId={}", operation, datasourceId);
        } catch (Exception exception) {
            log.warn("通知 Python 服务{}连接池失败 datasourceId={} reason={}",
                    operation, datasourceId, exception.getMessage());
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
