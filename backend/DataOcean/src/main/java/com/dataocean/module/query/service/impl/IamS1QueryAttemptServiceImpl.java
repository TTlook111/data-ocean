package com.dataocean.module.query.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.DataMaskingService;
import com.dataocean.module.metadata.service.SchemaSnapshotService;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.dto.IamS1DataAuthorizationRequestDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1RowConditionDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1TableRequestDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot;
import com.dataocean.module.permission.s1.entity.vo.IamS1FieldProtectionVO;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1DataAuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1UserResourceService;
import com.dataocean.module.query.entity.QueryAttempt;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.entity.dto.IamS1AttemptAuthorizeRequestDTO;
import com.dataocean.module.query.entity.dto.IamS1AttemptColumnEvidenceDTO;
import com.dataocean.module.query.entity.dto.IamS1AttemptResourceEvidenceDTO;
import com.dataocean.module.query.entity.dto.IamS1AttemptResultRequestDTO;
import com.dataocean.module.query.entity.dto.IamS1QueryAskRequestDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1QueryCandidateCatalogVO;
import com.dataocean.module.query.enums.QueryTaskStatus;
import com.dataocean.module.query.mapper.QueryAttemptMapper;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.query.service.ConversationService;
import com.dataocean.module.query.service.IamS1QueryAttemptService;
import com.dataocean.module.query.service.IamS1RowBindingService;
import com.dataocean.module.query.entity.dto.IamS1ExecutionBinding;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Java is the per-attempt IAM decision point and sole raw-result protection boundary. */
@Service
@RequiredArgsConstructor
public class IamS1QueryAttemptServiceImpl implements IamS1QueryAttemptService {

    private static final int MAX_SQL_ATTEMPTS = 3;
    private static final int MAX_RESULT_ROWS = 10_000;
    private static final String PROTECTED = "PROTECTED";

    private final QueryTaskMapper queryTaskMapper;
    private final QueryAttemptMapper queryAttemptMapper;
    private final IamS1DataAuthorizationResolver dataResolver;
    private final IamS1AuthorizationResolver authorizationResolver;
    private final IamS1RowBindingService rowBindingService;
    private final ConversationService conversationService;
    private final IamS1UserResourceService userResourceService;
    private final SchemaSnapshotService schemaSnapshotService;
    private final DataMaskingService maskingService;
    private final ObjectMapper objectMapper;
    private final IamS1QueryServiceImpl queryService;

