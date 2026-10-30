package com.dataocean.module.query.client.impl;

import com.dataocean.module.query.client.ConversationSummaryClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Python 会话摘要接口客户端实现。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationSummaryClientImpl implements ConversationSummaryClient {

    @Qualifier("pythonRestClient")
    private final RestClient restClient;

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> summarize(List<Map<String, Object>> messages,
                                         Map<String, Object> previousSummary) {
        Map<String, Object> request = new HashMap<>();
        request.put("messages", messages);
        request.put("previousSummary", previousSummary == null ? Map.of() : previousSummary);
        request.put("currentDate", LocalDate.now().toString());

        Map<String, Object> response = restClient.post()
                .uri("/internal/query/context-summary")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Map.class);
        if (response == null || !(response.get("summary") instanceof Map<?, ?> summary)) {
            throw new IllegalStateException("Python 摘要接口返回格式异常");
        }
        return (Map<String, Object>) summary;
    }
}
