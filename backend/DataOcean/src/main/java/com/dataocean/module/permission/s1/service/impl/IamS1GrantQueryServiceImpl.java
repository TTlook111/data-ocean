package com.dataocean.module.permission.s1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1DataGrant;
import com.dataocean.module.permission.s1.entity.IamS1DataGrantColumn;
import com.dataocean.module.permission.s1.entity.IamS1DatasourceFact;
import com.dataocean.module.permission.s1.entity.IamS1FieldProtection;
import com.dataocean.module.permission.s1.entity.IamS1Role;
import com.dataocean.module.permission.s1.entity.IamS1RowCondition;
import com.dataocean.module.permission.s1.entity.vo.IamS1DataGrantVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1FieldProtectionItemVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1RowConditionSummaryVO;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantColumnMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DatasourceIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1FieldProtectionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RowConditionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1SubjectQueryMapper;
import com.dataocean.module.permission.s1.service.IamS1GrantQueryService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.dataocean.module.permission.s1.support.IamS1Labels;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** IAM-SIMPLE-1 授权配置与字段保护只读查询实现。 */
@Service
@RequiredArgsConstructor
public class IamS1GrantQueryServiceImpl implements IamS1GrantQueryService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final IamS1DataGrantMapper dataGrantMapper;
    private final IamS1DataGrantColumnMapper dataGrantColumnMapper;
    private final IamS1RowConditionMapper rowConditionMapper;
    private final IamS1FieldProtectionMapper fieldProtectionMapper;
    private final IamS1DatasourceIdentityMapper datasourceIdentityMapper;
    private final IamS1SubjectQueryMapper subjectQueryMapper;
    private final IamS1RoleMapper roleMapper;
    private final IamS1AdminGuard adminGuard;

    @Override
    public List<IamS1DataGrantVO> listGrants(Long operatorUserId, Long datasourceId, String subjectType,
                                             Long subjectId, String tableName, String status) {
        adminGuard.requireDatasourceFunction(operatorUserId, "security:permission:view", datasourceId);
        LambdaQueryWrapper<IamS1DataGrant> wrapper = new LambdaQueryWrapper<IamS1DataGrant>()
                .eq(IamS1DataGrant::getProtocolVersion, IamS1Constants.PROTOCOL_VERSION)
                .eq(IamS1DataGrant::getDatasourceId, datasourceId);
        if (subjectType != null && !subjectType.isBlank()) {
            wrapper.eq(IamS1DataGrant::getSubjectType, subjectType.trim().toUpperCase());
        }
        if (subjectId != null) {
            wrapper.eq(IamS1DataGrant::getSubjectId, subjectId);
        }
        if (tableName != null && !tableName.isBlank()) {
            wrapper.eq(IamS1DataGrant::getTableName, tableName.trim());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(IamS1DataGrant::getStatus, status.trim().toUpperCase());
        }
        wrapper.orderByAsc(IamS1DataGrant::getId);
        return toVOs(dataGrantMapper.selectList(wrapper));
    }

    @Override
    public IamS1DataGrantVO getGrant(Long operatorUserId, Long grantId) {
        IamS1DataGrant grant = requireGrant(grantId);
        adminGuard.requireDatasourceFunction(operatorUserId, "security:permission:view", grant.getDatasourceId());
        List<IamS1DataGrantVO> result = toVOs(List.of(grant));
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public List<IamS1FieldProtectionItemVO> listFieldProtections(Long operatorUserId, Long datasourceId,
                                                                 Long snapshotId, String tableName) {
        adminGuard.requireDatasourceFunction(operatorUserId, "security:mask:view", datasourceId);
        LambdaQueryWrapper<IamS1FieldProtection> wrapper = new LambdaQueryWrapper<IamS1FieldProtection>()
                .eq(IamS1FieldProtection::getProtocolVersion, IamS1Constants.PROTOCOL_VERSION)
                .eq(IamS1FieldProtection::getDatasourceId, datasourceId)
                .eq(IamS1FieldProtection::getStatus, "ACTIVE");
        if (snapshotId != null) {
            wrapper.eq(IamS1FieldProtection::getMetadataSnapshotId, snapshotId);
        }
        if (tableName != null && !tableName.isBlank()) {
            wrapper.eq(IamS1FieldProtection::getTableName, tableName.trim());
        }
        wrapper.orderByAsc(IamS1FieldProtection::getTableName, IamS1FieldProtection::getColumnMetaId);
        String datasourceName = datasourceName(datasourceId);
        List<IamS1FieldProtectionItemVO> items = new ArrayList<>();
        for (IamS1FieldProtection protection : fieldProtectionMapper.selectList(wrapper)) {
            items.add(new IamS1FieldProtectionItemVO(protection.getId(), protection.getDatasourceId(),
                    datasourceName, protection.getMetadataSnapshotId(), protection.getTableName(),
                    protection.getColumnMetaId(), protection.getColumnName(), protection.getProtectionLevel(),
                    IamS1Labels.protectionLevelName(protection.getProtectionLevel()),
                    protection.getMaskPolicy(), IamS1Labels.maskPolicyName(protection.getMaskPolicy()),
                    protection.getStatus(), protection.getRevisionNo(), protection.getUpdatedAt()));
        }
        return items;
    }

    private List<IamS1DataGrantVO> toVOs(List<IamS1DataGrant> grants) {
        if (grants == null || grants.isEmpty()) {
            return List.of();
        }
        List<Long> grantIds = grants.stream().map(IamS1DataGrant::getId).collect(Collectors.toList());
        Map<Long, List<String>> columnsByGrant = new LinkedHashMap<>();
        for (IamS1DataGrantColumn column : dataGrantColumnMapper.selectByGrantIds(grantIds)) {
            columnsByGrant.computeIfAbsent(column.getGrantId(), key -> new ArrayList<>())
                    .add(column.getColumnName());
        }
        Map<Long, List<IamS1RowCondition>> conditionsByGrant = new LinkedHashMap<>();
        for (IamS1RowCondition condition : rowConditionMapper.selectByGrantIds(grantIds)) {
            conditionsByGrant.computeIfAbsent(condition.getGrantId(), key -> new ArrayList<>()).add(condition);
        }
        Map<Long, String> datasourceNames = new HashMap<>();
        Map<String, String> subjectNames = new HashMap<>();
        List<IamS1DataGrantVO> result = new ArrayList<>();
        for (IamS1DataGrant grant : grants) {
            String datasourceName = datasourceNames.computeIfAbsent(grant.getDatasourceId(),
                    this::datasourceName);
            String subjectName = subjectNames.computeIfAbsent(
                    grant.getSubjectType() + "#" + grant.getSubjectId(),
                    key -> subjectName(grant.getSubjectType(), grant.getSubjectId()));
            List<String> columns = columnsByGrant.getOrDefault(grant.getId(), List.of());
            List<IamS1RowCondition> conditions = conditionsByGrant.getOrDefault(grant.getId(), List.of());
            List<IamS1RowConditionSummaryVO> conditionSummaries = new ArrayList<>();
            List<String> conditionTexts = new ArrayList<>();
            String rowMatchType = null;
            for (IamS1RowCondition condition : conditions) {
                if (rowMatchType == null) {
                    rowMatchType = condition.getMatchType();
                }
                String valueSummary = IamS1Labels.valueSummary(condition.getOperatorCode(),
                        condition.getStructuredValueJson());
                conditionSummaries.add(new IamS1RowConditionSummaryVO(condition.getColumnName(),
                        condition.getOperatorCode(), IamS1Labels.operatorName(condition.getOperatorCode()),
                        valueSummary));
                conditionTexts.add(condition.getColumnName() + " "
                        + IamS1Labels.operatorName(condition.getOperatorCode()) + " " + valueSummary);
            }
            boolean longTerm = grant.getValidUntil() == null;
            String summary = IamS1Labels.grantSummary(grant.getSubjectType(), subjectName,
                    grant.getDepartmentScope(), grant.getEffect(), datasourceName, grant.getTableName(),
                    columns, conditionTexts, longTerm,
                    longTerm ? null : grant.getValidUntil().format(DATE_TIME));
            result.add(new IamS1DataGrantVO(grant.getId(), grant.getDatasourceId(), datasourceName,
                    grant.getSubjectType(), IamS1Labels.subjectTypeName(grant.getSubjectType()),
                    grant.getSubjectId(), subjectName, grant.getDepartmentScope(),
                    IamS1Labels.departmentScopeName(grant.getDepartmentScope()), grant.getResourceScope(),
                    IamS1Labels.resourceScopeName(grant.getResourceScope()), grant.getMetadataSnapshotId(),
                    grant.getTableName(), grant.getEffect(), IamS1Labels.effectName(grant.getEffect()),
                    grant.getGrantSource(), grant.getSourceReferenceId(), grant.getValidFrom(),
                    grant.getValidUntil(), grant.getStatus(), grant.getRevisionNo(), columns,
                    rowMatchType, conditionSummaries, summary));
        }
        return result;
    }

    private IamS1DataGrant requireGrant(Long grantId) {
        if (grantId == null) {
            throw new BusinessException("S1 数据授权 ID 不能为空");
        }
        IamS1DataGrant grant = dataGrantMapper.selectById(grantId);
        if (grant == null || !IamS1Constants.PROTOCOL_VERSION.equals(grant.getProtocolVersion())) {
            throw new BusinessException("S1 数据授权不存在");
        }
        return grant;
    }

    private String datasourceName(Long datasourceId) {
        if (datasourceId == null) {
            return null;
        }
        IamS1DatasourceFact fact = datasourceIdentityMapper.selectIdentity(datasourceId);
        return fact == null ? null : fact.getName();
    }

    private String subjectName(String subjectType, Long subjectId) {
        if (subjectType == null || subjectId == null) {
            return null;
        }
        return switch (subjectType) {
            case IamS1Constants.SUBJECT_USER -> {
                Map<String, Object> user = subjectQueryMapper.selectUserBrief(subjectId);
                if (user == null) {
                    yield null;
                }
                Object realName = user.get("real_name");
                Object username = user.get("username");
                yield realName == null || String.valueOf(realName).isBlank()
                        ? String.valueOf(username) : String.valueOf(realName);
            }
            case IamS1Constants.SUBJECT_ROLE -> {
                IamS1Role role = roleMapper.selectById(subjectId);
                yield role == null ? null : role.getRoleName();
            }
            case IamS1Constants.SUBJECT_DEPARTMENT -> {
                Map<String, Object> department = subjectQueryMapper.selectDepartmentBrief(subjectId);
                yield department == null ? null : String.valueOf(department.get("dept_name"));
            }
            default -> null;
        };
    }
}