    @Override
    @Transactional
    public Map<String, Object> authorize(String taskId, IamS1AttemptAuthorizeRequestDTO request) {
        QueryTask task = queryTaskMapper.selectByTaskIdForUpdate(taskId);
        requireLiveTask(task);
        verifySqlHash(request.getSql(), request.getSqlHash());
        List<IamS1TableRequestDTO> exactResources = exactResources(task, request);
        QueryAttempt previous = queryAttemptMapper.selectForUpdate(taskId, request.getAttemptId());
        if (previous != null && !Objects.equals(previous.getSqlHash(), request.getSqlHash())) {
            return denied("同一执行尝试对应了不同 SQL，拒绝继续");
        }
        if (previous != null && PROTECTED.equals(previous.getStatus())) {
            requireCurrentRevision(task);
            return protectedAttempt(previous);
        }
        if (previous != null && "EXECUTING".equals(previous.getStatus())) {
            previous.setStatus("UNCERTAIN");
            previous.setErrorMessage("执行结果未能安全确认，请重新提问");
            queryAttemptMapper.updateById(previous);
            return denied("执行状态无法确认；为避免重复运行，本任务已停止，请重新提问");
        }
        if (previous != null && !"AUTHORIZED".equals(previous.getStatus())) {
            return denied("该 SQL 尝试已终止；请使用新的尝试或重新提问");
        }

        int attemptNo;
        if (previous == null) {
            Long count = queryAttemptMapper.selectCount(new LambdaQueryWrapper<QueryAttempt>()
                    .eq(QueryAttempt::getTaskId, taskId));
            attemptNo = count == null ? 1 : count.intValue() + 1;
            if (attemptNo > MAX_SQL_ATTEMPTS) return denied("已达到本次查询的 SQL 尝试上限");
        } else {
            attemptNo = previous.getAttemptNo();
        }

        var currentMetadata = schemaSnapshotService.getPublishedSnapshot(task.getDatasourceId());
        if (currentMetadata == null || !Objects.equals(currentMetadata.getId(), task.getActiveMetadataSnapshotId())) {
            markRejected(task, previous, request, attemptNo, "当前元数据快照已变化");
            return denied("当前元数据快照已变化，请重新提问");
        }
        Long currentRevision = dataResolver.currentPermissionRevision();
        if (!Objects.equals(currentRevision, task.getPermissionRevision())) {
            markRejected(task, previous, request, attemptNo, "当前授权修订已变化");
            return denied("当前数据权限已变化，请重新提问");
        }
        if (!authorizationResolver.hasGlobalFunction(task.getUserId(), "query:use")) {
            markRejected(task, previous, request, attemptNo, "问数能力已撤销");
            return denied("问数权限已变化，请重新提问");
        }

        IamS1QueryAskRequestDTO exactRequest = new IamS1QueryAskRequestDTO();
        exactRequest.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
        exactRequest.setDatasourceId(task.getDatasourceId());
        exactRequest.setQuestion(task.getQuestion());
        exactRequest.setTables(exactResources);
        IamS1DataAuthorizationRequestDTO authorizationRequest = new IamS1DataAuthorizationRequestDTO();
        authorizationRequest.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
        authorizationRequest.setUserId(task.getUserId());
        authorizationRequest.setDatasourceId(task.getDatasourceId());
        authorizationRequest.setActiveMetadataSnapshotId(task.getActiveMetadataSnapshotId());
        authorizationRequest.setCalculatedAt(LocalDateTime.now());
        authorizationRequest.setTables(exactResources);
        IamS1DataAuthorizationSnapshot current = dataResolver.resolve(authorizationRequest);
        if (current == null || !current.isAllowed()
                || !Objects.equals(current.getPermissionRevision(), task.getPermissionRevision())
                || !Objects.equals(current.getActiveMetadataSnapshotId(), task.getActiveMetadataSnapshotId())) {
            markRejected(task, previous, request, attemptNo, "当前 S1 资源授权不允许执行");
            return denied("当前 S1 数据范围不允许执行该查询");
        }

        List<IamS1ExecutionBinding> bindings = rowBindingService.build(current);
        String resourceJson = queryService.writeJson(exactResources);
        String safeSnapshot = queryService.writeJson(queryService.buildSnapshot(taskId, exactRequest, current));
        QueryAttempt attempt = previous == null
                ? QueryAttempt.builder().taskId(taskId).attemptId(request.getAttemptId()).attemptNo(attemptNo)
                    .createdAt(LocalDateTime.now()).build()
                : previous;
        attempt.setSqlHash(request.getSqlHash());
        attempt.setSafeSql(queryService.safeSql(request.getSql()));
        attempt.setStatus("AUTHORIZED");
        attempt.setPermissionRevision(current.getPermissionRevision());
        attempt.setActiveMetadataSnapshotId(current.getActiveMetadataSnapshotId());
        attempt.setResourceRequestJson(resourceJson);
        attempt.setExecutionSnapshotJson(safeSnapshot);
        attempt.setUsedTables(queryService.writeJson(request.getUsedTables()));
        attempt.setUsedColumns(queryService.writeJson(request.getUsedColumns()));
        attempt.setErrorMessage(null);
        attempt.setUpdatedAt(LocalDateTime.now());
        if (previous == null) queryAttemptMapper.insert(attempt);
        else queryAttemptMapper.updateById(attempt);

        task.setIamResourceRequest(resourceJson);
        task.setIamExecutionSnapshot(safeSnapshot);
        queryTaskMapper.updateById(task);
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("allowed", true);
        decision.put("status", "AUTHORIZED");
        decision.put("attemptId", request.getAttemptId());
        decision.put("attemptNo", attemptNo);
        decision.put("sqlHash", request.getSqlHash());
        decision.put("permissionRevision", current.getPermissionRevision());
        decision.put("activeMetadataSnapshotId", current.getActiveMetadataSnapshotId());
        decision.put("permissionSnapshot", queryService.buildSnapshot(taskId, exactRequest, current));
        decision.put("executionBindings", bindings);
        decision.put("connectionConfig", queryService.connectionConfig(task.getDatasourceId()));
        return decision;
    }

