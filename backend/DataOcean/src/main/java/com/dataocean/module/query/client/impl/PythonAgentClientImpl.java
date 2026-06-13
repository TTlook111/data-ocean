package com.dataocean.module.query.client.impl;

import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import com.dataocean.module.knowledge.enums.ReviewStatus;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.permission.entity.vo.PermissionContextVO;
import com.dataocean.module.permission.service.PermissionCalculator;
import com.dataocean.module.query.client.PythonAgentClient;
import com.dataocean.module.query.service.ConversationService;
import com.dataocean.module.query.service.QueryTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
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

    private final QueryTaskService queryTaskService;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;
    private final com.dataocean.module.datasource.mapper.DatasourceMapper datasourceMapper;
    private final com.dataocean.module.datasource.mapper.DatasourceSecretMapper datasourceSecretMapper;
    private final com.dataocean.module.datasource.service.DatasourceSecretService datasourceSecretService;
    private final PermissionCalculator permissionCalculator;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final com.dataocean.module.knowledge.mapper.KnowledgeDocMapper knowledgeDocMapper;

    @Qualifier("pythonRestClient")
    private final RestClient restClient;

    /**
     * {@inheritDoc}
     * <p>
     * 使用 @Async 在独立线程中执行，不阻塞主请求。
     * 消费 Python SSE 流，提取最终 result/error 事件回写数据库。
     * </p>
     */
    @Async("queryExecutor")
    @Override
    public void executeAsync(String taskId, Long datasourceId, Long userId, String question,
                             Long conversationId, Long activeSnapshotId) {
        log.info("触发 Agent 执行 taskId={} datasourceId={}", taskId, datasourceId);

        // 查询数据源连接信息，Java 侧解密密码后以明文传给 Python 内网服务
        Map<String, Object> connectionConfig = buildConnectionConfig(datasourceId);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("taskId", taskId);
        requestBody.put("datasourceId", datasourceId);
        requestBody.put("userId", userId);
        requestBody.put("question", question);
        requestBody.put("activeSnapshotId", activeSnapshotId);
        requestBody.put("connectionConfig", connectionConfig);
        // 计算用户对该数据源的真实权限上下文
        PermissionContextVO permContext = permissionCalculator.calculate(userId, datasourceId);
        requestBody.put("userPermissions", buildUserPermissionsMap(permContext));
        // 从会话中获取最近 5 轮对话作为上下文
        requestBody.put("conversationHistory", buildConversationHistory(conversationId, userId));
        // 加载 fallback chunks（Milvus 不可用时的降级数据）
        requestBody.put("fallbackChunks", loadFallbackChunks(datasourceId));

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
                boolean updated = queryTaskService.updateTaskResult(taskId, finalResult);
                // 仅在实际更新时保存助手消息（任务已取消则跳过）
                if (updated) {
                    saveAssistantMessageFromResult(conversationId, taskId, finalResult);
                }
            } else {
                queryTaskService.updateTaskResult(taskId, "{\"status\":\"FAILED\",\"error\":\"Agent 未返回最终结果\"}");
            }

        } catch (Exception e) {
            log.error("Agent 执行失败 taskId={} error={}", taskId, e.getMessage(), e);
            // SSE 流读取异常可能是连接断开，通知 Python 取消以释放资源
            cancelTask(taskId);
            try {
                queryTaskService.updateTaskResult(taskId,
                        "{\"status\":\"FAILED\",\"error\":\"Agent 服务调用失败，请稍后重试\"}");
            } catch (Exception ex) {
                log.error("回写失败状态异常 taskId={}", taskId, ex);
            }
        }
    }

    /** SSE 流总时长保护：最大 180 秒 */
    private static final long MAX_SSE_DURATION_MS = 180_000;

    /**
     * 消费 SSE 事件流，返回最终 result/error 事件的 data 内容。
     * <p>
     * 实现标准 SSE 客户端解析：
     * - 支持多行 data（SSE 规范允许多行 data，以换行分隔）
     * - 按空行提交事件（空行表示事件结束）
     * - 忽略注释行（以 : 开头）
     * - 处理心跳/注释行
     * </p>
     * <p>
     * progress 事件会被实时解析并回写到 query_task 表，供前端轮询展示分阶段进度；
     * result/error 事件的 data 作为最终结果返回。
     * </p>
     * <p>
     * 增加总时长保护：如果 SSE 流持续超过 {@value MAX_SSE_DURATION_MS} 毫秒，
     * 将强制终止并返回 null，同时调用 cancelTask 通知 Python 取消任务。
     * </p>
     */
    private String consumeSseStream(InputStream stream, String taskId) {
        String lastResultData = null;
        long startTime = System.currentTimeMillis();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String currentEventType = null;
            StringBuilder currentData = new StringBuilder();

            String line;
            while ((line = reader.readLine()) != null) {
                // 检查总时长保护
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > MAX_SSE_DURATION_MS) {
                    log.warn("SSE 流超过最大时长 {}ms，强制终止 taskId={} elapsed={}ms", MAX_SSE_DURATION_MS, taskId, elapsed);
                    cancelTask(taskId);
                    break;
                }

                // 空行表示事件结束，提交当前事件
                if (line.isEmpty()) {
                    if (currentData.length() > 0 && currentEventType != null) {
                        String data = currentData.toString().trim();
                        if ("result".equals(currentEventType) || "error".equals(currentEventType)) {
                            lastResultData = data;
                        } else if ("progress".equals(currentEventType)) {
                            handleProgressEvent(taskId, data);
                        }
                    }
                    // 重置状态，准备下一个事件
                    currentEventType = null;
                    currentData.setLength(0);
                    continue;
                }

                // 忽略注释行（以 : 开头，SSE 心跳机制）
                if (line.startsWith(":")) {
                    continue;
                }

                // 解析 event 行
                if (line.startsWith("event:")) {
                    currentEventType = line.substring(6).trim();
                    continue;
                }

                // 解析 data 行（支持多行 data）
                if (line.startsWith("data:")) {
                    String dataContent = line.substring(5);
                    // SSE 规范：data 前的空格是可选的
                    if (dataContent.startsWith(" ")) {
                        dataContent = dataContent.substring(1);
                    }
                    if (currentData.length() > 0) {
                        currentData.append("\n");
                    }
                    currentData.append(dataContent);
                    continue;
                }

                // 其他行（如 id:、retry: 等）暂时忽略
                log.debug("SSE 未处理行 taskId={} line={}", taskId, line);
            }

            // 流结束时，提交最后一个事件（如果没有以空行结尾）
            if (currentData.length() > 0 && currentEventType != null) {
                String data = currentData.toString().trim();
                if ("result".equals(currentEventType) || "error".equals(currentEventType)) {
                    lastResultData = data;
                } else if ("progress".equals(currentEventType)) {
                    handleProgressEvent(taskId, data);
                }
            }
        } catch (Exception e) {
            log.error("SSE 流读取异常 taskId={}", taskId, e);
        }
        return lastResultData;
    }

    /**
     * 解析单条 progress 事件并回写任务进度。
     * <p>
     * 进度回写失败不影响主流程（最终结果仍以 result/error 事件为准）。
     * </p>
     */
    private void handleProgressEvent(String taskId, String data) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> progress = objectMapper.readValue(data, Map.class);
            String node = (String) progress.get("node");
            String message = (String) progress.get("message");
            queryTaskService.updateTaskProgress(taskId, node, message);
        } catch (Exception e) {
            log.warn("解析进度事件失败 taskId={} data={}", taskId, data);
        }
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

    /**
     * {@inheritDoc}
     * <p>
     * 调用 Python /internal/query/tasks/{taskId}/cancel 通知 Agent 停止执行。
     * 调用失败不影响 Java 侧状态更新。
     * </p>
     */
    @Override
    public void cancelTask(String taskId) {
        try {
            restClient.post()
                    .uri("/internal/query/tasks/{taskId}/cancel", taskId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("已通知 Python 取消任务 taskId={}", taskId);
        } catch (Exception e) {
            log.warn("通知 Python 取消任务失败 taskId={} reason={}", taskId, e.getMessage());
        }
    }

    /**
     * 构建最近 5 轮对话历史（排除当前刚保存的用户消息）。
     */
    private List<Map<String, String>> buildConversationHistory(Long conversationId, Long userId) {
        if (conversationId == null) {
            return List.of();
        }
        try {
            // 获取最近 10 条消息（5 轮 = 10 条 user+assistant）
            var messages = conversationService.getRecentMessages(conversationId, userId, 10);
            List<Map<String, String>> history = new java.util.ArrayList<>();
            for (var msg : messages) {
                history.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
            }
            // 去掉最后一条（当前刚保存的 user 消息，避免重复）
            if (!history.isEmpty() && "user".equals(history.get(history.size() - 1).get("role"))) {
                history.remove(history.size() - 1);
            }
            return history;
        } catch (Exception e) {
            log.warn("获取对话历史失败 conversationId={}", conversationId, e);
            return List.of();
        }
    }

    /**
     * 构建数据源连接配置，Java 侧解密密码后以明文传给 Python 内网服务。
     */
    private Map<String, Object> buildConnectionConfig(Long datasourceId) {
        var datasource = datasourceMapper.selectById(datasourceId);
        if (datasource == null) {
            return Map.of();
        }

        var secret = datasourceSecretMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                        com.dataocean.module.datasource.entity.DatasourceSecret>()
                        .eq(com.dataocean.module.datasource.entity.DatasourceSecret::getDatasourceId, datasourceId));

        String plainPassword = "";
        if (secret != null && secret.getEncryptedPassword() != null) {
            try {
                plainPassword = datasourceSecretService.decrypt(secret.getEncryptedPassword());
            } catch (Exception e) {
                log.error("数据源密码解密失败 datasourceId={}", datasourceId, e);
            }
        }

        Map<String, Object> config = new HashMap<>();
        config.put("host", datasource.getHost());
        config.put("port", datasource.getPort());
        config.put("database", datasource.getDatabaseName());
        config.put("username", secret != null ? secret.getUsername() : "");
        config.put("password", plainPassword);
        return config;
    }

    /**
     * 将 PermissionContextVO 转换为 Python UserPermissions 期望的 Map 格式
     */
    private Map<String, Object> buildUserPermissionsMap(PermissionContextVO context) {
        Map<String, Object> map = new HashMap<>();
        map.put("allowedTables", context.getAllowedTables());
        map.put("deniedColumns", context.getDeniedColumns());

        // rowFilters: [{tableName, condition}]
        List<Map<String, String>> rowFilters = new java.util.ArrayList<>();
        for (PermissionContextVO.RowFilterItem item : context.getRowFilters()) {
            rowFilters.add(Map.of("tableName", item.getTableName(), "condition", item.getCondition()));
        }
        map.put("rowFilters", rowFilters);

        // maskColumns: [{tableName, columnName, maskType}]
        List<Map<String, String>> maskColumns = new java.util.ArrayList<>();
        for (PermissionContextVO.MaskColumnItem item : context.getMaskColumns()) {
            if (item.getMaskType() == null) continue;
            Map<String, String> entry = new HashMap<>();
            entry.put("tableName", item.getTableName());
            entry.put("columnName", item.getColumnName());
            entry.put("maskType", item.getMaskType());
            maskColumns.add(entry);
        }
        map.put("maskColumns", maskColumns);

        return map;
    }

    /**
     * 加载该数据源已发布知识文档的 TABLE_DESC 类型切片，作为 Milvus 不可用时的降级数据。
     * <p>
     * 使用参数化查询替代 inSql() 字符串拼接，避免 SQL 注入风险。
     * </p>
     */
    private List<Map<String, Object>> loadFallbackChunks(Long datasourceId) {
        try {
            // 先查询该数据源下已发布的知识文档 ID 列表
            var docIds = knowledgeDocMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.dataocean.module.knowledge.entity.KnowledgeDoc>()
                            .select(com.dataocean.module.knowledge.entity.KnowledgeDoc::getId)
                            .eq(com.dataocean.module.knowledge.entity.KnowledgeDoc::getDatasourceId, datasourceId)
                            .eq(com.dataocean.module.knowledge.entity.KnowledgeDoc::getStatus, "PUBLISHED")
                            .eq(com.dataocean.module.knowledge.entity.KnowledgeDoc::getDeleted, 0))
                    .stream()
                    .map(com.dataocean.module.knowledge.entity.KnowledgeDoc::getId)
                    .toList();

            // 如果没有已发布的文档，直接返回空列表
            if (docIds.isEmpty()) {
                return List.of();
            }

            // 使用 .in() 查询 chunks，避免 inSql() 字符串拼接
            var chunks = knowledgeChunkMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeChunk>()
                            .eq(KnowledgeChunk::getReviewStatus, ReviewStatus.APPROVED.name())
                            .eq(KnowledgeChunk::getChunkType, "TABLE_DESC")
                            .in(KnowledgeChunk::getDocId, docIds)
                            .last("LIMIT 5"));
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (KnowledgeChunk chunk : chunks) {
                Map<String, Object> item = new HashMap<>();
                item.put("chunkType", chunk.getChunkType());
                item.put("chunkText", chunk.getChunkText());
                item.put("relatedTable", chunk.getRelatedTable());
                item.put("snapshotId", chunk.getMetadataSnapshotId());
                result.add(item);
            }
            return result;
        } catch (Exception e) {
            log.warn("加载 fallback chunks 失败 datasourceId={} reason={}", datasourceId, e.getMessage());
            return List.of();
        }
    }
}
