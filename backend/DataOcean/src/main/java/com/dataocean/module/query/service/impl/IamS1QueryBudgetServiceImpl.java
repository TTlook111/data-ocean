package com.dataocean.module.query.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1DataAuthorizationResolver;
import com.dataocean.module.metadata.service.SchemaSnapshotService;
import com.dataocean.module.query.entity.QueryModelCall;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.entity.dto.IamS1ModelCallBudgetDTO;
import com.dataocean.module.query.enums.QueryTaskStatus;
import com.dataocean.module.query.mapper.QueryModelCallMapper;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.query.service.ConversationService;
import com.dataocean.module.query.service.IamS1QueryBudgetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** G0 freezes at most 8 model calls, 90 seconds and CNY 0.10 per query. */
@Service
@RequiredArgsConstructor
public class IamS1QueryBudgetServiceImpl implements IamS1QueryBudgetService {
    private static final int MAX_CALLS = 8;
    private static final int MAX_EMBEDDING_CALLS = 2;
    private static final int MAX_INPUT_TOKENS_PER_CALL = 20_000;
    private static final int MAX_EMBEDDING_TOKENS_PER_CALL = 1_024;
    private static final int MAX_OUTPUT_TOKENS_PER_CALL = 1_024;
    private static final BigDecimal COST_LIMIT = new BigDecimal("0.100000");
    // Frozen against the G0 DashScope qwen-flash measurement; configure a new AI profile
    // only with an explicit update to this budget contract.
    private static final BigDecimal INPUT_PRICE_PER_MILLION = new BigDecimal("0.15");
    private static final BigDecimal OUTPUT_PRICE_PER_MILLION = new BigDecimal("1.5");
    private static final BigDecimal EMBEDDING_PRICE_PER_MILLION = new BigDecimal("0.5");

    private final QueryTaskMapper taskMapper;
    private final QueryModelCallMapper modelCallMapper;
    private final IamS1DataAuthorizationResolver dataResolver;
    private final IamS1AuthorizationResolver authorizationResolver;
    private final SchemaSnapshotService schemaSnapshotService;
    private final ConversationService conversationService;

    @Override
    @Transactional
    public Map<String, Object> reserve(String taskId, IamS1ModelCallBudgetDTO request) {
        QueryTask task = taskMapper.selectByTaskIdForUpdate(taskId);
        if (!isLive(task)) return denied("查询任务已结束或超过总时限");
        boolean embedding = "rag_embedding".equals(request.getNodeName());
        if (embedding) {
            if (!"text-embedding-v4".equals(request.getModelName())) return denied("当前 Embedding 价格未纳入 G0 冻结预算");
        } else if (!"qwen-flash".equals(request.getModelName())) {
            return denied("当前模型价格未纳入 G0 冻结预算");
        }
        QueryModelCall previous = modelCallMapper.selectForUpdate(taskId, request.getCallId());
        if (previous != null) {
            if ("COMPLETED".equals(previous.getStatus())) return denied("模型调用结果未能安全恢复，请重新提问");
            if ("RESERVED".equals(previous.getStatus())) {
                previous.setStatus("AMBIGUOUS");
                modelCallMapper.updateById(previous);
            }
            return denied("模型调用状态未能安全确认，请重新提问");
        }
        if (request.getInputTokens() > (embedding ? MAX_EMBEDDING_TOKENS_PER_CALL : MAX_INPUT_TOKENS_PER_CALL)
                || (embedding && request.getOutputTokens() != 0)
                || (!embedding && request.getOutputTokens() <= 0)
                || request.getOutputTokens() > MAX_OUTPUT_TOKENS_PER_CALL) {
            return denied("提示词或输出长度超过本次预算");
        }
        int callCount = embedding
                ? (task.getEmbeddingCallCount() == null ? 0 : task.getEmbeddingCallCount())
                : (task.getLlmCallCount() == null ? 0 : task.getLlmCallCount());
        if (callCount >= (embedding ? MAX_EMBEDDING_CALLS : MAX_CALLS)) {
            return denied(embedding ? "已达到本次查询的 Embedding 调用上限" : "已达到本次查询的模型调用上限");
        }

        BigDecimal reserve = cost(request.getModelName(), request.getInputTokens(), request.getOutputTokens());
        BigDecimal currentCost = task.getEstimatedAiCostCny() == null
                ? BigDecimal.ZERO : task.getEstimatedAiCostCny();
        if (currentCost.add(reserve).compareTo(COST_LIMIT) > 0) return denied("已达到本次查询的 AI 费用上限");

        modelCallMapper.insert(QueryModelCall.builder()
                .taskId(taskId).callId(request.getCallId()).nodeName(request.getNodeName())
                .modelName(request.getModelName())
                .status("RESERVED").inputTokens(request.getInputTokens()).outputTokens(request.getOutputTokens())
                .reservedCostCny(reserve).usageEstimated(0).createdAt(LocalDateTime.now()).build());
        if (embedding) task.setEmbeddingCallCount(callCount + 1);
        else task.setLlmCallCount(callCount + 1);
        task.setEstimatedAiCostCny(currentCost.add(reserve).setScale(6, RoundingMode.HALF_UP));
        taskMapper.updateById(task);
        return budgetState(task, "RESERVED", true);
    }