    @Override
    @Transactional
    public Map<String, Object> markExecuting(String taskId, String attemptId, String sqlHash) {
        QueryTask task = queryTaskMapper.selectByTaskIdForUpdate(taskId);
        requireLiveTask(task);
        requireCurrentRevision(task);
        QueryAttempt attempt = queryAttemptMapper.selectForUpdate(taskId, attemptId);
        if (attempt == null || !Objects.equals(attempt.getSqlHash(), sqlHash)) return denied("执行尝试身份不一致");
        if (PROTECTED.equals(attempt.getStatus())) return protectedAttempt(attempt);
        if ("EXECUTING".equals(attempt.getStatus()) || "UNCERTAIN".equals(attempt.getStatus())) {
            return denied("该尝试已进入执行阶段；为避免重复执行，请重新提问");
        }
        if (!"AUTHORIZED".equals(attempt.getStatus())) return denied("执行尝试未获当前 Java 授权");
        attempt.setStatus("EXECUTING");
        attempt.setUpdatedAt(LocalDateTime.now());
        queryAttemptMapper.updateById(attempt);
        return Map.of("allowed", true, "status", "EXECUTING", "attemptId", attemptId, "sqlHash", sqlHash);
    }

    @Override
    @Transactional
    public Map<String, Object> protectResult(String taskId, IamS1AttemptResultRequestDTO request) {
        QueryTask task = queryTaskMapper.selectByTaskIdForUpdate(taskId);
        requireLiveTask(task);
        requireCurrentRevision(task);
        QueryAttempt attempt = queryAttemptMapper.selectForUpdate(taskId, request.getAttemptId());
        if (attempt == null || !Objects.equals(attempt.getSqlHash(), request.getSqlHash())) return denied("结果尝试身份不一致");
        if (PROTECTED.equals(attempt.getStatus())) return protectedAttempt(attempt);
        if (!"EXECUTING".equals(attempt.getStatus())) return denied("结果未对应到已授权的执行尝试");

        if (!request.isSuccess()) {
            attempt.setStatus("FAILED");
            attempt.setErrorMessage(queryService.safeError(request.getError()));
            attempt.setUpdatedAt(LocalDateTime.now());
            attempt.setCompletedAt(LocalDateTime.now());
            queryAttemptMapper.updateById(attempt);
            return Map.of("success", false, "attemptId", attempt.getAttemptId(),
                    "error", attempt.getErrorMessage() == null ? "只读查询执行失败" : attempt.getErrorMessage());
        }
        if (!sameSet(readStringList(attempt.getUsedTables()), request.getUsedTables())
                || !sameSet(readStringList(attempt.getUsedColumns()), request.getUsedColumns())) {
            return rejectAttempt(attempt, "执行结果 AST 来源与 Java 授权范围不一致");
        }
        if (!queryService.hasCompleteSourceTrace(request.getSourceTrace(), request.getColumns())) {
            return rejectAttempt(attempt, "执行结果缺少完整字段来源证据");
        }
        Set<String> declared = lowerSet(readStringList(attempt.getUsedColumns()));
        if (!traceSources(request.getSourceTrace()).stream().allMatch(declared::contains)) {
            return rejectAttempt(attempt, "执行结果来源超出 Java 授权范围");
        }
        IamS1DataAuthorizationSnapshot current = queryService.recheck(task, task.getUserId());
        if (current == null || !current.isAllowed()
                || !Objects.equals(current.getPermissionRevision(), attempt.getPermissionRevision())
                || !Objects.equals(current.getActiveMetadataSnapshotId(), attempt.getActiveMetadataSnapshotId())) {
            return rejectAttempt(attempt, "执行期间权限或快照已变化");
        }
        List<Map<String, Object>> rawRows = request.getData() == null ? List.of() : request.getData();
        if (rawRows.size() > MAX_RESULT_ROWS) return rejectAttempt(attempt, "执行结果超过安全行数上限");
        if (!IamS1ResultIntegrity.dataKeysAreCoveredByColumns(rawRows, request.getColumns())) {
            return rejectAttempt(attempt, "执行结果包含未声明列，拒绝保护");
        }
        Map<String, String> masks = queryService.deriveOutputMasks(request.getSourceTrace(), current);
        List<Map<String, Object>> protectedRows = maskingService.maskResultByFields(rawRows, masks);
        List<Map<String, Object>> safeTrace = sanitizeTrace(request.getSourceTrace());
        attempt.setProtectedData(queryService.writeJson(protectedRows));
        attempt.setProtectedColumns(queryService.writeJson(request.getColumns() == null ? List.of() : request.getColumns()));
        attempt.setSourceTrace(queryService.writeJson(safeTrace));
        attempt.setMaskedFields(queryService.writeJson(masks));
        attempt.setUsedTables(queryService.writeJson(request.getUsedTables()));
        attempt.setUsedColumns(queryService.writeJson(request.getUsedColumns()));
        attempt.setRowCount(protectedRows.size());
        attempt.setExecutionTimeMs(request.getExecutionTimeMs());
        attempt.setStatus(PROTECTED);
        attempt.setUpdatedAt(LocalDateTime.now());
        attempt.setCompletedAt(LocalDateTime.now());
        queryAttemptMapper.updateById(attempt);
        return protectedAttempt(attempt);
    }

