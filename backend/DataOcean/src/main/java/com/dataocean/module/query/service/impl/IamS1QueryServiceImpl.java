package com.dataocean.module.query.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.exception.IamS1MaskPolicyConflictException;
import com.dataocean.common.pagination.PageRequest;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.DatasourceSecret;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceSecretMapper;
import com.dataocean.module.datasource.service.DatasourceSecretService;
import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.audit.service.AuditLogService;
import com.dataocean.module.glossary.entity.GlossaryTerm;
import com.dataocean.module.glossary.mapper.GlossaryTermMapper;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.dto.IamS1DataAuthorizationRequestDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1TableRequestDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot;
import com.dataocean.module.permission.s1.entity.vo.IamS1FieldProtectionVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1GrantSourceVO;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1DataAuthorizationResolver;
import com.dataocean.module.query.client.IamS1PythonClient;
import com.dataocean.module.query.controller.IamS1QuerySseController;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.entity.dto.IamS1ExecutionBinding;
import com.dataocean.module.query.entity.dto.IamS1QueryAskRequestDTO;
import com.dataocean.module.query.entity.dto.ConversationContextDTO;
import com.dataocean.module.query.entity.query.QueryHistoryQuery;
import com.dataocean.module.query.entity.vo.QueryTaskVO;
import com.dataocean.module.query.enums.QueryTaskStatus;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.query.service.IamS1QueryService;
import com.dataocean.module.query.service.IamS1RowBindingService;
import com.dataocean.module.query.service.ConversationService;
import com.dataocean.module.query.service.ConversationContextSummaryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * S1 查询服务。所有查询结果在落库前都要通过原执行范围与当前 Resolver
 * 结果的交集检查；绑定参数只在 submit -> Python 的内存对象中存在。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IamS1QueryServiceImpl implements IamS1QueryService {

    private final QueryTaskMapper queryTaskMapper;
    private final ObjectMapper objectMapper;
    private final IamS1DataAuthorizationResolver dataResolver;
    private final IamS1AuthorizationResolver authorizationResolver;
    private final IamS1RowBindingService rowBindingService;
    private final IamS1PythonClient pythonClient;
    private final IamS1QuerySseController sseController;
    private final ConversationService conversationService;
    private final ConversationContextSummaryService conversationContextSummaryService;
    private final GlossaryTermMapper glossaryTermMapper;
    private final MetadataEntityService metadataEntityService;
    private final MetadataRelationshipService metadataRelationshipService;
    private final DatasourceMapper datasourceMapper;
    private final DatasourceSecretMapper datasourceSecretMapper;
    private final DatasourceSecretService datasourceSecretService;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final AuditLogService auditLogService;
    private final com.dataocean.module.metadata.service.SchemaSnapshotService schemaSnapshotService;
    private final com.dataocean.common.security.DataMaskingService maskingService;

    @Override
    @Transactional
    public String submit(Long userId, IamS1QueryAskRequestDTO request) {
        requireProtocol(request.getProtocolVersion());
        if (userId == null || request.getDatasourceId() == null || request.getTables() == null
                || request.getTables().isEmpty()) {
            throw new BusinessException("S1 查询缺少必填资源");
        }
        requireExplicitUsages(request.getTables());
        if (!authorizationResolver.hasGlobalFunction(userId, "query:use")) {
            throw new BusinessException("没有 IAM-SIMPLE-1 问数功能");
        }
        var metadata = schemaSnapshotService.getPublishedSnapshot(request.getDatasourceId());
        if (metadata == null) {
            throw new BusinessException("当前数据源没有已发布元数据快照");
        }
        String taskId = UUID.randomUUID().toString();
        IamS1DataAuthorizationSnapshot snapshot = resolve(userId, request, metadata.getId());
        if (!snapshot.isAllowed()) {
            throw new BusinessException("当前 S1 数据范围不允许查询：" + snapshot.getReasonCode());
        }
        List<IamS1ExecutionBinding> bindings = rowBindingService.build(snapshot);
        Long conversationId = conversationService.getOrCreateConversation(
                userId, request.getDatasourceId(), request.getConversationId(), request.getQuestion());
        conversationService.saveUserMessage(conversationId, request.getQuestion());
        Map<String, Object> safeSnapshot = buildSnapshot(taskId, request, snapshot);
        Map<String, Object> capabilities = capabilities(userId, request.getDatasourceId());
        QueryTask task = QueryTask.builder()
                .taskId(taskId).userId(userId).datasourceId(request.getDatasourceId())
                .iamProtocolVersion(IamS1Constants.PROTOCOL_VERSION)
                .activeMetadataSnapshotId(snapshot.getActiveMetadataSnapshotId())
                .permissionRevision(snapshot.getPermissionRevision())
                .iamExecutionSnapshot(writeJson(safeSnapshot))
                .iamResourceRequest(writeJson(request.getTables()))
                .iamCapabilities(writeJson(capabilities))
                .question(request.getQuestion()).conversationId(conversationId)
                .status(QueryTaskStatus.PROCESSING.name()).retryCount(0).createdAt(LocalDateTime.now())
                .build();
        queryTaskMapper.insert(task);

        Map<String, Object> pythonRequest = buildPythonRequest(taskId, userId, conversationId, request, snapshot,
                safeSnapshot, capabilities, bindings);
        Runnable dispatch = () -> pythonClient.executeAsync(taskId, pythonRequest, result -> {
            complete(taskId, result);
            try {
                // 必须复用 get：SSE 与 REST 走同一条读取路径，才能在推送前
                // 完成 viewSql 能力判定、当前权限复查和最终脱敏。
                sseController.sendResult(taskId, get(taskId, userId));
            } catch (BusinessException ex) {
                // get 拒绝呈现时只推送可公开的原因，不推送任何结果载荷。
                sseController.sendError(taskId, ex.getMessage());
            } catch (Exception ex) {
                log.warn("S1 结果 SSE 推送失败 taskId={}", taskId);
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
        return taskId;
    }

    @Override
    public QueryTaskVO get(String taskId, Long userId) {
        QueryTask task = find(taskId, userId);
        if (!isS1(task)) throw new BusinessException("任务不属于 IAM-SIMPLE-1");
        // 功能权限按当前状态复查：撤销 query:use 后，历史结果不得再通过
        // 任务读取、历史、导出、反馈或 SSE 取回。
        if (!authorizationResolver.hasGlobalFunction(userId, "query:use")) {
            throw new BusinessException("没有 IAM-SIMPLE-1 问数功能");
        }
        IamS1DataAuthorizationSnapshot current = recheck(task, userId);
        if (!java.util.Objects.equals(current.getPermissionRevision(), task.getPermissionRevision())
                && (containsRowCondition(task.getIamExecutionSnapshot()) || hasRowCondition(current))) {
            throw new BusinessException("权限已变化，请重新查询");
        }
        QueryTaskVO result = toVO(task);
        if ("COMPLETED".equals(task.getStatus()) && !hasCompletePersistedEvidence(task)) {
            throw new BusinessException("结果来源不完整，请重新查询");
        }
        applyCurrentProtection(result, task, current);
        Map<String, Object> caps = capabilities(userId, task.getDatasourceId());
        boolean viewSql = Boolean.TRUE.equals(caps.get("viewSql"));
        result.setCanViewSql(viewSql);
        result.setCanExport(Boolean.TRUE.equals(caps.get("export")));
        if (!viewSql) result.setSql(null);
        return result;
    }

    @Override
    public Long conversationId(String taskId, Long userId) {
        QueryTask task = find(taskId, userId);
        if (!isS1(task)) throw new BusinessException("任务不属于 IAM-SIMPLE-1");
        requireQueryUse(userId);
        return task.getConversationId();
    }

    @Override
    public Page<QueryTaskVO> history(Long userId, QueryHistoryQuery query) {
        Page<QueryTask> page = queryTaskMapper.selectPage(
                new Page<>(PageRequest.page(query.getPage()), PageRequest.size(query.getPageSize())),
                new LambdaQueryWrapper<QueryTask>().eq(QueryTask::getUserId, userId)
                        .eq(QueryTask::getIamProtocolVersion, IamS1Constants.PROTOCOL_VERSION)
                        .eq(query.getDatasourceId() != null, QueryTask::getDatasourceId, query.getDatasourceId())
                        .eq(query.getStatus() != null && !query.getStatus().isBlank(), QueryTask::getStatus, query.getStatus())
                        .orderByDesc(QueryTask::getCreatedAt));
        Page<QueryTaskVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<QueryTaskVO> records = new ArrayList<>();
        for (QueryTask task : page.getRecords()) {
            try {
                records.add(get(task.getTaskId(), userId));
            } catch (BusinessException ignored) {
                // 当前权限无法证明历史结果安全时不返回该行。
            }
        }
        result.setRecords(records);
        return result;
    }

    @Override
    @Transactional
    public void cancel(String taskId, Long userId) {
        QueryTask task = find(taskId, userId);
        if (!isS1(task)) throw new BusinessException("任务不属于 IAM-SIMPLE-1");
        if (!QueryTaskStatus.PROCESSING.name().equals(task.getStatus())) {
            throw new BusinessException("任务已结束");
        }
        queryTaskMapper.update(null, new LambdaUpdateWrapper<QueryTask>()
                .eq(QueryTask::getTaskId, taskId).eq(QueryTask::getUserId, userId)
                .set(QueryTask::getStatus, QueryTaskStatus.CANCELLED.name())
                .set(QueryTask::getIamFinalProtectionStatus, "CANCELLED")
                .set(QueryTask::getCompletedAt, LocalDateTime.now()));
        pythonClient.cancelTask(taskId);
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public void complete(String taskId, String resultJson) {
        QueryTask task = queryTaskMapper.selectOne(new LambdaQueryWrapper<QueryTask>().eq(QueryTask::getTaskId, taskId));
        if (task == null || !isS1(task)) return;
        try {
            Map<String, Object> result = objectMapper.readValue(resultJson, new TypeReference<>() {});
            if (!taskId.equals(result.get("taskId")) || !IamS1Constants.PROTOCOL_VERSION.equals(result.get("protocolVersion"))) {
                fail(taskId, "S1 结果合同不一致", "REJECTED_CONTRACT");
                return;
            }
            String status = String.valueOf(result.getOrDefault("status", "FAILED"));
            if (!"COMPLETED".equals(status)) {
                fail(taskId, safeError(String.valueOf(result.getOrDefault("error", "S1 查询失败"))), "FAILED");
                return;
            }
            if (!hasCompleteSourceTrace(result.get("sourceTrace"), result.get("columns"))) {
                fail(taskId, "结果来源不完整，请重新查询", "REJECTED_SOURCE_TRACE");
                return;
            }
            IamS1DataAuthorizationSnapshot current = recheck(task, task.getUserId());
            if (!current.isAllowed()
                    || !java.util.Objects.equals(current.getPermissionRevision(), task.getPermissionRevision())
                    || !java.util.Objects.equals(current.getActiveMetadataSnapshotId(), task.getActiveMetadataSnapshotId())) {
                fail(taskId, "权限已变化，请重新查询", "REJECTED_ON_RECHECK");
                return;
            }
            List<String> usedColumns = objectMapper.convertValue(result.getOrDefault("usedColumns", List.of()), new TypeReference<>() {});
            if (!isSubsetOfCurrent(usedColumns, current)) {
                fail(taskId, "权限已变化，请重新查询", "REJECTED_ON_RECHECK");
                return;
            }
            List<Map<String, Object>> data = objectMapper.convertValue(result.getOrDefault("data", List.of()), new TypeReference<>() {});
            Map<String, String> outputMasks;
            try {
                outputMasks = deriveOutputMasks(result.get("sourceTrace"), current);
            } catch (IamS1MaskPolicyConflictException ex) {
                // 冲突结果无法按单一策略正确脱敏，直接拒绝落库。
                fail(taskId, "结果脱敏策略冲突，拒绝落库", "REJECTED_FINAL_PROTECTION");
                return;
            }
            data = maskingService.maskResultByFields(data, outputMasks);
            String safeSql = safeSql((String) result.get("sql"));
            String safeExplanation = safeExplanation((String) result.get("sqlExplanation"));
            String safeChart = safeChartConfig(result.get("chartConfig"), outputMasks);
            List<String> suggestions = safeSuggestions(result.get("suggestedQuestions"));
            LambdaUpdateWrapper<QueryTask> update = new LambdaUpdateWrapper<QueryTask>()
                    .eq(QueryTask::getTaskId, taskId).eq(QueryTask::getStatus, QueryTaskStatus.PROCESSING.name())
                    .set(QueryTask::getStatus, "COMPLETED").set(QueryTask::getResultSql, safeSql)
                    .set(QueryTask::getSqlExplanation, safeExplanation)
                    .set(QueryTask::getResultData, writeJson(data)).set(QueryTask::getResultColumns, writeJson(result.get("columns")))
                    .set(QueryTask::getChartConfig, safeChart)
                    .set(QueryTask::getSuggestedQuestions, writeJson(suggestions))
                    .set(QueryTask::getUsedTables, writeJson(result.get("usedTables")))
                    .set(QueryTask::getUsedColumns, writeJson(usedColumns))
                    .set(QueryTask::getIamSourceTrace, writeJson(Map.of(
                            "permissionRevision", task.getPermissionRevision(),
                            "activeMetadataSnapshotId", task.getActiveMetadataSnapshotId(),
                            "entries", result.getOrDefault("sourceTrace", List.of()))))
                    .set(QueryTask::getMaskedFields, writeJson(outputMasks))
                    .set(QueryTask::getDegraded, Boolean.TRUE.equals(result.get("degraded")))
                    .set(QueryTask::getDegradeNotice, (String) result.get("degradeNotice"))
                    .set(QueryTask::getTotalTimeMs, number(result.get("totalTimeMs")))
                    .set(QueryTask::getIamFinalProtectionStatus, outputMasks.isEmpty() ? "FINAL_PROTECTED" : "FINAL_MASKED")
                    .set(QueryTask::getCompletedAt, LocalDateTime.now());
            int updated = queryTaskMapper.update(null, update);
            auditLogService.recordAudit(task.getId());
            if (updated > 0) {
                saveCompletedConversationMessage(task, result, data, outputMasks, safeSql, safeExplanation,
                        safeChart, suggestions);
                refreshConversationContext(task.getConversationId(), task.getUserId(), task.getTaskId());
            }
        } catch (BusinessException ex) {
            fail(taskId, "权限已变化，请重新查询", "REJECTED_ON_RECHECK");
        } catch (Exception ex) {
            log.warn("S1 结果保护失败 taskId={}", taskId);
            fail(taskId, "查询结果无法完成最终保护", "REJECTED_FINAL_PROTECTION");
        }
    }

    @Override
    public void feedback(String taskId, Long userId, String feedbackType) {
        if (!"LIKE".equals(feedbackType) && !"DISLIKE".equals(feedbackType)) {
            throw new BusinessException("无效的反馈类型");
        }
        QueryTask task = find(taskId, userId);
        if (!isS1(task)) throw new BusinessException("任务不属于 IAM-SIMPLE-1");
        get(taskId, userId); // 当前权限与任务范围复查后才关联反馈。
        auditLogService.updateFeedback(task.getId(), feedbackType);
    }

    @Override
    public List<Map<String, Object>> export(String taskId, Long userId) {
        QueryTaskVO vo = get(taskId, userId);
        if (!Boolean.TRUE.equals(vo.getCanExport())) throw new BusinessException("没有当前 S1 导出能力");
        return vo.getData() == null ? List.of() : vo.getData();
    }

    @Override
    public List<?> conversations(Long userId, Long datasourceId) {
        requireQueryUse(userId);
        return conversationService.listConversations(userId, datasourceId).stream()
                .filter(item -> item instanceof com.dataocean.module.query.entity.Conversation conversation
                        && queryTaskMapper.selectCount(new LambdaQueryWrapper<QueryTask>()
                        .eq(QueryTask::getConversationId, conversation.getId())
                        .eq(QueryTask::getIamProtocolVersion, IamS1Constants.PROTOCOL_VERSION)) > 0)
                .toList();
    }

    @Override
    public List<com.dataocean.module.query.entity.vo.ConversationMessageVO> conversationMessages(
            Long conversationId, Long userId, Integer page, Integer pageSize) {
        requireQueryUse(userId);
        List<com.dataocean.module.query.entity.vo.ConversationMessageVO> messages =
                conversationService.listMessages(conversationId, userId, page, pageSize);
        for (var message : messages) {
            if (!"assistant".equals(message.getRole())) {
                continue;
            }
            if (message.getTaskId() == null || message.getTaskId().isBlank()) {
                message.setContent("该历史结果不属于 IAM-SIMPLE-1，已隐藏");
                message.setMetadata(null);
                continue;
            }
            QueryTaskVO safe = get(message.getTaskId(), userId);
            message.setMetadata(writeJson(safe));
            if (safe.getErrorMessage() != null && !safe.getErrorMessage().isBlank()) {
                message.setContent(safe.getErrorMessage());
            }
        }
        return messages;
    }

    @Override
    public void archiveConversation(Long conversationId, Long userId) {
        requireQueryUse(userId);
        conversationService.archiveConversation(conversationId, userId);
    }

    private void requireQueryUse(Long userId) {
        if (!authorizationResolver.hasGlobalFunction(userId, "query:use")) {
            throw new BusinessException("没有 IAM-SIMPLE-1 问数功能");
        }
    }

    private IamS1DataAuthorizationSnapshot resolve(Long userId, IamS1QueryAskRequestDTO request, Long snapshotId) {
        IamS1DataAuthorizationRequestDTO auth = new IamS1DataAuthorizationRequestDTO();
        auth.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION); auth.setUserId(userId);
        auth.setDatasourceId(request.getDatasourceId()); auth.setActiveMetadataSnapshotId(snapshotId);
        auth.setCalculatedAt(LocalDateTime.now()); auth.setTables(request.getTables());
        return dataResolver.resolve(auth);
    }

    private IamS1DataAuthorizationSnapshot recheck(QueryTask task, Long userId) {
        try {
            List<IamS1TableRequestDTO> tables = objectMapper.readValue(task.getIamResourceRequest(), new TypeReference<>() {});
            IamS1QueryAskRequestDTO request = new IamS1QueryAskRequestDTO();
            request.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION); request.setDatasourceId(task.getDatasourceId());
            request.setTables(tables);
            var snapshot = schemaSnapshotService.getPublishedSnapshot(task.getDatasourceId());
            if (snapshot == null) throw new BusinessException("当前没有已发布快照");
            return resolve(userId, request, snapshot.getId());
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw new BusinessException("无法确认当前 S1 权限"); }
    }

    private boolean isSubsetOfCurrent(List<String> usedColumns, IamS1DataAuthorizationSnapshot current) {
        if (usedColumns == null || usedColumns.isEmpty() || current == null) return false;
        Map<String, IamS1FieldProtectionVO> fields = new HashMap<>();
        current.getTables().forEach(table -> table.getFieldProtections().forEach(field ->
                fields.put((table.getTableName() + "." + field.getColumnName()).toLowerCase(Locale.ROOT), field)));
        for (String value : usedColumns) {
            IamS1FieldProtectionVO protection = fields.get(value.toLowerCase(Locale.ROOT));
            if (protection == null || "HIDDEN".equals(protection.getProtectionLevel())) return false;
        }
        return true;
    }

    /**
     * 按输出列名（小写规范化，与 {@code maskResultByFields} 的口径一致）汇总脱敏策略。
     * <p>
     * Python 侧同样会拒绝多策略冲突，但 Java 是最终保护边界，不能依赖上游校验结果：
     * 历史持久化任务、异常来源或协议回归都可能带来含有冲突的 sourceTrace，而按列名
     * 生效的脱敏只能取其一，必然用错误策略处理另一部分数据。此处独立判定并 fail-closed。
     * </p>
     */
    private Map<String, String> deriveOutputMasks(Object rawTrace, IamS1DataAuthorizationSnapshot snapshot) {
        Map<String, IamS1FieldProtectionVO> fieldMap = new HashMap<>();
        snapshot.getTables().forEach(table -> table.getFieldProtections().forEach(field ->
                fieldMap.put((table.getTableName() + "." + field.getColumnName()).toLowerCase(Locale.ROOT), field)));
        Map<String, Set<String>> policies = new LinkedHashMap<>();
        Map<String, String> outputNames = new LinkedHashMap<>();
        if (rawTrace instanceof Map<?, ?> wrapper) {
            rawTrace = wrapper.get("entries");
        }
        if (rawTrace instanceof List<?> traces) {
            for (Object raw : traces) {
                if (!(raw instanceof Map<?, ?> trace)) continue;
                String output = String.valueOf(trace.get("outputColumn"));
                Object source = trace.get("sources");
                if (!(source instanceof List<?> sources)) continue;
                String key = output.toLowerCase(Locale.ROOT);
                for (Object item : sources) {
                    IamS1FieldProtectionVO protection = fieldMap.get(String.valueOf(item).toLowerCase(Locale.ROOT));
                    if (protection == null || !"MASKED".equals(protection.getProtectionLevel())) continue;
                    outputNames.putIfAbsent(key, output);
                    Set<String> found = policies.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
                    String policy = protection.getMaskPolicy();
                    if (policy != null && !policy.isBlank()) found.add(policy);
                }
            }
        }
        List<String> conflicts = policies.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getKey() + " -> " + String.join(", ", entry.getValue()))
                .toList();
        if (!conflicts.isEmpty()) {
            log.warn("S1 结果存在脱敏策略冲突 conflicts={}", conflicts);
            throw new IamS1MaskPolicyConflictException("同一输出列存在多种脱敏策略", conflicts);
        }
        Map<String, String> result = new LinkedHashMap<>();
        policies.forEach((key, found) -> {
            if (!found.isEmpty()) {
                result.put(outputNames.getOrDefault(key, key), found.iterator().next());
            }
        });
        return result;
    }

    private Map<String, Object> buildSnapshot(String taskId, IamS1QueryAskRequestDTO request,
                                               IamS1DataAuthorizationSnapshot snapshot) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("protocolVersion", IamS1Constants.PROTOCOL_VERSION); output.put("taskId", taskId);
        output.put("userId", snapshot.getUserId()); output.put("datasourceId", snapshot.getDatasourceId());
        output.put("activeMetadataSnapshotId", snapshot.getActiveMetadataSnapshotId());
        output.put("permissionRevision", snapshot.getPermissionRevision());
        // S1 契约把这两个字段声明为字符串。PythonRestClientConfig 用的是静态 RestClient.builder()，
        // 其 Jackson converter 不套用本应用的日期配置，会把 LocalDateTime 序列化成时间戳数组，
        // 导致 Python 侧 422（"Input should be a valid string"）。因此在此显式格式化为 ISO-8601，
        // 不依赖客户端的序列化设置——凡是要跨到 Python 的日期字段都必须这样处理。
        output.put("calculatedAt", isoOrNull(snapshot.getCalculatedAt()));
        output.put("nextEffectiveAt", isoOrNull(snapshot.getNextEffectiveAt()));
        List<Map<String, Object>> resources = new ArrayList<>();
        for (var table : snapshot.getTables()) {
            IamS1TableRequestDTO tableRequest = request.getTables().stream()
                    .filter(item -> table.getTableName().equals(item.getTableName())).findFirst().orElse(null);
            List<Map<String, Object>> columns = new ArrayList<>();
            for (String name : table.getAllowedColumns()) {
                var protection = table.getFieldProtections().stream().filter(item -> name.equals(item.getColumnName())).findFirst().orElse(null);
                if (protection == null || "HIDDEN".equals(protection.getProtectionLevel())) continue;
                Map<String, Object> column = new LinkedHashMap<>(); column.put("name", name); column.put("columnId", protection.getColumnMetaId());
                column.put("usage", tableRequest == null ? List.of("PROJECTION") : tableRequest.getColumnUsages().getOrDefault(name, java.util.Set.of(com.dataocean.module.permission.s1.enums.IamS1ColumnUsage.PROJECTION)).stream().map(Enum::name).toList());
                column.put("protectionLevel", protection.getProtectionLevel()); column.put("maskPolicy", protection.getMaskPolicy());
                column.put("dataType", "UNKNOWN"); column.put("governanceStatus", "PUBLISHED"); column.put("sourceSnapshotId", snapshot.getActiveMetadataSnapshotId());
                columns.add(column);
            }
            Map<String, Object> resource = new LinkedHashMap<>(); resource.put("tableName", table.getTableName()); resource.put("columns", columns);
            resource.put("grantSources", table.getGrantSources().stream().map(this::grantMap).toList()); resource.put("sourceSnapshotId", snapshot.getActiveMetadataSnapshotId());
            resources.add(resource);
        }
        output.put("resources", resources); output.put("capabilities", capabilities(snapshot.getUserId(), snapshot.getDatasourceId()));
        return output;
    }

    /** 跨服务传输的日期统一用 ISO-8601 字符串；null 保持 null。 */
    private static String isoOrNull(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private Map<String, Object> grantMap(IamS1GrantSourceVO source) {
        Map<String, Object> map = new LinkedHashMap<>(); map.put("grantId", source.getGrantId()); map.put("sourceSummary", source.getSourceSummary());
        map.put("grantSource", source.getGrantSource()); map.put("sourceReferenceId", source.getSourceReferenceId()); map.put("explicitColumns", source.getExplicitColumns());
        map.put("rowCondition", source.getRowCondition()); return map;
    }

    private Map<String, Object> buildPythonRequest(String taskId, Long userId, Long conversationId,
                                                   IamS1QueryAskRequestDTO request,
                                                   IamS1DataAuthorizationSnapshot snapshot, Map<String, Object> safeSnapshot,
                                                   Map<String, Object> capabilities, List<IamS1ExecutionBinding> bindings) {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("protocolVersion", IamS1Constants.PROTOCOL_VERSION); body.put("taskId", taskId);
        body.put("userId", userId); body.put("datasourceId", request.getDatasourceId()); body.put("activeMetadataSnapshotId", snapshot.getActiveMetadataSnapshotId());
        body.put("permissionRevision", snapshot.getPermissionRevision()); body.put("permissionSnapshot", safeSnapshot); body.put("executionBindings", bindings);
        body.put("question", request.getQuestion()); body.put("connectionConfig", connectionConfig(request.getDatasourceId()));
        List<Map<String, Object>> chunks = loadKnowledgeChunks(snapshot.getActiveMetadataSnapshotId(), request.getDatasourceId());
        ConversationContextDTO conversation = conversationContextSummaryService
                .buildQueryContext(conversationId, userId);
        if (conversation == null) {
            conversation = ConversationContextDTO.builder().history(List.of()).summary(null).build();
        }
        body.put("conversationHistory", conversation.getHistory() == null ? List.of() : conversation.getHistory());
        body.put("conversationSummary", conversation.getSummary());
        body.put("ragChunks", chunks);
        body.put("fallbackChunks", chunks);
        body.put("glossaryTerms", loadApprovedGlossaryTerms(snapshot));
        body.put("fewShotExamples", loadFewShotExamples(userId, request.getDatasourceId(), snapshot, request));
        return body;
    }

    private void refreshConversationContext(Long conversationId, Long userId, String taskId) {
        if (conversationId == null) return;
        try {
            conversationContextSummaryService.refreshAsync(conversationId, userId);
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            log.warn("S1 会话摘要线程池繁忙，跳过本次摘要刷新 conversationId={} taskId={}", conversationId, taskId);
        }
    }

    private List<Map<String, Object>> loadFewShotExamples(Long userId, Long datasourceId,
                                                           IamS1DataAuthorizationSnapshot snapshot,
                                                           IamS1QueryAskRequestDTO request) {
        Set<String> requestedTables = request.getTables().stream()
                .map(IamS1TableRequestDTO::getTableName)
                .filter(java.util.Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> requestedColumns = request.getTables().stream()
                .flatMap(table -> table.getReferencedColumns().stream()
                        .map(column -> table.getTableName() + "." + column))
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        List<QueryTask> tasks = queryTaskMapper.selectList(new LambdaQueryWrapper<QueryTask>()
                .eq(QueryTask::getUserId, userId)
                .eq(QueryTask::getDatasourceId, datasourceId)
                .eq(QueryTask::getIamProtocolVersion, IamS1Constants.PROTOCOL_VERSION)
                .eq(QueryTask::getStatus, QueryTaskStatus.COMPLETED.name())
                .isNotNull(QueryTask::getResultSql)
                .orderByDesc(QueryTask::getCreatedAt)
                .last("LIMIT 8"));
        List<Map<String, Object>> examples = new ArrayList<>();
        for (QueryTask task : tasks == null ? List.<QueryTask>of() : tasks) {
            List<String> tables = readList(task.getUsedTables());
            List<String> columns = readList(task.getUsedColumns());
            if (tables.isEmpty() || columns.isEmpty()) continue;
            boolean withinRequestedTables = tables.stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .allMatch(requestedTables::contains);
            boolean withinRequestedColumns = columns.stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .allMatch(requestedColumns::contains);
            if (!withinRequestedTables || !withinRequestedColumns) continue;
            Map<String, Object> example = new LinkedHashMap<>();
            example.put("question", task.getQuestion());
            example.put("sql", safeSql(task.getResultSql()));
            example.put("datasourceId", datasourceId);
            example.put("activeMetadataSnapshotId", snapshot.getActiveMetadataSnapshotId());
            example.put("tables", tables);
            example.put("columns", columns);
            examples.add(example);
            if (examples.size() >= 3) break;
        }
        return examples;
    }

    private List<Map<String, Object>> loadApprovedGlossaryTerms(IamS1DataAuthorizationSnapshot snapshot) {
        List<GlossaryTerm> terms = glossaryTermMapper.selectList(new LambdaQueryWrapper<GlossaryTerm>()
                .eq(GlossaryTerm::getStatus, GlossaryTerm.STATUS_APPROVED));
        if (terms == null || terms.isEmpty()) return List.of();
        Map<Long, List<String>> relatedColumns = new HashMap<>();
        Set<Long> invalidTerms = new java.util.HashSet<>();
        try {
            List<Long> termIds = terms.stream().map(GlossaryTerm::getId).filter(java.util.Objects::nonNull).toList();
            if (termIds.size() != terms.size()) {
                for (GlossaryTerm term : terms) {
                    if (term.getId() == null) invalidTerms.add(null);
                }
            }
            if (termIds.isEmpty()) return List.of();
            List<MetadataRelationship> relations = metadataRelationshipService.list(
                    new LambdaQueryWrapper<MetadataRelationship>()
                            .eq(MetadataRelationship::getRelationType, MetadataRelationship.TYPE_GLOSSARY_OF)
                            .in(MetadataRelationship::getSourceId, termIds));
            if (relations == null) return List.of();
            Map<Long, MetadataEntity> entityById = new HashMap<>();
            List<Long> entityIds = relations.stream().map(MetadataRelationship::getTargetId)
                    .filter(java.util.Objects::nonNull).distinct().toList();
            List<MetadataEntity> entities = entityIds.isEmpty()
                    ? List.of() : metadataEntityService.listByIds(entityIds);
            if (entities == null) return List.of();
            for (MetadataEntity entity : entities) {
                if (entity != null && entity.getId() != null) entityById.put(entity.getId(), entity);
            }
            Set<Long> knownTermIds = new java.util.HashSet<>(termIds);
            for (MetadataRelationship relation : relations) {
                Long termId = relation.getSourceId();
                if (!knownTermIds.contains(termId)) continue;
                MetadataEntity entity = entityById.get(relation.getTargetId());
                String column = entity == null ? null : normalizeGlossaryColumn(entity.getFqn(), snapshot);
                if (column == null) {
                    invalidTerms.add(termId);
                } else {
                    relatedColumns.computeIfAbsent(termId, ignored -> new ArrayList<>()).add(column);
                }
            }
        } catch (Exception ex) {
            log.warn("S1 术语关联读取失败，按 fail-closed 返回空 glossary", ex);
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (GlossaryTerm term : terms) {
            if (invalidTerms.contains(term.getId())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", term.getName());
            item.put("displayName", term.getDisplayName());
            item.put("description", term.getDescription());
            item.put("synonyms", term.getSynonyms());
            item.put("fqn", term.getFqn());
            if (relatedColumns.containsKey(term.getId())) {
                item.put("columns", relatedColumns.get(term.getId()));
            }
            result.add(item);
        }
        return result;
    }

    private String normalizeGlossaryColumn(String fqn, IamS1DataAuthorizationSnapshot snapshot) {
        if (fqn == null || fqn.isBlank() || snapshot == null || snapshot.getDatasourceName() == null) return null;
        String[] parts = fqn.trim().split("\\.");
        if (parts.length < 4 || !parts[0].equalsIgnoreCase(snapshot.getDatasourceName())) return null;
        String tableName = parts[parts.length - 2];
        String columnName = parts[parts.length - 1];
        if (tableName.isBlank() || columnName.isBlank()) return null;
        for (var table : snapshot.getTables()) {
            if (tableName.equalsIgnoreCase(table.getTableName()) && table.getAllowedColumns().stream()
                    .anyMatch(column -> columnName.equalsIgnoreCase(column))) {
                return tableName.toLowerCase(Locale.ROOT) + "." + columnName.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private List<Map<String, Object>> loadKnowledgeChunks(Long snapshotId, Long datasourceId) {
        List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getMetadataSnapshotId, snapshotId)
                .eq(KnowledgeChunk::getReviewStatus, "APPROVED")
                .eq(KnowledgeChunk::getVectorStatus, "INDEXED")
                .orderByAsc(KnowledgeChunk::getChunkIndex));
        List<Map<String, Object>> result = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks == null ? List.<KnowledgeChunk>of() : chunks) {
            try {
                List<String> tables = chunk.getRelatedTables() == null
                        ? (chunk.getRelatedTable() == null ? List.of() : List.of(chunk.getRelatedTable()))
                        : objectMapper.readValue(chunk.getRelatedTables(), new TypeReference<>() {});
                List<String> columns = chunk.getRelatedColumns() == null
                        ? (chunk.getRelatedColumn() == null || tables.isEmpty() ? List.of() : List.of(tables.get(0) + "." + chunk.getRelatedColumn()))
                        : objectMapper.readValue(chunk.getRelatedColumns(), new TypeReference<>() {});
                if (tables.isEmpty() || columns.isEmpty()) continue;
                Map<String, Object> item = new LinkedHashMap<>(); item.put("datasourceId", datasourceId);
                item.put("activeMetadataSnapshotId", snapshotId); item.put("tables", tables); item.put("columns", columns);
                item.put("chunkText", chunk.getChunkText()); item.put("chunkType", chunk.getChunkType()); item.put("docId", chunk.getDocId()); item.put("versionNo", chunk.getVersionNo());
                result.add(item);
            } catch (Exception ignored) {
                // 来源字段不完整时安全降级为无 RAG 上下文。
            }
        }
        return result;
    }

    private Map<String, Object> connectionConfig(Long datasourceId) {
        Datasource datasource = datasourceMapper.selectById(datasourceId); DatasourceSecret secret = datasourceSecretMapper.selectOne(new LambdaQueryWrapper<DatasourceSecret>().eq(DatasourceSecret::getDatasourceId, datasourceId));
        if (datasource == null || secret == null) throw new BusinessException("数据源连接配置不完整");
        Map<String, Object> config = new LinkedHashMap<>(); config.put("host", datasource.getHost()); config.put("port", datasource.getPort()); config.put("database", datasource.getDatabaseName()); config.put("username", secret.getUsername()); config.put("password", datasourceSecretService.decrypt(secret.getEncryptedPassword())); return config;
    }

    private Map<String, Object> capabilities(Long userId, Long datasourceId) { Map<String, Object> result = new LinkedHashMap<>(); result.put("query", authorizationResolver.hasGlobalFunction(userId, "query:use")); result.put("viewSql", authorizationResolver.hasGlobalFunction(userId, "query:sql:view")); result.put("export", authorizationResolver.hasGlobalFunction(userId, "query:export")); return result; }

    private QueryTask find(String taskId, Long userId) { QueryTask task = queryTaskMapper.selectOne(new LambdaQueryWrapper<QueryTask>().eq(QueryTask::getTaskId, taskId).eq(QueryTask::getUserId, userId)); if (task == null) throw new BusinessException("S1 任务不存在或无权访问"); return task; }
    private boolean isS1(QueryTask task) { return IamS1Constants.PROTOCOL_VERSION.equals(task.getIamProtocolVersion()); }
    private void requireProtocol(String protocol) { if (!IamS1Constants.PROTOCOL_VERSION.equals(protocol)) throw new BusinessException("未知 IAM-SIMPLE-1 协议"); }
    private void requireExplicitUsages(List<IamS1TableRequestDTO> tables) {
        for (IamS1TableRequestDTO table : tables) {
            if (table == null || table.getTableName() == null || table.getTableName().isBlank()
                    || table.getReferencedColumns() == null || table.getReferencedColumns().isEmpty()
                    || table.getColumnUsages() == null) {
                throw new BusinessException("S1 表字段范围或字段 usage 缺失：请求必须声明表、字段和每个字段的 usage");
            }
            for (String column : table.getReferencedColumns()) {
                if (column == null || column.isBlank() || !table.getColumnUsages().containsKey(column)
                        || table.getColumnUsages().get(column) == null || table.getColumnUsages().get(column).isEmpty()) {
                    // 明确给出缺失位置，避免前端只能看到“usage 缺失”而定位不到字段。
                    throw new BusinessException("S1 字段 usage 缺失：表 " + table.getTableName()
                            + " 的字段 " + column + " 没有声明使用位置（默认应为 PROJECTION/FILTER/JOIN）");
                }
            }
        }
    }
    private String writeJson(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception ex) { throw new BusinessException("S1 安全摘要序列化失败"); } }
    private String safeSql(String sql) { return sql == null ? null : sql.replaceAll("(?i)('(?:''|[^'])*')", "?"); }
    private String safeExplanation(String value) { return value == null ? null : value.replaceAll("(?i)(select|from|where)\\s+[^\\n]{0,200}", "SQL 说明已隐藏"); }
    private String safeChartConfig(Object value, Map<String, String> outputMasks) {
        if (value == null || (outputMasks != null && !outputMasks.isEmpty())) return null;
        Object chart = objectMapper.convertValue(value, Object.class);
        return writeJson(sanitizeChartNode(chart, outputMasks));
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeChartNode(Object value, Map<String, String> outputMasks) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object item = entry.getValue();
                if ("data".equals(key) && raw.get("name") != null) {
                    String strategy = outputMasks.get(String.valueOf(raw.get("name")));
                    if (strategy != null && item instanceof List<?> values) {
                        item = values.stream().map(element -> element == null ? null
                                : maskingService.maskValue(String.valueOf(element), strategy)).toList();
                    }
                }
                result.put(key, sanitizeChartNode(item, outputMasks));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> sanitizeChartNode(item, outputMasks)).toList();
        }
        return value;
    }
    private List<String> safeSuggestions(Object value) { return value instanceof List<?> list ? list.stream().filter(item -> item instanceof String).map(String.class::cast).limit(5).toList() : List.of(); }
    private String safeError(String error) { return error == null || error.isBlank() ? "S1 查询失败" : error.replaceAll("(?i)(iam_s1_[a-z0-9_-]+)", "?"); }
    private Integer number(Object value) { return value instanceof Number n ? n.intValue() : 0; }

    private void fail(String taskId, String message, String protection) {
        QueryTask task = queryTaskMapper.selectOne(new LambdaQueryWrapper<QueryTask>().eq(QueryTask::getTaskId, taskId));
        int updated = queryTaskMapper.update(null, new LambdaUpdateWrapper<QueryTask>()
                .eq(QueryTask::getTaskId, taskId)
                .eq(QueryTask::getStatus, QueryTaskStatus.PROCESSING.name())
                .set(QueryTask::getStatus, QueryTaskStatus.FAILED.name())
                .set(QueryTask::getErrorMessage, message)
                .set(QueryTask::getIamFinalProtectionStatus, protection)
                .set(QueryTask::getCompletedAt, LocalDateTime.now()));
        if (updated > 0 && task != null && task.getConversationId() != null) {
            Map<String, Object> failureMetadata = new LinkedHashMap<>();
            failureMetadata.put("taskId", taskId);
            failureMetadata.put("status", QueryTaskStatus.FAILED.name());
            failureMetadata.put("question", task.getQuestion());
            failureMetadata.put("errorMessage", message);
            conversationService.saveAssistantMessage(task.getConversationId(), message, taskId,
                    writeJson(failureMetadata));
            refreshConversationContext(task.getConversationId(), task.getUserId(), taskId);
        }
    }

    private void saveCompletedConversationMessage(QueryTask task, Map<String, Object> rawResult,
                                                   List<Map<String, Object>> data,
                                                   Map<String, String> outputMasks,
                                                   String safeSql, String safeExplanation,
                                                   String safeChart, List<String> suggestions) {
        if (task.getConversationId() == null) return;
        boolean canViewSql = authorizationResolver.hasGlobalFunction(task.getUserId(), "query:sql:view");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("taskId", task.getTaskId());
        metadata.put("status", QueryTaskStatus.COMPLETED.name());
        metadata.put("question", task.getQuestion());
        metadata.put("sql", canViewSql ? safeSql : null);
        metadata.put("sqlExplanation", canViewSql ? safeExplanation : null);
        metadata.put("data", data);
        metadata.put("columns", rawResult.get("columns"));
        metadata.put("rowCount", data.size());
        metadata.put("chartConfig", safeChart);
        metadata.put("suggestedQuestions", suggestions);
        metadata.put("usedTables", rawResult.getOrDefault("usedTables", List.of()));
        metadata.put("usedColumns", rawResult.getOrDefault("usedColumns", List.of()));
        metadata.put("maskedFields", outputMasks);
        metadata.put("canExport", authorizationResolver.hasGlobalFunction(task.getUserId(), "query:export"));
        metadata.put("degraded", Boolean.TRUE.equals(rawResult.get("degraded")));
        metadata.put("degradeNotice", rawResult.get("degradeNotice"));
        metadata.put("totalTimeMs", number(rawResult.get("totalTimeMs")));
        String content = safeExplanation == null || safeExplanation.isBlank() ? "查询完成" : safeExplanation;
        conversationService.saveAssistantMessage(task.getConversationId(), content, task.getTaskId(), writeJson(metadata));
    }

    @SuppressWarnings("unchecked")
    private QueryTaskVO toVO(QueryTask task) {
        try {
            List<Map<String, Object>> data = task.getResultData() == null ? null : objectMapper.readValue(task.getResultData(), new TypeReference<>() {});
            List<Map<String, String>> columns = task.getResultColumns() == null ? null : objectMapper.readValue(task.getResultColumns(), new TypeReference<>() {});
            Map<String, Object> trace = task.getIamSourceTrace() == null ? null : objectMapper.readValue(task.getIamSourceTrace(), new TypeReference<>() {});
            Map<String, Object> caps = task.getIamCapabilities() == null ? Map.of() : objectMapper.readValue(task.getIamCapabilities(), new TypeReference<>() {});
            Map<String, Object> chart = task.getChartConfig() == null ? null : objectMapper.readValue(task.getChartConfig(), new TypeReference<>() {});
            List<String> suggestions = task.getSuggestedQuestions() == null ? List.of() : objectMapper.readValue(task.getSuggestedQuestions(), new TypeReference<>() {});
            return QueryTaskVO.builder().taskId(task.getTaskId()).status(task.getStatus()).question(task.getQuestion()).sql(task.getResultSql()).sqlExplanation(task.getSqlExplanation()).data(data).columns(columns).rowCount(data == null ? 0 : data.size()).chartConfig(chart).suggestedQuestions(suggestions).usedTables(readList(task.getUsedTables())).usedColumns(readList(task.getUsedColumns())).errorMessage(task.getErrorMessage()).degraded(task.getDegraded()).degradeNotice(task.getDegradeNotice()).retryCount(task.getRetryCount()).totalTimeMs(task.getTotalTimeMs()).protocolVersion(task.getIamProtocolVersion()).activeMetadataSnapshotId(task.getActiveMetadataSnapshotId()).permissionRevision(task.getPermissionRevision()).sourceTrace(trace).finalProtectionStatus(task.getIamFinalProtectionStatus()).canViewSql(Boolean.TRUE.equals(caps.get("viewSql"))).canExport(Boolean.TRUE.equals(caps.get("export"))).createdAt(task.getCreatedAt()).completedAt(task.getCompletedAt()).build();
        } catch (Exception ex) { throw new BusinessException("S1 结果读取失败"); }
    }
    private List<String> readList(String json) { try { return json == null ? List.of() : objectMapper.readValue(json, new TypeReference<>() {}); } catch (Exception ex) { return List.of(); } }
    private void applyCurrentProtection(QueryTaskVO vo, QueryTask task, IamS1DataAuthorizationSnapshot current) {
        // 失败任务没有结果载荷，可被再保护的字段集为空。此时必须放行读取，
        // 否则真实失败原因会被"权限已变化"覆盖，失败任务也无法再次读取。
        if (vo.getData() == null && vo.getColumns() == null) return;
        if (!isSubsetOfCurrent(vo.getUsedColumns(), current)) throw new BusinessException("权限已变化，请重新查询");
        Map<String, String> masks;
        try {
            masks = deriveOutputMasks(vo.getSourceTrace(), current);
        } catch (IamS1MaskPolicyConflictException ex) {
            // 读取阶段同样 fail-closed：历史任务或异常来源带来的冲突 trace 不得返回结果。
            throw new BusinessException("结果脱敏策略冲突，已拒绝返回");
        }
        if (!masks.isEmpty()) vo.setData(maskingService.maskResultByFields(vo.getData(), masks)); vo.setMaskedFields(masks);
    }

    private boolean containsRowCondition(String snapshotJson) {
        return snapshotJson != null && snapshotJson.contains("\"rowCondition\":{");
    }

    private boolean hasRowCondition(IamS1DataAuthorizationSnapshot snapshot) {
        return snapshot.getTables().stream().flatMap(table -> table.getGrantSources().stream())
                .anyMatch(source -> source.getRowCondition() != null);
    }

    @SuppressWarnings("unchecked")
    private boolean hasCompletePersistedEvidence(QueryTask task) {
        try {
            List<String> usedColumns = objectMapper.readValue(task.getUsedColumns(), new TypeReference<>() {});
            Object trace = objectMapper.readValue(task.getIamSourceTrace(), Object.class);
            Object columns = objectMapper.readValue(task.getResultColumns(), Object.class);
            if (trace instanceof Map<?, ?> wrapper) trace = wrapper.get("entries");
            return isSubsetEvidencePresent(usedColumns) && hasCompleteSourceTrace(trace, columns);
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isSubsetEvidencePresent(List<String> usedColumns) {
        return usedColumns != null && !usedColumns.isEmpty()
                && usedColumns.stream().allMatch(item -> item != null && !item.isBlank());
    }

    @SuppressWarnings("unchecked")
    private boolean hasCompleteSourceTrace(Object rawTrace, Object rawColumns) {
        if (!(rawTrace instanceof List<?> entries) || entries.isEmpty()) return false;
        java.util.Set<String> outputs = new java.util.HashSet<>();
        if (!(rawColumns instanceof List<?> columns) || columns.isEmpty()) return false;
        for (Object rawColumn : columns) {
            if (rawColumn instanceof Map<?, ?> column && column.get("name") != null) {
                outputs.add(String.valueOf(column.get("name")));
            }
        }
        if (outputs.isEmpty()) return false;
        java.util.Set<String> traced = new java.util.HashSet<>();
        for (Object rawEntry : entries) {
            if (!(rawEntry instanceof Map<?, ?> entry) || entry.get("outputColumn") == null
                    || !(entry.get("sources") instanceof List<?> sources) || sources.isEmpty()) return false;
            String output = String.valueOf(entry.get("outputColumn"));
            if (sources.stream().anyMatch(source -> source == null || String.valueOf(source).isBlank())) return false;
            traced.add(output);
        }
        return traced.containsAll(outputs);
    }
}
