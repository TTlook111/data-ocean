package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.entity.dto.QualityRuleOperationDTO;
import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import com.dataocean.module.governance.entity.po.QualityRuleOperation;
import com.dataocean.module.governance.entity.vo.QualityRuleOperationVO;
import com.dataocean.module.governance.entity.vo.QualityRuleVO;
import com.dataocean.module.governance.mapper.MetadataQualityRuleMapper;
import com.dataocean.module.governance.mapper.QualityRuleOperationMapper;
import com.dataocean.module.governance.service.QualityRuleOperationService;
import com.dataocean.module.governance.service.QualityRuleService;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/** 只协调规则领域写入；配置检查和执行检查保持为各自服务。 */
@Service
@RequiredArgsConstructor
public class QualityRuleOperationServiceImpl implements QualityRuleOperationService {
    private static final String RULE_MANAGE = "governance:rule:manage";
    private static final Set<String> ACTIONS = Set.of("CREATE", "UPDATE", "COPY", "ENABLE", "DISABLE", "DELETE", "RESTORE");

    private final QualityRuleOperationMapper operationMapper;
    private final MetadataQualityRuleMapper ruleMapper;
    private final QualityRuleService ruleService;
    private final IamS1AuthorizationResolver authorizationResolver;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public QualityRuleOperationVO execute(QualityRuleOperationDTO request, Long operatorUserId) {
        requireUser(operatorUserId);
        if (request == null) throw new BusinessException(400, "规则操作不能为空");
        String operationId = normalizeId(request.getOperationId());
        request.setOperationId(operationId);
        QualityRuleOperation seed = seed(operationId, operatorUserId);
        seed.setRequestHash(fingerprint(request, operatorUserId));
        seed.setAction(request.getAction());
        // INSERT 的唯一键锁必须先于规则写入；即使首次响应丢失，并发重试也只会有一份修订。
        operationMapper.reserve(seed);
        QualityRuleOperation operation = operationMapper.selectForUpdate(operatorUserId, operationId);
        if (!"PENDING".equals(operation.getOutcome())) {
            requireReceiptScope(operation, operatorUserId);
            if (operation.getRequestHash() != null && !operation.getRequestHash().equals(seed.getRequestHash())) {
                throw new BusinessException(409, "操作标识已用于其他内容，请先核对原操作结果");
            }
            return receipt(operation);
        }
        try {
            validateShape(request);
            // 不同操作标识仍可能启用同一目标的不同规则，必须先串行配置写，再读取原范围。
            if (!Integer.valueOf(1).equals(ruleMapper.lockConfigurationWrites())) {
                throw new IllegalStateException("质量规则配置写锁未初始化");
            }
            bindAndAuthorizeScope(operation, request, operatorUserId);
            // 规则服务的 NESTED 保存点保证任何业务拒绝均不会留下半次配置写入。
            QualityRuleVO result = mutate(request, operatorUserId);
            operation.setOutcome("APPLIED");
            operation.setResultRuleId(result.getId());
            operation.setResultVersion(result.getConfigVersion());
        } catch (BusinessException exception) {
            operation.setOutcome(exception.getCode() == 409 ? "CONFLICT" : "REJECTED");
            operation.setErrorCode(exception.getCode());
            operation.setErrorMessage(exception.getMessage());
        }
        // 非业务异常直接传播并回滚整个事务；不得把数据库失败写成已拒绝或成功。
        operation.setCompletedAt(LocalDateTime.now());
        if (operationMapper.updateById(operation) != 1) throw new IllegalStateException("规则操作回执保存失败");
        return receipt(operation);
    }

    @Override
    public QualityRuleOperationVO get(String operationId, Long operatorUserId) {
        requireUser(operatorUserId);
        String id = normalizeId(operationId);
        QualityRuleOperation operation = operationMapper.selectOne(new LambdaQueryWrapper<QualityRuleOperation>()
                .eq(QualityRuleOperation::getOperatorUserId, operatorUserId)
                .eq(QualityRuleOperation::getOperationId, id));
        if (operation == null) {
            // 查询不阻塞在途事务，NOT_FOUND 不证明原请求不会稍后提交。
            QualityRuleOperationVO result = new QualityRuleOperationVO();
            result.setOperationId(id);
            result.setOutcome("NOT_FOUND");
            return result;
        }
        requireReceiptScope(operation, operatorUserId);
        return receipt(operation);
    }

    @Override
    @Transactional
    public QualityRuleOperationVO resolve(String operationId, Long operatorUserId) {
        requireUser(operatorUserId);
        String id = normalizeId(operationId);
        operationMapper.reserve(seed(id, operatorUserId));
        QualityRuleOperation operation = operationMapper.selectForUpdate(operatorUserId, id);
        if ("PENDING".equals(operation.getOutcome())) {
            // 刷新已丢失草稿时，持久墓碑封住晚到原请求；与原请求争同一唯一键，不能先查后解锁。
            operation.setOutcome("REJECTED");
            operation.setErrorCode(409);
            operation.setErrorMessage("该操作已终止，未提交规则变更");
            operation.setCompletedAt(LocalDateTime.now());
            if (operationMapper.updateById(operation) != 1) throw new IllegalStateException("规则操作终结回执保存失败");
        }
        requireReceiptScope(operation, operatorUserId);
        return receipt(operation);
    }

