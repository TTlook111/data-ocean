package com.dataocean.module.query.client.impl;

import com.dataocean.module.query.client.IamS1PythonClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** 不接受旧 Agent 合同，不把 SSE 失败包装成成功。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IamS1PythonClientImpl implements IamS1PythonClient {

    private final ObjectMapper objectMapper;
    @Qualifier("pythonRestClient")
    private final RestClient restClient;

    @Async("queryExecutor")
    @Override
    public void executeAsync(String taskId, Map<String, Object> request, Consumer<String> resultConsumer) {
        try {
            String result = restClient.post()
                    .uri("/internal/iam-s1/query/execute")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((req, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new IllegalStateException("IAM-SIMPLE-1 Python HTTP 状态异常");
                        }
                        try {
                            return consume(response.getBody(), taskId);
                        } catch (Exception ex) {
                            throw new IllegalStateException("IAM-SIMPLE-1 SSE 读取失败", ex);
                        }
                    });
            resultConsumer.accept(result == null
                    ? failed(taskId, "S1 Agent 未返回最终结果") : result);
        } catch (Exception ex) {
            log.error("IAM-SIMPLE-1 Agent 执行失败 taskId={}", taskId, ex);
            resultConsumer.accept(failed(taskId, "S1 Agent 服务调用失败，请稍后重试"));
        }
    }

    private String consume(InputStream stream, String taskId) throws Exception {
        String event = null;
        StringBuilder data = new StringBuilder();
        String finalData = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if ("result".equals(event) && data.length() > 0) {
                        finalData = data.toString().trim();
                    }
                    event = null;
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    String item = line.substring(5);
                    data.append(item.startsWith(" ") ? item.substring(1) : item);
                }
            }
        }
        return finalData;
    }

    private String failed(String taskId, String message) {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("taskId", taskId);
            result.put("protocolVersion", "IAM-SIMPLE-1");
            result.put("status", "FAILED");
            result.put("error", message);
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            return "{\"taskId\":\"" + taskId + "\",\"status\":\"FAILED\"}";
        }
    }

    @Override
    public void cancelTask(String taskId) {
        try {
            restClient.post().uri("/internal/iam-s1/query/tasks/{taskId}/cancel", taskId).retrieve().toBodilessEntity();
        } catch (Exception ex) {
            log.warn("IAM-SIMPLE-1 取消请求失败 taskId={}", taskId);
        }
    }
}
