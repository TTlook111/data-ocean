package com.dataocean.module.query.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
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
import com.dataocean.module.query.entity.query.QueryHistoryQuery;
import com.dataocean.module.query.entity.vo.QueryTaskVO;
import com.dataocean.module.query.enums.QueryTaskStatus;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.query.service.IamS1QueryService;
import com.dataocean.module.query.service.IamS1RowBindingService;
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
import java.util.List;
import java.util.Map;
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
    private final DatasourceMapper datasourceMapper;
    private final DatasourceSecretMapper datasourceSecretMapper;
    private final DatasourceSecretService datasourceSecretService;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final AuditLogService auditLogService;
    private final com.dataocean.module.metadata.service.SchemaSnapshotService schemaSnapshotService;
    private final com.dataocean.module.permission.service.DataMaskingService maskingService;

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
                .question(request.getQuestion()).conversationId(request.getConversationId())
                .status(QueryTaskStatus.PROCESSING.name()).retryCount(0).createdAt(LocalDateTime.now())
                .build();
        queryTaskMapper.insert(task);

        Map<String, Object> pythonRequest = buildPythonRequest(taskId, userId, request, snapshot,
                safeSnapshot, capabilities, bindings);
        Runnable dispatch = () -> pythonClient.executeAsync(taskId, pythonRequest, result -> {
            complete(taskId, result);
            try {
                QueryTask completed = queryTaskMapper.selectOne(new LambdaQueryWrapper<QueryTask>()
                        .eq(QueryTask::getTaskId, taskId));
                if (completed != null) sseController.sendResult(taskId, toVO(completed));
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
            Map<String, String> outputMasks = deriveOutputMasks(result.get("sourceTrace"), current);
            data = maskingService.maskResultByFields(data, outputMasks);
            LambdaUpdateWrapper<QueryTask> update = new LambdaUpdateWrapper<QueryTask>()
                    .eq(QueryTask::getTaskId, taskId).eq(QueryTask::getStatus, QueryTaskStatus.PROCESSING.name())
                    .set(QueryTask::getStatus, "COMPLETED").set(QueryTask::getResultSql, safeSql((String) result.get("sql")))
                    .set(QueryTask::getSqlExplanation, safeExplanation((String) result.get("sqlExplanation")))
                    .set(QueryTask::getResultData, writeJson(data)).set(QueryTask::getResultColumns, writeJson(result.get("columns")))
                    .set(QueryTask::getChartConfig, safeChartConfig(result.get("chartConfig")))
                    .set(QueryTask::getSuggestedQuestions, writeJson(safeSuggestions(result.get("suggestedQuestions"))))
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
            queryTaskMapper.update(null, update);
            auditLogService.recordAudit(task.getId());
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
                fields.put((table.getTableName() + "." + field.getColumnName()).toLowerCase(), field)));
        for (String value : usedColumns) {
            IamS1FieldProtectionVO protection = fields.get(value.toLowerCase());
            if (protection == null || "HIDDEN".equals(protection.getProtectionLevel())) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> deriveOutputMasks(Object rawTrace, IamS1DataAuthorizationSnapshot snapshot) {
        Map<String, IamS1FieldProtectionVO> fieldMap = new HashMap<>();
        snapshot.getTables().forEach(table -> table.getFieldProtections().forEach(field ->
                fieldMap.put((table.getTableName() + "." + field.getColumnName()).toLowerCase(), field)));
        Map<String, String> result = new LinkedHashMap<>();
        if (rawTrace instanceof Map<?, ?> wrapper) {
            rawTrace = wrapper.get("entries");
        }
        if (rawTrace instanceof List<?> traces) {
            for (Object raw : traces) {
                if (!(raw instanceof Map<?, ?> trace)) continue;
                String output = String.valueOf(trace.get("outputColumn"));
                Object source = trace.get("sources");
                if (!(source instanceof List<?> sources)) continue;
                for (Object item : sources) {
                    IamS1FieldProtectionVO protection = fieldMap.get(String.valueOf(item).toLowerCase());
                    if (protection != null && "MASKED".equals(protection.getProtectionLevel())) {
                        result.put(output, protection.getMaskPolicy());
                    }
                }
            }
        }
        return result;
    }

    private Map<String, Object> buildSnapshot(String taskId, IamS1QueryAskRequestDTO request,
                                               IamS1DataAuthorizationSnapshot snapshot) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("protocolVersion", IamS1Constants.PROTOCOL_VERSION); output.put("taskId", taskId);
        output.put("userId", snapshot.getUserId()); output.put("datasourceId", snapshot.getDatasourceId());
        output.put("activeMetadataSnapshotId", snapshot.getActiveMetadataSnapshotId());
        output.put("permissionRevision", snapshot.getPermissionRevision()); output.put("calculatedAt", snapshot.getCalculatedAt());
        output.put("nextEffectiveAt", snapshot.getNextEffectiveAt());
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

    private Map<String, Object> grantMap(IamS1GrantSourceVO source) {
        Map<String, Object> map = new LinkedHashMap<>(); map.put("grantId", source.getGrantId()); map.put("sourceSummary", source.getSourceSummary());
        map.put("grantSource", source.getGrantSource()); map.put("sourceReferenceId", source.getSourceReferenceId()); map.put("explicitColumns", source.getExplicitColumns());
        map.put("rowCondition", source.getRowCondition()); return map;
    }

    private Map<String, Object> buildPythonRequest(String taskId, Long userId, IamS1QueryAskRequestDTO request,
                                                   IamS1DataAuthorizationSnapshot snapshot, Map<String, Object> safeSnapshot,
                                                   Map<String, Object> capabilities, List<IamS1ExecutionBinding> bindings) {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("protocolVersion", IamS1Constants.PROTOCOL_VERSION); body.put("taskId", taskId);
        body.put("userId", userId); body.put("datasourceId", request.getDatasourceId()); body.put("activeMetadataSnapshotId", snapshot.getActiveMetadataSnapshotId());
        body.put("permissionRevision", snapshot.getPermissionRevision()); body.put("permissionSnapshot", safeSnapshot); body.put("executionBindings", bindings);
        body.put("question", request.getQuestion()); body.put("connectionConfig", connectionConfig(request.getDatasourceId()));
        List<Map<String, Object>> chunks = loadKnowledgeChunks(snapshot.getActiveMetadataSnapshotId(), request.getDatasourceId());
        body.put("conversationHistory", List.of()); body.put("conversationSummary", null); body.put("ragChunks", chunks);
        body.put("fallbackChunks", chunks); body.put("glossaryTerms", List.of()); body.put("fewShotExamples", List.of()); return body;
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
                throw new BusinessException("S1 表字段范围或字段 usage 缺失");
            }
            for (String column : table.getReferencedColumns()) {
                if (column == null || column.isBlank() || !table.getColumnUsages().containsKey(column)
                        || table.getColumnUsages().get(column) == null || table.getColumnUsages().get(column).isEmpty()) {
                    throw new BusinessException("S1 字段 usage 缺失");
                }
            }
        }
    }
    private String writeJson(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception ex) { throw new BusinessException("S1 安全摘要序列化失败"); } }
    private String safeSql(String sql) { return sql == null ? null : sql.replaceAll("(?i)('(?:''|[^'])*')", "?"); }
    private String safeExplanation(String value) { return value == null ? null : value.replaceAll("(?i)(select|from|where)\\s+[^\\n]{0,200}", "SQL 说明已隐藏"); }
    private String safeChartConfig(Object value) { return value == null ? null : writeJson(Map.of("status", "chart_not_emitted_by_b3")); }
    private List<String> safeSuggestions(Object value) { return value instanceof List<?> list ? list.stream().filter(item -> item instanceof String).map(String.class::cast).limit(5).toList() : List.of(); }
    private String safeError(String error) { return error == null || error.isBlank() ? "S1 查询失败" : error.replaceAll("(?i)(iam_s1_[a-z0-9_-]+)", "?"); }
    private Integer number(Object value) { return value instanceof Number n ? n.intValue() : 0; }
    private void fail(String taskId, String message, String protection) { queryTaskMapper.update(null, new LambdaUpdateWrapper<QueryTask>().eq(QueryTask::getTaskId, taskId).set(QueryTask::getStatus, QueryTaskStatus.FAILED.name()).set(QueryTask::getErrorMessage, message).set(QueryTask::getIamFinalProtectionStatus, protection).set(QueryTask::getCompletedAt, LocalDateTime.now())); }

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
    private void applyCurrentProtection(QueryTaskVO vo, QueryTask task, IamS1DataAuthorizationSnapshot current) { if (!isSubsetOfCurrent(vo.getUsedColumns(), current)) throw new BusinessException("权限已变化，请重新查询"); Map<String, String> masks = deriveOutputMasks(vo.getSourceTrace(), current); if (!masks.isEmpty()) vo.setData(maskingService.maskResultByFields(vo.getData(), masks)); vo.setMaskedFields(masks); }

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