    private QualityRuleVO mutate(QualityRuleOperationDTO request, Long userId) {
        Long ruleId = request.getRuleId();
        Integer version = request.getExpectedVersion();
        switch (request.getAction()) {
            case "CREATE": return ruleService.createRule(request.getConfiguration(), userId);
            case "UPDATE": return ruleService.updateRule(ruleId, request.getConfiguration(), version, userId);
            case "COPY": return ruleService.copyRule(ruleId, version, userId);
            case "ENABLE": ruleService.updateEnabled(ruleId, true, version, userId); break;
            case "DISABLE": ruleService.updateEnabled(ruleId, false, version, userId); break;
            case "DELETE": ruleService.deleteRule(ruleId, version, userId); break;
            case "RESTORE": ruleService.restoreRule(ruleId, version, userId); break;
            default: throw new BusinessException(400, "质量规则操作无效");
        }
        // 只需原操作的 ID/版本，不借主读取接口额外要求 metadata 或其他辅助目录权。
        QualityRuleVO result = new QualityRuleVO();
        result.setId(ruleId);
        result.setConfigVersion(version + 1);
        return result;
    }

    private void bindAndAuthorizeScope(QualityRuleOperation operation, QualityRuleOperationDTO request, Long userId) {
        if (request.getRuleId() != null) {
            MetadataQualityRule source = ruleMapper.selectForUpdate(request.getRuleId());
            if (source == null) throw new BusinessException(404, "质量规则不存在");
            operation.setSourceRuleId(source.getId());
            operation.setSourceScopeType(source.getScopeType());
            operation.setSourceDatasourceId(source.getDatasourceId());
            requireManage(source.getScopeType(), source.getDatasourceId(), userId);
        }
        if (request.getConfiguration() != null) {
            operation.setTargetScopeType(request.getConfiguration().getScopeType());
            operation.setTargetDatasourceId(request.getConfiguration().getDatasourceId());
            requireManage(operation.getTargetScopeType(), operation.getTargetDatasourceId(), userId);
        }
    }

    private void requireReceiptScope(QualityRuleOperation operation, Long userId) {
        // 原范围、意图目标及当前对象均重新授权；保存过回执不等于撤权后仍可读旧事实。
        if (operation.getSourceScopeType() != null) requireManage(operation.getSourceScopeType(), operation.getSourceDatasourceId(), userId);
        if (operation.getTargetScopeType() != null) requireManage(operation.getTargetScopeType(), operation.getTargetDatasourceId(), userId);
        if (operation.getSourceRuleId() != null) requireCurrentScope(operation.getSourceRuleId(), userId);
        if (operation.getResultRuleId() != null) requireCurrentScope(operation.getResultRuleId(), userId);
    }

    private void requireCurrentScope(Long ruleId, Long userId) {
        MetadataQualityRule rule = ruleMapper.selectById(ruleId);
        if (rule != null) requireManage(rule.getScopeType(), rule.getDatasourceId(), userId);
    }

    private void requireManage(String scopeType, Long datasourceId, Long userId) {
        boolean allowed = "GLOBAL".equals(scopeType)
                ? authorizationResolver.isSystemAdmin(userId)
                : datasourceId != null && authorizationResolver.canManageDatasource(userId, RULE_MANAGE, datasourceId);
        if (!allowed) throw new BusinessException(403, "没有维护该范围质量规则的权限");
    }

    private void validateShape(QualityRuleOperationDTO request) {
        if (request.getAction() == null || !ACTIONS.contains(request.getAction())) throw new BusinessException(400, "质量规则操作无效");
        boolean creating = "CREATE".equals(request.getAction());
        boolean configuring = creating || "UPDATE".equals(request.getAction());
        if (creating && (request.getRuleId() != null || request.getExpectedVersion() != null)
                || !creating && (request.getRuleId() == null || request.getRuleId() <= 0
                || request.getExpectedVersion() == null || request.getExpectedVersion() < 1)) {
            throw new BusinessException(400, "质量规则操作缺少正确的对象或预期版本");
        }
        if (configuring != (request.getConfiguration() != null)) throw new BusinessException(400, "质量规则操作与配置内容不匹配");
    }

    private QualityRuleOperation seed(String operationId, Long userId) {
        QualityRuleOperation result = new QualityRuleOperation();
        result.setOperationId(operationId);
        result.setOperatorUserId(userId);
        return result;
    }

    private String fingerprint(QualityRuleOperationDTO request, Long userId) {
        try {
            // 排序对象键，避免 JSON/参数键顺序影响幂等；账号、动作、对象、版本和全部配置均进入指纹。
            String json = objectMapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(request);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((userId + "\n" + json).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new BusinessException(400, "质量规则操作内容无法核验");
        }
    }

    private String normalizeId(String operationId) {
        if (operationId == null || !operationId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw new BusinessException(400, "请提供有效的质量规则操作标识");
        }
        return operationId.toLowerCase(Locale.ROOT);
    }

    private void requireUser(Long userId) {
        if (userId == null) throw new BusinessException(401, "请先登录");
    }

    private QualityRuleOperationVO receipt(QualityRuleOperation operation) {
        QualityRuleOperationVO result = new QualityRuleOperationVO();
        result.setOperationId(operation.getOperationId());
        result.setAction(operation.getAction());
        result.setOutcome(operation.getOutcome());
        result.setRuleId(operation.getResultRuleId());
        result.setConfigVersion(operation.getResultVersion());
        result.setErrorCode(operation.getErrorCode());
        result.setErrorMessage(operation.getErrorMessage());
        return result;
    }
}