    private List<IamS1TableRequestDTO> exactResources(QueryTask task, IamS1AttemptAuthorizeRequestDTO request) {
        if (!Objects.equals(sha256(request.getSql()), request.getSqlHash())) {
            throw new BusinessException("SQL 与 AST 证据摘要不一致");
        }
        Set<String> usedTables = lowerSet(request.getUsedTables());
        Set<String> declaredTables = new LinkedHashSet<>();
        Set<String> declaredColumns = new LinkedHashSet<>();
        Map<String, IamS1TableRequestDTO> byName = new LinkedHashMap<>();
        for (IamS1AttemptResourceEvidenceDTO resource : request.getResources()) {
            String tableName = resource.getTableName().trim();
            if (!declaredTables.add(tableName.toLowerCase(Locale.ROOT))) {
                throw new BusinessException("AST 证据包含重复表");
            }
            IamS1TableRequestDTO table = new IamS1TableRequestDTO(tableName, Set.of());
            Map<String, Set<com.dataocean.module.permission.s1.enums.IamS1ColumnUsage>> usages = new LinkedHashMap<>();
            for (IamS1AttemptColumnEvidenceDTO column : resource.getColumns() == null
                    ? List.<IamS1AttemptColumnEvidenceDTO>of() : resource.getColumns()) {
                String columnName = column.getColumnName().trim();
                String fqn = tableName.toLowerCase(Locale.ROOT) + "." + columnName.toLowerCase(Locale.ROOT);
                if (!declaredColumns.add(fqn)) throw new BusinessException("AST 证据包含重复字段");
                usages.put(columnName, Set.copyOf(column.getUsages()));
            }
            table.setReferencedColumns(new LinkedHashSet<>(usages.keySet()));
            table.setColumnUsages(usages);
            byName.put(tableName.toLowerCase(Locale.ROOT), table);
        }
        if (!usedTables.equals(declaredTables)) throw new BusinessException("AST 表证据与资源声明不一致");
        if (!declaredColumns.equals(lowerSet(request.getUsedColumns()))) throw new BusinessException("AST 字段证据与资源声明不一致");

        if (declaredColumns.isEmpty()) {
            // COUNT(*) has no column references. Require at least one currently visible
            // field on each physical table as the table-access authorization anchor.
            IamS1QueryCandidateCatalogVO catalog = userResourceService.candidateCatalog(
                    task.getUserId(), task.getDatasourceId(), task.getActiveMetadataSnapshotId());
            for (String tableName : usedTables) {
                var candidate = catalog.tables().stream()
                        .filter(table -> table.tableName().equalsIgnoreCase(tableName)).findFirst()
                        .orElseThrow(() -> new BusinessException("AST 表不在当前可见候选范围内"));
                if (candidate.columns().isEmpty()) throw new BusinessException("COUNT(*) 缺少可授权字段锚点");
                var first = candidate.columns().get(0);
                var table = byName.get(tableName);
                if (table == null) {
                    table = new IamS1TableRequestDTO(candidate.tableName(), Set.of());
                    table.setReferencedColumns(new LinkedHashSet<>());
                    table.setColumnUsages(new LinkedHashMap<>());
                    byName.put(tableName, table);
                }
                table.getReferencedColumns().add(first.columnName());
                table.getColumnUsages().put(first.columnName(), Set.of(
                        com.dataocean.module.permission.s1.enums.IamS1ColumnUsage.PROJECTION));
            }
        }
        return List.copyOf(byName.values());
    }

