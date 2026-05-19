package com.dataocean.module.datasource.service.impl;

import com.dataocean.module.datasource.service.PythonPoolClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
@Slf4j
public class PythonPoolClientImpl implements PythonPoolClient {

    private final RestClient.Builder restClientBuilder;

    @Value("${dataocean.python-service.base-url:http://localhost:8000}")
    private String pythonServiceBaseUrl;

    @Override
    public void destroyPool(Long datasourceId) {
        try {
            restClientBuilder.build()
                    .delete()
                    .uri(pythonServiceBaseUrl + "/internal/sql/pools/{datasourceId}", datasourceId)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) ->
                            log.warn("Python 连接池销毁接口返回异常 datasourceId={} status={}", datasourceId, response.getStatusCode()))
                    .toBodilessEntity();
            log.info("已通知 Python 服务销毁数据源连接池 datasourceId={}", datasourceId);
        } catch (Exception exception) {
            log.warn("通知 Python 服务销毁连接池失败 datasourceId={} reason={}", datasourceId, exception.getMessage());
        }
    }
}
