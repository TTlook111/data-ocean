package com.dataocean.common.cancel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PostConstruct;

/**
 * 查询取消服务
 * <p>
 * 通过 HTTP 调用 Python 内部取消 API，通知 Agent 停止执行并释放资源。
 * 独立于 PythonAgentClient，可被 SSE 断开检测、定时清理等场景复用。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueryCancelService {

    @Value("${dataocean.python-service.base-url:http://localhost:8000}")
    private String pythonServiceBaseUrl;

    private static final int CANCEL_TIMEOUT_MS = 5_000;

    private RestClient cancelClient;

    /**
     * 初始化取消专用 RestClient（短超时）
     */
    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CANCEL_TIMEOUT_MS);
        factory.setReadTimeout(CANCEL_TIMEOUT_MS);
        this.cancelClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(pythonServiceBaseUrl)
                .build();
    }

    /**
     * 通知 Python 取消指定任务
     * <p>
     * 调用失败不抛异常，仅记录警告日志。
     * 取消是尽力而为的操作，不影响 Java 侧状态更新。
     * </p>
     *
     * @param taskId 任务 ID
     */
    public void cancelTask(String taskId) {
        try {
            cancelClient.post()
                    .uri("/internal/query/tasks/{taskId}/cancel", taskId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("已通知 Python 取消任务 taskId={}", taskId);
        } catch (Exception e) {
            log.warn("通知 Python 取消任务失败 taskId={} reason={}", taskId, e.getMessage());
        }
    }
}