    @Override
    @Transactional
    public Map<String, Object> settle(String taskId, IamS1ModelCallBudgetDTO request) {
        QueryTask task = taskMapper.selectByTaskIdForUpdate(taskId);
        if (task == null) return denied("查询任务不存在");
        QueryModelCall call = modelCallMapper.selectForUpdate(taskId, request.getCallId());
        if (call == null) return denied("模型调用未预留预算");
        if (!Objects.equals(call.getModelName(), request.getModelName())) return denied("模型与预算预留不一致");
        if ("COMPLETED".equals(call.getStatus())) return budgetState(task, "COMPLETED", true);
        if (!"RESERVED".equals(call.getStatus())) return denied("模型调用状态无法结算");

        BigDecimal actual = request.isUsageReported()
                ? cost(call.getModelName(), request.getInputTokens(), request.getOutputTokens())
                : call.getReservedCostCny();
        BigDecimal reserved = call.getReservedCostCny() == null ? BigDecimal.ZERO : call.getReservedCostCny();
        BigDecimal accumulated = task.getEstimatedAiCostCny() == null ? BigDecimal.ZERO : task.getEstimatedAiCostCny();
        BigDecimal settled = accumulated.subtract(reserved).add(actual).setScale(6, RoundingMode.HALF_UP);
        call.setActualCostCny(actual);
        call.setUsageEstimated(request.isUsageReported() ? 0 : 1);
        call.setStatus("COMPLETED");
        call.setCompletedAt(LocalDateTime.now());
        modelCallMapper.updateById(call);
        task.setEstimatedAiCostCny(settled);
        taskMapper.updateById(task);
        if (settled.compareTo(COST_LIMIT) > 0) return denied("本次查询达到 AI 费用上限，已停止后续步骤");
        return budgetState(task, "COMPLETED", true);
    }

    private boolean isLive(QueryTask task) {
        if (task == null || !QueryTaskStatus.PROCESSING.name().equals(task.getStatus())
                || !"IAM-SIMPLE-1".equals(task.getIamProtocolVersion())) return false;
        if (task.getCreatedAt() != null && task.getCreatedAt().plusSeconds(90).isBefore(LocalDateTime.now())) return false;
        if (!authorizationResolver.hasGlobalFunction(task.getUserId(), "query:use")) return false;
        var metadata = schemaSnapshotService.getPublishedSnapshot(task.getDatasourceId());
        if (metadata == null || !Objects.equals(metadata.getId(), task.getActiveMetadataSnapshotId())
                || !Objects.equals(dataResolver.currentPermissionRevision(), task.getPermissionRevision())) return false;
        return task.getConversationId() == null || (conversationService.isVisible(task.getConversationId(), task.getUserId())
                && conversationService.isActiveTurn(task.getConversationId(), task.getTaskId()));
    }

    private Map<String, Object> budgetState(QueryTask task, String status, boolean allowed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowed", allowed);
        result.put("status", status);
        result.put("callsUsed", task.getLlmCallCount() == null ? 0 : task.getLlmCallCount());
        result.put("embeddingCallsUsed", task.getEmbeddingCallCount() == null ? 0 : task.getEmbeddingCallCount());
        result.put("estimatedCostCny", task.getEstimatedAiCostCny() == null ? BigDecimal.ZERO : task.getEstimatedAiCostCny());
        result.put("maxCalls", MAX_CALLS);
        result.put("costLimitCny", COST_LIMIT);
        return result;
    }

    private Map<String, Object> denied(String reason) {
        return Map.of("allowed", false, "status", "REJECTED", "error", reason);
    }

    private BigDecimal cost(String modelName, int inputTokens, int outputTokens) {
        if ("text-embedding-v4".equals(modelName)) {
            return EMBEDDING_PRICE_PER_MILLION.multiply(BigDecimal.valueOf(inputTokens))
                    .divide(BigDecimal.valueOf(1_000_000), 9, RoundingMode.HALF_UP)
                    .setScale(6, RoundingMode.HALF_UP);
        }
        BigDecimal input = INPUT_PRICE_PER_MILLION.multiply(BigDecimal.valueOf(inputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 9, RoundingMode.HALF_UP);
        BigDecimal output = OUTPUT_PRICE_PER_MILLION.multiply(BigDecimal.valueOf(outputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 9, RoundingMode.HALF_UP);
        return input.add(output).setScale(6, RoundingMode.HALF_UP);
    }
}