    private QueryAttempt markRejected(QueryTask task, QueryAttempt previous, IamS1AttemptAuthorizeRequestDTO request,
                                      int attemptNo, String reason) {
        QueryAttempt rejected = previous == null
                ? QueryAttempt.builder().taskId(task.getTaskId()).attemptId(request.getAttemptId()).attemptNo(attemptNo)
                    .createdAt(LocalDateTime.now()).build()
                : previous;
        rejected.setSqlHash(request.getSqlHash());
        rejected.setStatus("REJECTED");
        rejected.setPermissionRevision(task.getPermissionRevision());
        rejected.setActiveMetadataSnapshotId(task.getActiveMetadataSnapshotId());
        rejected.setResourceRequestJson("[]");
        rejected.setExecutionSnapshotJson("{}");
        rejected.setErrorMessage(reason);
        rejected.setUpdatedAt(LocalDateTime.now());
        if (previous == null) queryAttemptMapper.insert(rejected);
        else queryAttemptMapper.updateById(rejected);
        return rejected;
    }

    private Map<String, Object> rejectAttempt(QueryAttempt attempt, String reason) {
        attempt.setStatus("REJECTED");
        attempt.setErrorMessage(reason);
        attempt.setUpdatedAt(LocalDateTime.now());
        attempt.setCompletedAt(LocalDateTime.now());
        queryAttemptMapper.updateById(attempt);
        return denied(reason);
    }

    private Map<String, Object> protectedAttempt(QueryAttempt attempt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowed", true);
        result.put("success", true);
        result.put("status", PROTECTED);
        result.put("attemptId", attempt.getAttemptId());
        result.put("sqlHash", attempt.getSqlHash());
        result.put("sql", attempt.getSafeSql());
        result.put("permissionRevision", attempt.getPermissionRevision());
        result.put("activeMetadataSnapshotId", attempt.getActiveMetadataSnapshotId());
        result.put("data", readListOfMaps(attempt.getProtectedData()));
        result.put("columns", readListOfStringMaps(attempt.getProtectedColumns()));
        result.put("sourceTrace", readListOfMaps(attempt.getSourceTrace()));
        result.put("maskedFields", readStringMap(attempt.getMaskedFields()));
        result.put("usedTables", readStringList(attempt.getUsedTables()));
        result.put("usedColumns", readStringList(attempt.getUsedColumns()));
        result.put("rowCount", attempt.getRowCount() == null ? 0 : attempt.getRowCount());
        result.put("executionTimeMs", attempt.getExecutionTimeMs() == null ? 0 : attempt.getExecutionTimeMs());
        return result;
    }

