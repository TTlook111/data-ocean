package com.dataocean.module.query.client.impl;

import com.dataocean.module.query.client.PythonAgentClient;
import com.dataocean.module.query.service.ConversationService;
import com.dataocean.module.query.service.QueryTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Python Agent 服务客户端实现。
 * <p>
 * 通过 HTTP POST 调用 Python /internal/query/execute，
 * 消费 SSE 事件流，提取最终 result/error 事件后回写到 query_task 表。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PythonAgentClientImpl implements PythonAgentClient {

    @Value("${dataocean.python-service.base-url:http://localhost:8000}")
    private String pythonServiceBaseUrl;

    private static final int TIMEOUT_MS = 120_000;

    private final QueryTaskService queryTaskService;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;

    private RestClient restClient;

    /**
     * 初始化 RestClient，配置超时。
     */
    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(pythonServiceBaseUrl)
                .build();
        log.info("PythonAgentClient 初始化完成 baseUrl={}", pythonServiceBaseUrl);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 使用 @Async 在独立线程中执行，不阻塞主请求。
     * 消费 Python SSE 流，提取最终 result/error 事件回写数据库。
     * </p>
     */
    @Async
    @Override
    public void executeAsync(String taskId, Long datasourceId, Long userId, String question,
                             Long conversationId, Long activeSnapshotId) {
        log.info("触发 Agent 执行 taskId={} datasourceId={}", taskId, datasourceId);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("taskId", taskId);
        requestBody.put("datasourceId", datasourceId);
        requestBody.put("userId", userId);
        requestBody.put("question", question);
        requestBody.put("activeSnapshotId", activeSnapshotId);
        requestBody.put("userPermissions", Map.of("allowedTables", List.of(), "rowFilters", List.of(),
                "deniedColumns", List.of(), "maskColumns", List.of()));
        requestBody.put("conversationHistory", List.of());

        try {
            // 调用 Python SSE 接口并消费流
            InputStream stream = restClient.post()
                    .uri("/internal/query/execute")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(InputStream.class);

            if (stream == null) {
                log.error("Python Agent 返回空响应 taskId={}", taskId);
                queryTaskService.updateTaskResult(taskId, "{\"status\":\"FAILED\",\"error\":\"Agent 服务无响应\"}");
                return;
            }

            // 逐行读取 SSE 事件，提取最终结果
            String finalResult = consumeSseStream(stream, taskId);

            // 回写任务结果
            if (finalResult != null) {
                queryTaskService.updateTaskResult(taskId, finalResult);
                // 保存助手消息到会话
                saveAssistantMessageFromResult(conversationId, taskId, finalResult);
            } else {
                queryTaskService.updateTaskResult(taskId, "{\"status\":\"FAILED\",\"error\":\"Agent 未返回最终结果\"}");
            }

        } catch (Exception e) {
            log.error("Agent 执行失败 taskId={} error={}", taskId, e.getMessage(), e);
            try {
                queryTaskService.updateTaskResult(taskId,
                        "{\"status\":\"FAILED\",\"error\":\"Agent 服务调用失败：" + e.getMessage() + "\"}");
            } catch (Exception ex) {
                log.error("回写失败状态异常 taskId={}", taskId, ex);
            }
        }
    }

    /**
     * 消费 SSE 事件流，返回最终 result/error 事件的 data 内容。
     */
    private String consumeSseStream(InputStream stream, String taskId) {
        String lastResultData = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            String currentEventType = null;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    currentEventType = line.substring(6).trim();
                } else if (line.startsWith("data:") && currentEventType != null) {
                    String data = line.substring(5).trim();
                    if ("result".equals(currentEventType) || "error".equals(currentEventType)) {
                        lastResultData = data;
                    }
                }
            }
        } catch (Exception e) {
            log.error("SSE 流读取异常 taskId={}", taskId, e);
        }
        return lastResultData;
    }

    /**
     * 从 Agent 结果中提取口径说明，保存为助手消息。
     */
    private void saveAssistantMessageFromResult(Long conversationId, String taskId, String resultJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);
            String status = (String) result.getOrDefault("status", "FAILED");
            String content;
            if ("COMPLETED".equals(status)) {
                content = (String) result.getOrDefault("sqlExplanation", "查询完成");
            } else {
                content = (String) result.getOrDefault("error", "查询失败");
            }
            conversationService.saveAssistantMessage(conversationId, content, taskId, resultJson);
        } catch (Exception e) {
            log.warn("保存助手消息失败 taskId={}", taskId, e);
        }
    }
}
