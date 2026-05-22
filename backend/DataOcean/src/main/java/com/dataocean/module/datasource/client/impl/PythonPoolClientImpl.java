package com.dataocean.module.datasource.client.impl;

import com.dataocean.module.datasource.client.PythonPoolClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Python AI 服务连接池管理客户端实现类
 * <p>
 * 通过 HTTP DELETE 请求通知 Python 服务销毁指定数据源的连接池。
 * 调用失败时仅记录警告日志，不抛出异常，保证主业务流程不受影响。
 * </p>
 *
 * @author dataocean
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
    public void destroyPool(Long datasourceId) {
        try {
            // 发送 DELETE 请求通知 Python 服务销毁连接池
            restClientBuilder.build()
                    .delete()
                    .uri(pythonServiceBaseUrl + "/internal/sql/pools/{datasourceId}", datasourceId)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) ->
                            log.warn("Python 连接池销毁接口返回异常 datasourceId={} status={}", datasourceId, response.getStatusCode()))
                    .toBodilessEntity();
            log.info("已通知 Python 服务销毁数据源连接池 datasourceId={}", datasourceId);
        } catch (Exception exception) {
            // 通知失败不影响主流程，仅记录警告
            log.warn("通知 Python 服务销毁连接池失败 datasourceId={} reason={}", datasourceId, exception.getMessage());
        }
    }
}