    private void requireLiveTask(QueryTask task) {
        if (task == null || !IamS1Constants.PROTOCOL_VERSION.equals(task.getIamProtocolVersion())
                || !QueryTaskStatus.PROCESSING.name().equals(task.getStatus())) {
            throw new BusinessException("查询任务已结束或不存在");
        }
        if (task.getCreatedAt() != null && task.getCreatedAt().plusSeconds(90).isBefore(LocalDateTime.now())) {
            throw new BusinessException("查询已超过总时限");
        }
        if (!authorizationResolver.hasGlobalFunction(task.getUserId(), "query:use")
                || (task.getConversationId() != null
                    && (!conversationService.isVisible(task.getConversationId(), task.getUserId())
                        || !conversationService.isActiveTurn(task.getConversationId(), task.getTaskId())))) {
            throw new BusinessException("当前会话或问数权限已撤销");
        }
    }

    private void requireCurrentRevision(QueryTask task) {
        Long currentRevision = dataResolver.currentPermissionRevision();
        var snapshot = schemaSnapshotService.getPublishedSnapshot(task.getDatasourceId());
        if (!Objects.equals(currentRevision, task.getPermissionRevision()) || snapshot == null
                || !Objects.equals(snapshot.getId(), task.getActiveMetadataSnapshotId())) {
            throw new BusinessException("当前权限或元数据快照已变化");
        }
    }

    private Map<String, Object> denied(String reason) {
        return Map.of("allowed", false, "success", false, "status", "REJECTED", "error", reason);
    }

    private void verifySqlHash(String sql, String expected) {
        if (!Objects.equals(sha256(sql), expected)) throw new BusinessException("SQL 与 AST 证据摘要不一致");
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new BusinessException("无法校验 SQL 摘要");
        }
    }

    private boolean sameSet(List<String> left, List<String> right) {
        return lowerSet(left).equals(lowerSet(right));
    }

    private Set<String> lowerSet(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) values.stream().filter(Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT)).forEach(result::add);
        return result;
    }

    private Set<String> traceSources(List<Map<String, Object>> trace) {
        Set<String> sources = new HashSet<>();
        if (trace != null) for (Map<String, Object> entry : trace) {
            Object raw = entry.get("sources");
            if (raw instanceof List<?> values) values.stream().filter(Objects::nonNull)
                    .map(value -> String.valueOf(value).toLowerCase(Locale.ROOT)).forEach(sources::add);
        }
        return sources;
    }

    private List<Map<String, Object>> sanitizeTrace(List<Map<String, Object>> trace) {
        List<Map<String, Object>> safe = new ArrayList<>();
        if (trace == null) return safe;
        for (Map<String, Object> entry : trace) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (entry.get("outputColumn") != null) item.put("outputColumn", entry.get("outputColumn"));
            if (entry.get("sources") != null) item.put("sources", entry.get("sources"));
            if (entry.get("sourceKind") != null) item.put("sourceKind", entry.get("sourceKind"));
            safe.add(item);
        }
        return safe;
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception ex) { return List.of(); }
    }

    private List<Map<String, Object>> readListOfMaps(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception ex) { return List.of(); }
    }

    private List<Map<String, String>> readListOfStringMaps(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception ex) { return List.of(); }
    }

    private Map<String, String> readStringMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception ex) { return Map.of(); }
    }
}
