package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.entity.dto.QualityRuleDTO;
import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import com.dataocean.module.governance.entity.po.MetadataQualityRuleRevision;
import com.dataocean.module.governance.entity.query.QualityRuleQuery;
import com.dataocean.module.governance.entity.vo.QualityRuleVO;
import com.dataocean.module.governance.entity.vo.QualityRuleRevisionVO;
import com.dataocean.module.governance.mapper.MetadataQualityRuleMapper;
import com.dataocean.module.governance.mapper.MetadataQualityRuleRevisionMapper;
import com.dataocean.module.governance.service.QualityRuleService;
import com.dataocean.module.governance.service.QualityRuleTemplateRegistry;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class QualityRuleServiceImpl implements QualityRuleService {
    private static final String RULE_VIEW = "governance:rule:view";
    private static final String RULE_MANAGE = "governance:rule:manage";
    private static final Set<String> SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private final MetadataQualityRuleMapper ruleMapper;
    private final MetadataQualityRuleRevisionMapper revisionMapper;
    private final IamS1AuthorizationResolver authorizationResolver;
    private final IamS1CapabilityService capabilityService;
    private final QualityRuleTemplateRegistry templateRegistry;
    private final MetadataSnapshotMapper snapshotMapper;
    private final DbTableMetaMapper tableMapper;
    private final DbColumnMetaMapper columnMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<MetadataQualityRule> listAllRules() {
        return ruleMapper.selectList(new LambdaQueryWrapper<MetadataQualityRule>()
                .isNull(MetadataQualityRule::getDeletedAt)
                .orderByAsc(MetadataQualityRule::getId));
    }

    @Override
    public List<MetadataQualityRule> listEnabledRules() {
        return ruleMapper.selectList(new LambdaQueryWrapper<MetadataQualityRule>()
                .eq(MetadataQualityRule::getEnabled, 1)
                .isNull(MetadataQualityRule::getDeletedAt)
                .orderByAsc(MetadataQualityRule::getId));
    }

    /** 仅返回全局与当前数据源规则；目标覆盖由执行计划按表/字段优先级解析。 */
    @Override
    public List<MetadataQualityRule> listEnabledRules(Long datasourceId) {
        if (datasourceId == null) throw new BusinessException(400, "质量检查缺少数据源范围");
        return ruleMapper.selectList(new LambdaQueryWrapper<MetadataQualityRule>()
                .eq(MetadataQualityRule::getEnabled, 1)
                .isNull(MetadataQualityRule::getDeletedAt)
                .and(w -> w.isNull(MetadataQualityRule::getDatasourceId)
                        .or().eq(MetadataQualityRule::getDatasourceId, datasourceId))
                .orderByAsc(MetadataQualityRule::getId));
    }

    @Override
    public List<QualityRuleVO> listRules(QualityRuleQuery query, Long operatorUserId) {
        QualityRuleQuery filter = query == null ? new QualityRuleQuery() : query;
        List<Long> visibleSources = capabilityService.responsibleDatasourcesWithFunction(operatorUserId, RULE_VIEW)
                .stream().map(IamS1DatasourceRefVO::id).toList();
        if (filter.getDatasourceId() != null && !visibleSources.contains(filter.getDatasourceId())) {
            throw new BusinessException(403, "没有查看该数据源质量规则的权限");
        }

        LambdaQueryWrapper<MetadataQualityRule> wrapper = visibleScope(filter.getDatasourceId(), visibleSources)
                .orderByDesc(MetadataQualityRule::getUpdatedAt).orderByDesc(MetadataQualityRule::getId);
        if (Boolean.TRUE.equals(filter.getDeleted())) wrapper.isNotNull(MetadataQualityRule::getDeletedAt);
        else wrapper.isNull(MetadataQualityRule::getDeletedAt);
        if (StringUtils.hasText(filter.getScopeType())) wrapper.eq(MetadataQualityRule::getScopeType, filter.getScopeType());
        if (StringUtils.hasText(filter.getDimension())) wrapper.eq(MetadataQualityRule::getDimension, filter.getDimension());
        if (StringUtils.hasText(filter.getTemplateCode())) wrapper.eq(MetadataQualityRule::getTemplateCode, filter.getTemplateCode());
        if (StringUtils.hasText(filter.getRuleName())) wrapper.like(MetadataQualityRule::getRuleName, filter.getRuleName().trim());
        if (filter.getEnabled() != null) wrapper.eq(MetadataQualityRule::getEnabled, filter.getEnabled() ? 1 : 0);
        List<MetadataQualityRule> visibleRules = ruleMapper.selectList(wrapper);
        List<MetadataQualityRule> overrideRules = ruleMapper.selectList(visibleScope(filter.getDatasourceId(), visibleSources)
                .isNull(MetadataQualityRule::getDeletedAt)
                .eq(MetadataQualityRule::getEnabled, 1));
        List<QualityRuleVO> result = new ArrayList<>();
        for (MetadataQualityRule rule : visibleRules) {
            QualityRuleVO vo = toVO(rule);
            if (Integer.valueOf(1).equals(rule.getEnabled()) && rule.getDeletedAt() == null) {
                MetadataQualityRule override = overrideRules.stream()
                        .filter(candidate -> !candidate.getId().equals(rule.getId()))
                        .filter(candidate -> Integer.valueOf(1).equals(candidate.getEnabled()) && candidate.getDeletedAt() == null)
                        .filter(candidate -> java.util.Objects.equals(candidate.getTemplateCode(), rule.getTemplateCode()))
                        .filter(candidate -> specificity(candidate.getScopeType()) > specificity(rule.getScopeType()))
                        .filter(candidate -> scopeOverlaps(candidate, rule))
                        .findFirst().orElse(null);
                if (override != null) {
                    vo.setOverridden(true);
                    vo.setOverriddenBy(override.getRuleName());
                }
            }
            result.add(vo);
        }
        return result;
    }

    private LambdaQueryWrapper<MetadataQualityRule> visibleScope(Long datasourceId, List<Long> visibleSources) {
        LambdaQueryWrapper<MetadataQualityRule> wrapper = new LambdaQueryWrapper<>();
        if (datasourceId != null) {
            return wrapper.and(w -> w.isNull(MetadataQualityRule::getDatasourceId)
                    .or().eq(MetadataQualityRule::getDatasourceId, datasourceId));
        }
        if (visibleSources.isEmpty()) return wrapper.isNull(MetadataQualityRule::getDatasourceId);
        return wrapper.and(w -> w.isNull(MetadataQualityRule::getDatasourceId)
                .or().in(MetadataQualityRule::getDatasourceId, visibleSources));
    }

    @Override
    public QualityRuleVO getRule(Long ruleId, Long operatorUserId, boolean includeDeleted) {
        MetadataQualityRule rule = requireRule(ruleId, includeDeleted);
        requireViewScope(rule, operatorUserId);
        return toVO(rule);
    }

    @Override
    public List<QualityRuleRevisionVO> history(Long ruleId, Long operatorUserId) {
        MetadataQualityRule rule = requireRule(ruleId, true);
        requireViewScope(rule, operatorUserId);
        boolean systemAdmin = authorizationResolver.isSystemAdmin(operatorUserId);
        List<Long> visibleSources = systemAdmin ? List.of() : capabilityService
                .responsibleDatasourcesWithFunction(operatorUserId, RULE_VIEW).stream()
                .map(IamS1DatasourceRefVO::id).toList();
        LambdaQueryWrapper<MetadataQualityRuleRevision> query = new LambdaQueryWrapper<MetadataQualityRuleRevision>()
                .eq(MetadataQualityRuleRevision::getRuleId, ruleId);
        if (!systemAdmin) {
            if (visibleSources.isEmpty()) query.isNull(MetadataQualityRuleRevision::getDatasourceId);
            else query.and(w -> w.isNull(MetadataQualityRuleRevision::getDatasourceId)
                    .or().in(MetadataQualityRuleRevision::getDatasourceId, visibleSources));
        }
        List<MetadataQualityRuleRevision> revisions = revisionMapper.selectList(query
                .orderByDesc(MetadataQualityRuleRevision::getId));
        return revisions.stream()
                .filter(revision -> systemAdmin || revision.getDatasourceId() == null
                        || visibleSources.contains(revision.getDatasourceId()))
                .map(this::revisionVO).toList();
    }

    @Transactional
    @Override
    public QualityRuleVO createRule(QualityRuleDTO request, Long operatorUserId) {
        return createRule(request, operatorUserId, "CREATE");
    }

    private QualityRuleVO createRule(QualityRuleDTO request, Long operatorUserId, String action) {
        QualityRuleDTO dto = requireRequest(request);
        MetadataQualityRule rule = new MetadataQualityRule();
        apply(rule, dto, operatorUserId, true);
        rule.setRuleCode("QR-" + UUID.randomUUID().toString().replace("-", ""));
        rule.setBuiltin(0);
        rule.setConfigVersion(1);
        rule.setCreatedBy(operatorUserId);
        rule.setUpdatedBy(operatorUserId);
        rule.setDeletedAt(null);
        rule.setDeletedBy(null);
        if (Integer.valueOf(1).equals(rule.getEnabled())) validateNoConflict(rule, null);
        ruleMapper.insert(rule);
        appendRevision(rule, action, operatorUserId);
        return toVO(rule);
    }

    @Transactional
    @Override
    public QualityRuleVO updateRule(Long ruleId, QualityRuleDTO request, Long operatorUserId) {
        MetadataQualityRule rule = requireRule(ruleId, false);
        requireManageScope(rule, operatorUserId);
        QualityRuleDTO dto = requireRequest(request);
        requireManageTarget(dto.getScopeType(), dto.getDatasourceId(), operatorUserId);
        apply(rule, dto, operatorUserId, false);
        rule.setConfigVersion((rule.getConfigVersion() == null ? 1 : rule.getConfigVersion()) + 1);
        rule.setUpdatedBy(operatorUserId);
        if (Integer.valueOf(1).equals(rule.getEnabled())) validateNoConflict(rule, ruleId);
        rule.setUpdatedAt(LocalDateTime.now());
        if (persistConfiguration(rule) != 1) throw new BusinessException(409, "质量规则配置已变化，请重新读取后再修改");
        appendRevision(rule, "UPDATE", operatorUserId);
        return toVO(rule);
    }

    @Transactional
    @Override
    public QualityRuleVO copyRule(Long ruleId, Long operatorUserId) {
        MetadataQualityRule source = requireRule(ruleId, false);
        requireManageScope(source, operatorUserId);
        QualityRuleDTO dto = new QualityRuleDTO();
        dto.setRuleName(source.getRuleName() + "（副本）");
        dto.setTemplateCode(source.getTemplateCode());
        dto.setDescription(source.getDescription());
        dto.setScopeType(source.getScopeType());
        dto.setDatasourceId(source.getDatasourceId());
        dto.setTargetTableName(source.getTargetTableName());
        dto.setTargetColumnName(source.getTargetColumnName());
        dto.setParameters(parseParameters(source.getParametersJson()));
        dto.setSeverity(source.getSeverity());
        dto.setDeductionPoints(source.getDeductionPoints());
        dto.setEnabled(false);
        return createRule(dto, operatorUserId, "COPY");
    }

    @Transactional
    @Override
    public void updateEnabled(Long ruleId, boolean enabled, Long operatorUserId) {
        MetadataQualityRule rule = requireRule(ruleId, false);
        requireManageScope(rule, operatorUserId);
        if (enabled) {
            validateRuleTarget(rule);
            validateNoConflict(rule, ruleId);
        }
        rule.setEnabled(enabled ? 1 : 0);
        rule.setUpdatedBy(operatorUserId);
        ruleMapper.updateById(rule);
        appendRevision(rule, enabled ? "ENABLE" : "DISABLE", operatorUserId);
    }

    @Transactional
    @Override
    public void deleteRule(Long ruleId, Long operatorUserId) {
        MetadataQualityRule rule = requireRule(ruleId, false);
        requireManageScope(rule, operatorUserId);
        // 一次操作原子停用并软删除；问题、批次快照和历史结果均不级联删除。
        rule.setEnabled(0);
        rule.setDeletedAt(java.time.LocalDateTime.now());
        rule.setDeletedBy(operatorUserId);
        rule.setUpdatedBy(operatorUserId);
        ruleMapper.updateById(rule);
        appendRevision(rule, "DELETE", operatorUserId);
    }

    @Transactional
    @Override
    public void restoreRule(Long ruleId, Long operatorUserId) {
        MetadataQualityRule rule = requireRule(ruleId, true);
        if (rule.getDeletedAt() == null) throw new BusinessException(409, "该规则未删除");
        requireManageScope(rule, operatorUserId);
        rule.setDeletedAt(null);
        rule.setDeletedBy(null);
        rule.setEnabled(0);
        rule.setUpdatedBy(operatorUserId);
        rule.setUpdatedAt(LocalDateTime.now());
        int updated = ruleMapper.update(null, new LambdaUpdateWrapper<MetadataQualityRule>()
                .eq(MetadataQualityRule::getId, ruleId)
                .isNotNull(MetadataQualityRule::getDeletedAt)
                .set(MetadataQualityRule::getDeletedAt, null)
                .set(MetadataQualityRule::getDeletedBy, null)
                .set(MetadataQualityRule::getEnabled, 0)
                .set(MetadataQualityRule::getUpdatedBy, operatorUserId)
                .set(MetadataQualityRule::getUpdatedAt, rule.getUpdatedAt()));
        if (updated != 1) throw new BusinessException(409, "质量规则状态已变化，请重新读取后再恢复");
        appendRevision(rule, "RESTORE", operatorUserId);
    }

    /** 规则编辑是完整配置替换；空值字段仅在本次配置 UPDATE 中显式清空。 */
    private int persistConfiguration(MetadataQualityRule rule) {
        return ruleMapper.update(null, new LambdaUpdateWrapper<MetadataQualityRule>()
                .eq(MetadataQualityRule::getId, rule.getId())
                .isNull(MetadataQualityRule::getDeletedAt)
                .set(MetadataQualityRule::getRuleName, rule.getRuleName())
                .set(MetadataQualityRule::getTemplateCode, rule.getTemplateCode())
                .set(MetadataQualityRule::getDimension, rule.getDimension())
                .set(MetadataQualityRule::getSeverity, rule.getSeverity())
                .set(MetadataQualityRule::getDescription, rule.getDescription())
                .set(MetadataQualityRule::getCheckTarget, rule.getCheckTarget())
                .set(MetadataQualityRule::getCheckType, rule.getCheckType())
                .set(MetadataQualityRule::getCheckExpression, rule.getCheckExpression())
                .set(MetadataQualityRule::getThreshold, rule.getThreshold())
                .set(MetadataQualityRule::getEnabled, rule.getEnabled())
                .set(MetadataQualityRule::getDeductionPoints, rule.getDeductionPoints())
                .set(MetadataQualityRule::getScopeType, rule.getScopeType())
                .set(MetadataQualityRule::getDatasourceId, rule.getDatasourceId())
                .set(MetadataQualityRule::getTargetTableName, rule.getTargetTableName())
                .set(MetadataQualityRule::getTargetColumnName, rule.getTargetColumnName())
                .set(MetadataQualityRule::getParametersJson, rule.getParametersJson())
                .set(MetadataQualityRule::getConfigVersion, rule.getConfigVersion())
                .set(MetadataQualityRule::getUpdatedBy, rule.getUpdatedBy())
                .set(MetadataQualityRule::getUpdatedAt, rule.getUpdatedAt()));
    }

    private void apply(MetadataQualityRule rule, QualityRuleDTO dto, Long operatorUserId, boolean creating) {
        QualityRuleTemplateRegistry.Definition template = templateRegistry.require(dto.getTemplateCode());
        if (!StringUtils.hasText(dto.getRuleName())) throw new BusinessException(400, "规则名称不能为空");
        if (!StringUtils.hasText(dto.getScopeType())) throw new BusinessException(400, "规则作用范围不能为空");
        requireManageTarget(dto.getScopeType(), dto.getDatasourceId(), operatorUserId);
        if (!template.getAllowedScopes().contains(dto.getScopeType())) {
            throw new BusinessException(400, "所选检查模板不支持该作用范围");
        }
        if (!StringUtils.hasText(dto.getSeverity()) || !SEVERITIES.contains(dto.getSeverity())) {
            throw new BusinessException(400, "问题严重程度无效");
        }
        BigDecimal points = dto.getDeductionPoints();
        if (points == null || points.signum() < 0 || points.compareTo(new BigDecimal("100.0")) > 0) {
            throw new BusinessException(400, "扣分必须在 0 到 100 之间");
        }
        validateScopeShape(dto.getScopeType(), dto.getDatasourceId(), dto.getTargetTableName(), dto.getTargetColumnName());
        validateTargetExists(dto.getScopeType(), dto.getDatasourceId(), dto.getTargetTableName(), dto.getTargetColumnName(), template);

        rule.setRuleName(dto.getRuleName().trim());
        rule.setTemplateCode(template.getCode());
        rule.setDimension(template.getDimension());
        rule.setCheckTarget(template.getTargetType());
        rule.setCheckType(template.getCheckType());
        rule.setCheckExpression(null);
        rule.setDescription(dto.getDescription());
        rule.setSeverity(dto.getSeverity());
        rule.setDeductionPoints(points);
        rule.setScopeType(dto.getScopeType());
        rule.setDatasourceId(dto.getDatasourceId());
        rule.setTargetTableName(blankToNull(dto.getTargetTableName()));
        rule.setTargetColumnName(blankToNull(dto.getTargetColumnName()));
        Map<String, Object> parameters = normalizeParameters(template, dto.getParameters());
        try {
            rule.setParametersJson(parameters.isEmpty() ? null : objectMapper.writeValueAsString(parameters));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(400, "规则参数无法保存");
        }
        if (template.getCode().equals("DATA_NULL_RATE_HIGH")) {
            rule.setThreshold(new BigDecimal(parameters.get("threshold").toString()));
        } else if (template.getCode().equals("DATA_STALE_TABLE")) {
            rule.setThreshold(new BigDecimal(parameters.get("staleDays").toString()));
        } else if (template.getCode().equals("TIME_SNAPSHOT_EXPIRED")) {
            rule.setThreshold(new BigDecimal(parameters.get("maxAgeDays").toString()));
        } else if (template.getCode().equals("ACCU_ENUM_ANOMALY")) {
            rule.setThreshold(new BigDecimal(parameters.get("maxDistinctValues").toString()));
        } else {
            rule.setThreshold(null);
        }
        if (dto.getEnabled() != null) rule.setEnabled(dto.getEnabled() ? 1 : 0);
        else if (creating) rule.setEnabled(0);
    }

    private Map<String, Object> normalizeParameters(QualityRuleTemplateRegistry.Definition template,
                                                    Map<String, Object> input) {
        Map<String, Object> supplied = input == null ? Map.of() : input;
        Set<String> supported = template.getParameters().stream()
                .map(QualityRuleTemplateRegistry.ParameterDefinition::getKey)
                .collect(java.util.stream.Collectors.toSet());
        for (String key : supplied.keySet()) {
            if (!supported.contains(key)) throw new BusinessException(400, "参数不受该检查模板支持: " + key);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (QualityRuleTemplateRegistry.ParameterDefinition definition : template.getParameters()) {
            Object raw = supplied.getOrDefault(definition.getKey(), definition.getDefaultValue());
            if (raw == null) throw new BusinessException(400, "缺少规则参数: " + definition.getLabel());
            if ("BOOLEAN".equals(definition.getType())) {
                if (!(raw instanceof Boolean)) throw new BusinessException(400, "规则参数必须是布尔值: " + definition.getLabel());
                normalized.put(definition.getKey(), raw);
                continue;
            }
            BigDecimal number;
            try {
                number = raw instanceof Number ? new BigDecimal(raw.toString()) : new BigDecimal(String.valueOf(raw));
            } catch (NumberFormatException exception) {
                throw new BusinessException(400, "规则参数必须是数字: " + definition.getLabel());
            }
            if (definition.getMinimum() != null && number.compareTo(definition.getMinimum()) < 0
                    || definition.getMaximum() != null && number.compareTo(definition.getMaximum()) > 0) {
                throw new BusinessException(400, "规则参数超出允许范围: " + definition.getLabel());
            }
            if ("INTEGER".equals(definition.getType())) {
                try {
                    normalized.put(definition.getKey(), number.intValueExact());
                } catch (ArithmeticException exception) {
                    throw new BusinessException(400, "规则参数必须是整数: " + definition.getLabel());
                }
            } else {
                normalized.put(definition.getKey(), number.stripTrailingZeros());
            }
        }
        return normalized;
    }

    private void validateScopeShape(String scopeType, Long datasourceId, String table, String column) {
        if (QualityRuleTemplateRegistry.SCOPE_GLOBAL.equals(scopeType)) {
            if (datasourceId != null || StringUtils.hasText(table) || StringUtils.hasText(column)) {
                throw new BusinessException(400, "全局规则不能绑定数据源、表或字段");
            }
            return;
        }
        if (datasourceId == null) throw new BusinessException(400, "该作用范围必须选择数据源");
        if (QualityRuleTemplateRegistry.SCOPE_DATASOURCE.equals(scopeType)
                && (StringUtils.hasText(table) || StringUtils.hasText(column))) {
            throw new BusinessException(400, "数据源级规则不能绑定表或字段");
        }
        if (QualityRuleTemplateRegistry.SCOPE_TABLE.equals(scopeType)
                && (!StringUtils.hasText(table) || StringUtils.hasText(column))) {
            throw new BusinessException(400, "表级规则必须选择一张表且不能绑定字段");
        }
        if (QualityRuleTemplateRegistry.SCOPE_COLUMN.equals(scopeType)
                && (!StringUtils.hasText(table) || !StringUtils.hasText(column))) {
            throw new BusinessException(400, "字段级规则必须选择表和字段");
        }
        if (!Set.of(QualityRuleTemplateRegistry.SCOPE_DATASOURCE, QualityRuleTemplateRegistry.SCOPE_TABLE,
                QualityRuleTemplateRegistry.SCOPE_COLUMN).contains(scopeType)) {
            throw new BusinessException(400, "质量规则作用范围无效");
        }
    }

    private void validateTargetExists(String scopeType, Long datasourceId, String table, String column,
                                      QualityRuleTemplateRegistry.Definition template) {
        if (QualityRuleTemplateRegistry.SCOPE_GLOBAL.equals(scopeType)
                || QualityRuleTemplateRegistry.SCOPE_DATASOURCE.equals(scopeType)) return;
        MetadataSnapshot latest = snapshotMapper.selectOne(new LambdaQueryWrapper<MetadataSnapshot>()
                .eq(MetadataSnapshot::getDatasourceId, datasourceId)
                .orderByDesc(MetadataSnapshot::getSnapshotVersion)
                .orderByDesc(MetadataSnapshot::getId).last("LIMIT 1"));
        if (latest == null) throw new BusinessException(409, "该数据源没有可用于核对范围的元数据快照");
        DbTableMeta tableMeta = tableMapper.selectOne(new LambdaQueryWrapper<DbTableMeta>()
                .eq(DbTableMeta::getSnapshotId, latest.getId())
                .eq(DbTableMeta::getTableName, table).last("LIMIT 1"));
        if (tableMeta == null) throw new BusinessException(409, "所选表在该数据源当前快照中不存在");
        if (MetadataQualityRule.CHECK_TYPE_DATA.equals(template.getCheckType())
                && isRestrictedGovernanceStatus(tableMeta.getGovernanceStatus())) {
            throw new BusinessException(409, "废弃或阻断状态的表不能作为数据级规则目标");
        }
        if (QualityRuleTemplateRegistry.SCOPE_COLUMN.equals(scopeType)) {
            DbColumnMeta columnMeta = columnMapper.selectOne(new LambdaQueryWrapper<DbColumnMeta>()
                    .eq(DbColumnMeta::getSnapshotId, latest.getId())
                    .eq(DbColumnMeta::getTableName, table)
                    .eq(DbColumnMeta::getColumnName, column).last("LIMIT 1"));
            if (columnMeta == null) throw new BusinessException(409, "所选字段在该数据源当前快照中不存在");
            if (MetadataQualityRule.CHECK_TYPE_DATA.equals(template.getCheckType())
                    && isRestrictedGovernanceStatus(columnMeta.getGovernanceStatus())) {
                throw new BusinessException(409, "废弃或阻断状态的字段不能作为数据级规则目标");
            }
        }
    }

    private void validateRuleTarget(MetadataQualityRule rule) {
        QualityRuleDTO target = new QualityRuleDTO();
        target.setScopeType(rule.getScopeType());
        target.setDatasourceId(rule.getDatasourceId());
        target.setTargetTableName(rule.getTargetTableName());
        target.setTargetColumnName(rule.getTargetColumnName());
        validateScopeShape(target.getScopeType(), target.getDatasourceId(), target.getTargetTableName(), target.getTargetColumnName());
        QualityRuleTemplateRegistry.Definition template = templateRegistry.require(rule.getTemplateCode());
        validateTargetExists(target.getScopeType(), target.getDatasourceId(), target.getTargetTableName(),
                target.getTargetColumnName(), template);
    }

    private void validateNoConflict(MetadataQualityRule candidate, Long excludeRuleId) {
        LambdaQueryWrapper<MetadataQualityRule> wrapper = new LambdaQueryWrapper<MetadataQualityRule>()
                .eq(MetadataQualityRule::getTemplateCode, candidate.getTemplateCode())
                .eq(MetadataQualityRule::getScopeType, candidate.getScopeType())
                .eq(MetadataQualityRule::getEnabled, 1)
                .isNull(MetadataQualityRule::getDeletedAt);
        if (candidate.getDatasourceId() == null) wrapper.isNull(MetadataQualityRule::getDatasourceId);
        else wrapper.eq(MetadataQualityRule::getDatasourceId, candidate.getDatasourceId());
        if (candidate.getTargetTableName() == null) wrapper.isNull(MetadataQualityRule::getTargetTableName);
        else wrapper.eq(MetadataQualityRule::getTargetTableName, candidate.getTargetTableName());
        if (candidate.getTargetColumnName() == null) wrapper.isNull(MetadataQualityRule::getTargetColumnName);
        else wrapper.eq(MetadataQualityRule::getTargetColumnName, candidate.getTargetColumnName());
        if (excludeRuleId != null) wrapper.ne(MetadataQualityRule::getId, excludeRuleId);
        // 在事务内对冲突范围加锁，避免并发启用同一模板/同一目标的重复扣分规则。
        List<MetadataQualityRule> conflicts = ruleMapper.selectList(wrapper.last("FOR UPDATE"));
        if (conflicts != null && !conflicts.isEmpty()) {
            throw new BusinessException(409, "同一检查模板、同一目标和同一级范围已有启用规则");
        }
    }

    private void requireManageTarget(String scopeType, Long datasourceId, Long userId) {
        if (QualityRuleTemplateRegistry.SCOPE_GLOBAL.equals(scopeType)) {
            if (userId == null || !authorizationResolver.isSystemAdmin(userId)) {
                throw new BusinessException(403, "全局质量规则仅受保护系统管理员可以维护");
            }
            return;
        }
        if (datasourceId == null || !authorizationResolver.canManageDatasource(userId, RULE_MANAGE, datasourceId)) {
            throw new BusinessException(403, "没有维护该数据源质量规则的权限");
        }
    }

    private void requireManageScope(MetadataQualityRule rule, Long userId) {
        requireManageTarget(rule.getScopeType(), rule.getDatasourceId(), userId);
    }

    private void requireViewScope(MetadataQualityRule rule, Long userId) {
        if (rule.getDatasourceId() == null) return;
        if (authorizationResolver.isSystemAdmin(userId)) return;
        boolean visible = capabilityService.responsibleDatasourcesWithFunction(userId, RULE_VIEW).stream()
                .anyMatch(source -> source.id().equals(rule.getDatasourceId()));
        if (!visible) throw new BusinessException(403, "没有查看该数据源质量规则的权限");
    }

    private int specificity(String scopeType) {
        if (QualityRuleTemplateRegistry.SCOPE_COLUMN.equals(scopeType)) return 3;
        if (QualityRuleTemplateRegistry.SCOPE_TABLE.equals(scopeType)) return 2;
        if (QualityRuleTemplateRegistry.SCOPE_DATASOURCE.equals(scopeType)) return 1;
        return 0;
    }

    private boolean scopeOverlaps(MetadataQualityRule moreSpecific, MetadataQualityRule broader) {
        return switch (moreSpecific.getScopeType()) {
            case QualityRuleTemplateRegistry.SCOPE_DATASOURCE -> QualityRuleTemplateRegistry.SCOPE_GLOBAL.equals(broader.getScopeType())
                    || java.util.Objects.equals(moreSpecific.getDatasourceId(), broader.getDatasourceId());
            case QualityRuleTemplateRegistry.SCOPE_TABLE -> (QualityRuleTemplateRegistry.SCOPE_GLOBAL.equals(broader.getScopeType())
                    || java.util.Objects.equals(moreSpecific.getDatasourceId(), broader.getDatasourceId()))
                    && (broader.getTargetTableName() == null
                    || java.util.Objects.equals(moreSpecific.getTargetTableName(), broader.getTargetTableName()));
            case QualityRuleTemplateRegistry.SCOPE_COLUMN -> (QualityRuleTemplateRegistry.SCOPE_GLOBAL.equals(broader.getScopeType())
                    || java.util.Objects.equals(moreSpecific.getDatasourceId(), broader.getDatasourceId()))
                    && (broader.getTargetTableName() == null
                    || java.util.Objects.equals(moreSpecific.getTargetTableName(), broader.getTargetTableName()))
                    && (broader.getTargetColumnName() == null
                    || java.util.Objects.equals(moreSpecific.getTargetColumnName(), broader.getTargetColumnName()));
            default -> false;
        };
    }

    private MetadataQualityRule requireRule(Long ruleId, boolean includeDeleted) {
        MetadataQualityRule rule = ruleMapper.selectById(ruleId);
        if (rule == null || (!includeDeleted && rule.getDeletedAt() != null)) {
            throw new BusinessException(404, "质量规则不存在");
        }
        return rule;
    }

    private QualityRuleDTO requireRequest(QualityRuleDTO request) {
        if (request == null) throw new BusinessException(400, "质量规则内容不能为空");
        return request;
    }

    private QualityRuleVO toVO(MetadataQualityRule rule) {
        QualityRuleVO vo = new QualityRuleVO();
        vo.setId(rule.getId());
        vo.setRuleCode(rule.getRuleCode());
        vo.setTemplateCode(rule.getTemplateCode());
        vo.setRuleName(rule.getRuleName());
        vo.setDimension(rule.getDimension());
        vo.setSeverity(rule.getSeverity());
        vo.setDescription(rule.getDescription());
        vo.setCheckTarget(rule.getCheckTarget());
        vo.setCheckType(rule.getCheckType());
        vo.setScopeType(rule.getScopeType() == null ? QualityRuleTemplateRegistry.SCOPE_GLOBAL : rule.getScopeType());
        vo.setDatasourceId(rule.getDatasourceId());
        vo.setTargetTableName(rule.getTargetTableName());
        vo.setTargetColumnName(rule.getTargetColumnName());
        vo.setParameters(parseParameters(rule.getParametersJson()));
        vo.setConfigVersion(rule.getConfigVersion() == null ? 1 : rule.getConfigVersion());
        vo.setEnabled(rule.getEnabled());
        vo.setDeductionPoints(rule.getDeductionPoints());
        vo.setBuiltin(rule.getBuiltin());
        vo.setDeletedAt(rule.getDeletedAt());
        vo.setCreatedAt(rule.getCreatedAt());
        vo.setUpdatedAt(rule.getUpdatedAt());
        QualityRuleTemplateRegistry.Definition template = templateRegistry.find(rule.getTemplateCode());
        vo.setSupported(template != null);
        if (template == null) vo.setUnsupportedReason("该历史规则未映射到当前已注册的检查模板");
        return vo;
    }

    private void appendRevision(MetadataQualityRule rule, String action, Long operatorUserId) {
        MetadataQualityRuleRevision revision = new MetadataQualityRuleRevision();
        revision.setRuleId(rule.getId());
        revision.setRuleCode(rule.getRuleCode());
        revision.setTemplateCode(rule.getTemplateCode());
        revision.setConfigVersion(rule.getConfigVersion() == null ? 1 : rule.getConfigVersion());
        revision.setAction(action);
        revision.setRuleName(rule.getRuleName());
        revision.setDimension(rule.getDimension());
        revision.setSeverity(rule.getSeverity());
        revision.setDescription(rule.getDescription());
        revision.setCheckTarget(rule.getCheckTarget());
        revision.setCheckType(rule.getCheckType());
        revision.setScopeType(rule.getScopeType());
        revision.setDatasourceId(rule.getDatasourceId());
        revision.setTargetTableName(rule.getTargetTableName());
        revision.setTargetColumnName(rule.getTargetColumnName());
        revision.setParametersJson(rule.getParametersJson());
        revision.setEnabled(rule.getEnabled());
        revision.setDeductionPoints(rule.getDeductionPoints());
        revision.setBuiltin(rule.getBuiltin());
        revision.setDeleted(rule.getDeletedAt() == null ? 0 : 1);
        revision.setChangedBy(operatorUserId);
        revision.setChangedAt(java.time.LocalDateTime.now());
        revisionMapper.insert(revision);
    }

    private QualityRuleRevisionVO revisionVO(MetadataQualityRuleRevision row) {
        QualityRuleRevisionVO vo = new QualityRuleRevisionVO();
        vo.setId(row.getId());
        vo.setRuleId(row.getRuleId());
        vo.setRuleCode(row.getRuleCode());
        vo.setTemplateCode(row.getTemplateCode());
        vo.setConfigVersion(row.getConfigVersion());
        vo.setAction(row.getAction());
        vo.setRuleName(row.getRuleName());
        vo.setDimension(row.getDimension());
        vo.setSeverity(row.getSeverity());
        vo.setDescription(row.getDescription());
        vo.setScopeType(row.getScopeType());
        vo.setDatasourceId(row.getDatasourceId());
        vo.setTargetTableName(row.getTargetTableName());
        vo.setTargetColumnName(row.getTargetColumnName());
        vo.setParameters(parseParameters(row.getParametersJson()));
        vo.setEnabled(row.getEnabled());
        vo.setDeductionPoints(row.getDeductionPoints());
        vo.setDeleted(Integer.valueOf(1).equals(row.getDeleted()));
        vo.setChangedBy(row.getChangedBy());
        vo.setChangedAt(row.getChangedAt());
        return vo;
    }

    private Map<String, Object> parseParameters(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            return Map.of("_invalid", "已保存参数无法读取");
        }
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private boolean isRestrictedGovernanceStatus(String status) {
        return "DEPRECATED".equals(status) || "BLOCKED".equals(status);
    }
}
