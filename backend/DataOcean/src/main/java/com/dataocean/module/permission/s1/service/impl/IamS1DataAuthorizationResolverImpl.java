package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.common.util.HashUtils;
import com.dataocean.module.governance.constant.GovernanceStatuses;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1ColumnFact;
import com.dataocean.module.permission.s1.entity.IamS1DataGrant;
import com.dataocean.module.permission.s1.entity.IamS1DataGrantColumn;
import com.dataocean.module.permission.s1.entity.IamS1DepartmentNode;
import com.dataocean.module.permission.s1.entity.IamS1FieldProtection;
import com.dataocean.module.permission.s1.entity.IamS1MetadataSnapshotFact;
import com.dataocean.module.permission.s1.entity.IamS1RowCondition;
import com.dataocean.module.permission.s1.entity.IamS1Role;
import com.dataocean.module.permission.s1.entity.IamS1TableFact;
import com.dataocean.module.permission.s1.entity.IamS1UserIdentity;
import com.dataocean.module.permission.s1.entity.IamS1UserRole;
import com.dataocean.module.permission.s1.entity.dto.IamS1DataAuthorizationRequestDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1RowConditionDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1TableRequestDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot;
import com.dataocean.module.permission.s1.entity.vo.IamS1FieldProtectionVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1GrantSourceVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1RowConditionVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1RowPredicateVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1TablePermissionVO;
import com.dataocean.module.permission.s1.enums.IamS1ColumnUsage;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantColumnMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DatasourceIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DepartmentMapper;
import com.dataocean.module.permission.s1.mapper.IamS1FieldProtectionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1MetadataResourceMapper;
import com.dataocean.module.permission.s1.mapper.IamS1PermissionRevisionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RowConditionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserRoleMapper;
import com.dataocean.module.permission.s1.service.IamS1DataAuthorizationResolver;
import com.dataocean.module.permission.s1.support.IamS1RowConditionValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * IAM-SIMPLE-1 数据授权唯一计算入口。
 * <p>
 * 本实现只读取 S1 数据授权事实、S1 角色绑定、账号/组织、数据源和已发布元数据。
 * 同一份 grant 的字段和记录条件始终一起进入合并结果，预览直接调用本类的 resolve。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IamS1DataAuthorizationResolverImpl implements IamS1DataAuthorizationResolver {

    private static final Set<String> SAFE_MASK_POLICIES = Set.of("PHONE", "ID_CARD", "EMAIL", "BANK_CARD", "NAME");

    private final IamS1UserIdentityMapper userIdentityMapper;
    private final IamS1DepartmentMapper departmentMapper;
    private final IamS1DatasourceIdentityMapper datasourceIdentityMapper;
    private final IamS1RoleMapper roleMapper;
    private final IamS1UserRoleMapper userRoleMapper;
    private final IamS1MetadataResourceMapper metadataResourceMapper;
    private final IamS1DataGrantMapper dataGrantMapper;
    private final IamS1DataGrantColumnMapper dataGrantColumnMapper;
    private final IamS1RowConditionMapper rowConditionMapper;
    private final IamS1FieldProtectionMapper fieldProtectionMapper;
    private final IamS1PermissionRevisionMapper permissionRevisionMapper;
    private final IamS1PermissionCacheService permissionCacheService;

    @Override
    public IamS1DataAuthorizationSnapshot resolve(IamS1DataAuthorizationRequestDTO request) {
        if (request == null) {
            return IamS1DataAuthorizationSnapshot.deny("MISSING_REQUIRED_PARAMETER", null, null, null);
        }
        if (!IamS1Constants.PROTOCOL_VERSION.equals(request.getProtocolVersion())) {
            return deny("UNKNOWN_PROTOCOL", request, null, null);
        }
        if (request.getUserId() == null || request.getDatasourceId() == null
                || request.getActiveMetadataSnapshotId() == null || request.getCalculatedAt() == null) {
            return deny("MISSING_REQUIRED_PARAMETER", request, null, null);
        }
        try {
            IamS1UserIdentity user = userIdentityMapper.selectIdentity(request.getUserId());
            if (user == null || !Integer.valueOf(IamS1Constants.ENABLED).equals(user.getStatus())) {
                return deny("USER_NOT_ENABLED", request, null, null);
            }
            var datasource = datasourceIdentityMapper.selectIdentity(request.getDatasourceId());
            if (datasource == null) {
                return deny("DATASOURCE_NOT_FOUND", request, null, null);
            }
            if (!Integer.valueOf(IamS1Constants.ENABLED).equals(datasource.getStatus())) {
                return deny("DATASOURCE_NOT_ENABLED", request, null, datasource.getName());
            }
            if (!hasTablesAndColumns(request)) {
                return deny("EMPTY_RESOURCE_COLUMNS", request, null, datasource.getName());
            }
            if (hasDuplicateTables(request)) {
                return deny("DUPLICATE_TABLE_REQUEST", request, null, datasource.getName());
            }

            Long revision = permissionRevisionMapper.selectCurrentRevision();
            if (revision == null) {
                return deny("REVISION_UNAVAILABLE", request, null, datasource.getName());
            }
            DepartmentPath departmentPath = resolveDepartmentPath(user.getDepartmentId());
            if (!departmentPath.valid()) {
                return deny("DEPARTMENT_PATH_INVALID", request, revision, datasource.getName());
            }

            String cacheKey = buildCacheKey(request, revision, user.getDepartmentId(), departmentPath.ids());
            IamS1DataAuthorizationSnapshot cached = permissionCacheService.read(
                    cacheKey, IamS1DataAuthorizationSnapshot.class);
            if (isUsableCachedSnapshot(cached, request.getCalculatedAt())) {
                return cached;
            }

            IamS1MetadataSnapshotFact snapshot = metadataResourceMapper.selectSnapshot(
                    request.getActiveMetadataSnapshotId(), request.getDatasourceId());
            if (snapshot == null || !"PUBLISHED".equals(snapshot.getStatus())) {
                return deny("SNAPSHOT_NOT_PUBLISHED", request, revision, datasource.getName());
            }

            List<IamS1DataGrant> allGrants = dataGrantMapper.selectActiveByDatasource(
                    IamS1Constants.PROTOCOL_VERSION, request.getDatasourceId());
            if (allGrants == null) {
                return deny("FACT_READ_FAILED", request, revision, datasource.getName());
            }
            List<IamS1DataGrant> matchedGrants = matchGrants(allGrants, request.getUserId(), departmentPath);
            Map<Long, IamS1DataGrant> grantById = matchedGrants.stream()
                    .filter(grant -> grant.getId() != null)
                    .collect(Collectors.toMap(IamS1DataGrant::getId, item -> item, (left, right) -> left,
                            LinkedHashMap::new));
            List<Long> grantIds = new ArrayList<>(grantById.keySet());
            List<IamS1DataGrantColumn> grantColumns = grantIds.isEmpty()
                    ? List.of() : dataGrantColumnMapper.selectByGrantIds(grantIds);
            List<IamS1RowCondition> conditions = grantIds.isEmpty()
                    ? List.of() : rowConditionMapper.selectByGrantIds(grantIds);
            List<IamS1FieldProtection> protections = fieldProtectionMapper.selectActiveBySnapshot(
                    IamS1Constants.PROTOCOL_VERSION, request.getDatasourceId(), request.getActiveMetadataSnapshotId());
            if (grantColumns == null || conditions == null || protections == null) {
                return deny("FACT_READ_FAILED", request, revision, datasource.getName());
            }

            Computation computation = compute(request, matchedGrants, grantColumns, conditions, protections,
                    departmentPath, request.getCalculatedAt());
            IamS1DataAuthorizationSnapshot result = new IamS1DataAuthorizationSnapshot(
                    computation.allowed(), computation.reasonCode(), IamS1Constants.PROTOCOL_VERSION,
                    request.getUserId(), request.getDatasourceId(), datasource.getName(),
                    request.getActiveMetadataSnapshotId(), revision, request.getCalculatedAt(),
                    computation.nextEffectiveAt(), computation.tables());
            permissionCacheService.write(cacheKey, request.getDatasourceId(), result,
                    new IamS1PermissionCacheService.LocalDateTimeBoundary(
                            request.getCalculatedAt(), computation.nextEffectiveAt()));
            return result;
        } catch (RuntimeException exception) {
            // 不把事实异常、条件参数或内部堆栈返回给调用方，也不扩大权限。
            log.warn("S1 数据权限事实读取失败 userId={} datasourceId={}",
                    request.getUserId(), request.getDatasourceId());
            return deny("FACT_READ_FAILED", request, null, null);
        }
    }

    @Override
    public boolean hasEffectiveAllowGrant(Long userId, Long datasourceId, LocalDateTime at) {
        if (userId == null || datasourceId == null) {
            return false;
        }
        LocalDateTime now = at == null ? LocalDateTime.now() : at;
        IamS1UserIdentity user = userIdentityMapper.selectIdentity(userId);
        if (user == null || !Integer.valueOf(IamS1Constants.ENABLED).equals(user.getStatus())) {
            return false;
        }
        // 与 resolve 完全相同的主体匹配与有效期判定：部门路径损坏时按“无授权”处理，不放大范围。
        DepartmentPath path = resolveDepartmentPath(user.getDepartmentId());
        if (!path.valid()) {
            return false;
        }
        List<IamS1DataGrant> grants = dataGrantMapper.selectActiveByDatasource(
                IamS1Constants.PROTOCOL_VERSION, datasourceId);
        if (grants == null) {
            return false;
        }
        for (IamS1DataGrant grant : matchGrants(grants, userId, path)) {
            if (IamS1Constants.EFFECT_ALLOW.equals(grant.getEffect()) && isEffective(grant, now)) {
                return true;
            }
        }
        return false;
    }

    private Computation compute(IamS1DataAuthorizationRequestDTO request, List<IamS1DataGrant> grants,
                                List<IamS1DataGrantColumn> grantColumns, List<IamS1RowCondition> conditions,
                                List<IamS1FieldProtection> protections, DepartmentPath departmentPath,
                                LocalDateTime calculatedAt) {
        Map<Long, List<IamS1DataGrantColumn>> columnsByGrant = grantColumns.stream()
                .filter(item -> item.getGrantId() != null)
                .collect(Collectors.groupingBy(IamS1DataGrantColumn::getGrantId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<IamS1RowCondition>> conditionsByGrant = conditions.stream()
                .filter(item -> item.getGrantId() != null)
                .collect(Collectors.groupingBy(IamS1RowCondition::getGrantId, LinkedHashMap::new, Collectors.toList()));
        Map<String, IamS1TableRequestDTO> requestsByTable = request.getTables().stream()
                .collect(Collectors.toMap(item -> item.getTableName().trim(), item -> item,
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, List<IamS1FieldProtection>> protectionsByColumn = protections.stream()
                .filter(item -> item.getTableName() != null && item.getColumnMetaId() != null)
                .collect(Collectors.groupingBy(item -> protectionKey(item.getTableName(), item.getColumnMetaId()),
                        LinkedHashMap::new, Collectors.toList()));
        LocalDateTime nextEffectiveAt = nextEffectiveAt(grants, calculatedAt);
        List<IamS1TablePermissionVO> tableResults = new ArrayList<>();
        for (Map.Entry<String, IamS1TableRequestDTO> entry : requestsByTable.entrySet()) {
            TableComputation table = computeTable(request, entry.getKey(), entry.getValue(), grants,
                    columnsByGrant, conditionsByGrant, protectionsByColumn, departmentPath, calculatedAt);
            tableResults.add(table.permission());
            nextEffectiveAt = earliest(nextEffectiveAt, table.nextEffectiveAt());
            if (!table.allowed()) {
                return new Computation(false, table.reasonCode(), nextEffectiveAt, tableResults);
            }
        }
        return new Computation(true, "ALLOWED", nextEffectiveAt, tableResults);
    }

    private TableComputation computeTable(IamS1DataAuthorizationRequestDTO request, String tableName,
                                           IamS1TableRequestDTO tableRequest, List<IamS1DataGrant> grants,
                                           Map<Long, List<IamS1DataGrantColumn>> columnsByGrant,
                                           Map<Long, List<IamS1RowCondition>> conditionsByGrant,
                                           Map<String, List<IamS1FieldProtection>> protectionsByColumn,
                                           DepartmentPath departmentPath, LocalDateTime calculatedAt) {
        IamS1TableFact tableFact = metadataResourceMapper.selectTable(
                request.getActiveMetadataSnapshotId(), request.getDatasourceId(), tableName);
        if (tableFact == null) {
            return tableDeny(tableName, "TABLE_NOT_IN_SNAPSHOT", "表不在当前已发布快照中");
        }
        if (isBlocked(tableFact.getGovernanceStatus())) {
            return tableDeny(tableName, governanceReason(tableFact.getGovernanceStatus()), "表的治理状态不允许问数");
        }
        List<IamS1ColumnFact> allColumns = metadataResourceMapper.selectColumns(
                request.getActiveMetadataSnapshotId(), request.getDatasourceId(), tableName);
        if (allColumns == null || allColumns.isEmpty()) {
            return tableDeny(tableName, "TABLE_COLUMNS_NOT_PUBLISHED", "表没有可用的已发布字段");
        }
        Map<String, IamS1ColumnFact> columnsByName = allColumns.stream()
                .filter(item -> item.getColumnName() != null)
                .collect(Collectors.toMap(IamS1ColumnFact::getColumnName, item -> item,
                        (left, right) -> left, LinkedHashMap::new));
        Set<String> referencedColumns = normalizeReferencedColumns(tableRequest);
        if (referencedColumns.isEmpty()) {
            return tableDeny(tableName, "EMPTY_RESOURCE_COLUMNS", "本次查询没有明确字段白名单");
        }
        for (String columnName : referencedColumns) {
            IamS1ColumnFact column = columnsByName.get(columnName);
            if (column == null) {
                return tableDeny(tableName, "COLUMN_NOT_IN_SNAPSHOT", "引用字段不在当前快照中");
            }
            if (isBlocked(column.getGovernanceStatus())) {
                return tableDeny(tableName, governanceReason(column.getGovernanceStatus()), "字段的治理状态不允许问数");
            }
        }

        List<IamS1FieldProtectionVO> fieldProtectionViews = new ArrayList<>();
        for (String columnName : referencedColumns) {
            IamS1ColumnFact column = columnsByName.get(columnName);
            IamS1FieldProtection merged = mergeProtection(
                    protectionsByColumn.getOrDefault(protectionKey(tableName, column.getId()), List.of()));
            String level = merged == null ? IamS1Constants.PROTECTION_NORMAL : merged.getProtectionLevel();
            String policy = merged == null ? null : merged.getMaskPolicy();
            fieldProtectionViews.add(new IamS1FieldProtectionVO(column.getId(), tableName, columnName,
                    level, policy, protectionReason(level)));
            if (IamS1Constants.PROTECTION_HIDDEN.equals(level)) {
                return tableDeny(tableName, "FIELD_HIDDEN", "字段已被隐藏，不能进入本次查询", fieldProtectionViews);
            }
            if (IamS1Constants.PROTECTION_MASKED.equals(level)
                    && !isProjectionOnly(tableRequest, columnName)) {
                return tableDeny(tableName, "MASKED_FIELD_USAGE_FORBIDDEN", "脱敏字段只能直接投影", fieldProtectionViews);
            }
        }

        for (IamS1DataGrant grant : grants) {
            if (!isValidGrantShape(grant)) {
                return tableDeny(tableName, "INVALID_GRANT", "S1 授权事实结构不完整");
            }
            if (IamS1Constants.RESOURCE_SCOPE_DATASOURCE.equals(grant.getResourceScope())
                    && IamS1Constants.EFFECT_DENY.equals(grant.getEffect())
                    && isEffective(grant, calculatedAt)) {
                return tableDeny(tableName, "DATASOURCE_DENY", "数据源禁止查询");
            }
        }

        List<IamS1DataGrant> tableGrants = grants.stream()
                .filter(item -> IamS1Constants.RESOURCE_SCOPE_TABLE.equals(item.getResourceScope()))
                .filter(item -> tableName.equals(item.getTableName()))
                .filter(item -> request.getActiveMetadataSnapshotId().equals(item.getMetadataSnapshotId()))
                .filter(item -> isEffective(item, calculatedAt))
                .toList();
        List<IamS1DataGrant> qualifiedAllows = new ArrayList<>();
        Set<Long> deniedColumns = new HashSet<>();
        boolean tableDenied = false;
        LocalDateTime nextEffectiveAt = null;
        for (IamS1DataGrant grant : tableGrants) {
            nextEffectiveAt = earliest(nextEffectiveAt, futureBoundary(grant, calculatedAt));
            List<IamS1DataGrantColumn> grantColumnList = columnsByGrant.getOrDefault(grant.getId(), List.of());
            for (IamS1DataGrantColumn column : grantColumnList) {
                IamS1ColumnFact fact = columnsByName.get(column.getColumnName());
                if (column.getMetadataSnapshotId() == null
                        || !request.getActiveMetadataSnapshotId().equals(column.getMetadataSnapshotId())
                        || !tableName.equals(column.getTableName()) || fact == null
                        || !fact.getId().equals(column.getColumnMetaId())) {
                    return tableDeny(tableName, "INVALID_GRANT", "S1 授权字段事实与当前快照不匹配");
                }
            }
            Set<String> grantColumnNames = grantColumnList.stream().map(IamS1DataGrantColumn::getColumnName)
                    .filter(item -> item != null).collect(Collectors.toCollection(LinkedHashSet::new));
            if (IamS1Constants.EFFECT_ALLOW.equals(grant.getEffect())) {
                if (grantColumnNames.isEmpty()) {
                    return tableDeny(tableName, "ALLOW_FIELDS_EMPTY", "允许授权必须明确选择字段");
                }
                if (grantColumnNames.containsAll(referencedColumns)) {
                    List<IamS1RowCondition> grantConditions = conditionsByGrant.getOrDefault(grant.getId(), List.of());
                    if (!validateConditionRows(grant, grantConditions, columnsByName, protectionsByColumn)) {
                        return tableDeny(tableName, "INVALID_ROW_CONDITION", "记录条件不是合法的结构化条件");
                    }
                    qualifiedAllows.add(grant);
                }
            } else if (IamS1Constants.EFFECT_DENY.equals(grant.getEffect())) {
                if (!conditionsByGrant.getOrDefault(grant.getId(), List.of()).isEmpty()) {
                    return tableDeny(tableName, "DENY_ROW_CONDITION_FORBIDDEN", "DENY 不支持记录级条件");
                }
                if (grantColumnNames.isEmpty()) {
                    tableDenied = true;
                } else {
                    for (IamS1DataGrantColumn column : grantColumnList) {
                        IamS1ColumnFact fact = columnsByName.get(column.getColumnName());
                        if (fact != null && referencedColumns.contains(column.getColumnName())) {
                            deniedColumns.add(fact.getId());
                        }
                    }
                }
            } else {
                return tableDeny(tableName, "INVALID_GRANT_EFFECT", "授权效果不合法");
            }
        }
        if (tableDenied) {
            return tableDeny(tableName, "TABLE_DENY", "表级禁止优先于允许授权");
        }
        if (!deniedColumns.isEmpty()) {
            return tableDeny(tableName, "FIELD_DENY", "引用字段命中字段级禁止");
        }
        if (qualifiedAllows.isEmpty()) {
            return tableDeny(tableName, "NO_ALLOW_COVERING_FIELDS", "没有单份授权完整覆盖本次引用字段");
        }

        List<IamS1GrantSourceVO> sources = qualifiedAllows.stream()
                .map(grant -> toGrantSource(grant, columnsByGrant.getOrDefault(grant.getId(), List.of()),
                        conditionsByGrant.getOrDefault(grant.getId(), List.of())))
                .toList();
        IamS1TablePermissionVO permission = new IamS1TablePermissionVO(true, "ALLOWED", tableName,
                referencedColumns.stream().sorted().toList(), sources, fieldProtectionViews,
                List.of("已找到至少一份完整覆盖字段的 S1 ALLOW 授权；同表条件按 OR 合并"));
        return new TableComputation(true, "ALLOWED", nextEffectiveAt, permission);
    }

    private IamS1GrantSourceVO toGrantSource(IamS1DataGrant grant, List<IamS1DataGrantColumn> columns,
                                              List<IamS1RowCondition> conditions) {
        List<String> explicitColumns = columns.stream().map(IamS1DataGrantColumn::getColumnName)
                .filter(item -> item != null).distinct().sorted().toList();
        IamS1RowConditionVO condition = null;
        if (!conditions.isEmpty()) {
            String matchType = conditions.get(0).getMatchType();
            List<IamS1RowPredicateVO> predicates = conditions.stream().sorted(Comparator.comparing(
                            item -> item.getSequenceNo() == null ? Integer.MAX_VALUE : item.getSequenceNo()))
                    .map(item -> new IamS1RowPredicateVO(item.getColumnMetaId(), item.getColumnName(),
                            item.getOperatorCode(), item.getValueType(), item.getParameterReference(),
                            "grant-" + grant.getId() + "-condition-" + item.getId()))
                    .toList();
            condition = new IamS1RowConditionVO(matchType, predicates);
        }
        return new IamS1GrantSourceVO(grant.getId(), grant.getSubjectType(), grant.getSubjectId(),
                sourceSummary(grant), grant.getDepartmentScope(), grant.getGrantSource(),
                grant.getSourceReferenceId(), grant.getValidFrom(), grant.getValidUntil(), explicitColumns, condition);
    }

    private boolean validateConditionRows(IamS1DataGrant grant, List<IamS1RowCondition> rows,
                                          Map<String, IamS1ColumnFact> columnsByName,
                                          Map<String, List<IamS1FieldProtection>> protectionsByColumn) {
        if (rows.isEmpty()) {
            return true;
        }
        String matchType = rows.get(0).getMatchType();
        if (!IamS1Constants.ROW_MATCH_ALL.equals(matchType) && !IamS1Constants.ROW_MATCH_ANY.equals(matchType)) {
            return false;
        }
        int expectedSequence = 1;
        for (IamS1RowCondition row : rows) {
            if (row.getSequenceNo() == null || row.getSequenceNo() != expectedSequence
                    || !matchType.equals(row.getMatchType()) || row.getColumnMetaId() == null
                    || row.getColumnName() == null || row.getOperatorCode() == null
                    || row.getValueType() == null || row.getTableName() == null
                    || !row.getTableName().equals(grant.getTableName())
                    || !grant.getMetadataSnapshotId().equals(row.getMetadataSnapshotId())) {
                return false;
            }
            IamS1ColumnFact fact = columnsByName.get(row.getColumnName());
            if (fact == null || !fact.getId().equals(row.getColumnMetaId())
                    || isProtectedForCondition(protectionsByColumn.getOrDefault(
                    protectionKey(grant.getTableName(), fact.getId()), List.of()))) {
                return false;
            }
            if (!isStructuredCondition(row)) {
                return false;
            }
            IamS1RowConditionDTO dto = new IamS1RowConditionDTO(row.getColumnMetaId(), row.getColumnName(),
                    row.getOperatorCode(), row.getValueType(), row.getStructuredValueJson(), row.getParameterReference());
            try {
                String canonicalValue = IamS1RowConditionValidator.validateAndCanonicalize(dto, fact.getDataType());
                if (!java.util.Objects.equals(canonicalValue, row.getStructuredValueJson())) {
                    return false;
                }
            } catch (RuntimeException exception) {
                return false;
            }
            expectedSequence++;
        }
        return true;
    }

    private boolean isStructuredCondition(IamS1RowCondition row) {
        Set<String> operators = Set.of("EQ", "NE", "GT", "GE", "LT", "LE", "IN", "NOT_IN",
                "IS_NULL", "IS_NOT_NULL");
        Set<String> types = Set.of("STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "DATETIME",
                "STRING_LIST", "INTEGER_LIST", "DECIMAL_LIST", "NULL");
        if (!operators.contains(row.getOperatorCode()) || !types.contains(row.getValueType())) {
            return false;
        }
        boolean nullOperator = "IS_NULL".equals(row.getOperatorCode()) || "IS_NOT_NULL".equals(row.getOperatorCode());
        boolean hasValue = row.getStructuredValueJson() != null && !row.getStructuredValueJson().isBlank();
        boolean hasParameter = row.getParameterReference() != null && !row.getParameterReference().isBlank();
        if (nullOperator) {
            return "NULL".equals(row.getValueType()) && !hasValue && !hasParameter;
        }
        return hasValue ^ hasParameter;
    }

    private List<IamS1DataGrant> matchGrants(List<IamS1DataGrant> grants, Long userId,
                                             DepartmentPath departmentPath) {
        Set<Long> activeRoleIds = new HashSet<>();
        List<IamS1UserRole> bindings = userRoleMapper.selectActiveByUserId(userId);
        if (bindings != null) {
            for (IamS1UserRole binding : bindings) {
                if (binding == null || binding.getRoleId() == null || binding.getStatus() == null
                        || !Integer.valueOf(IamS1Constants.ENABLED).equals(binding.getStatus())) {
                    continue;
                }
                IamS1Role role = roleMapper.selectById(binding.getRoleId());
                if (role != null && Integer.valueOf(IamS1Constants.ENABLED).equals(role.getStatus())) {
                    activeRoleIds.add(role.getId());
                }
            }
        }
        return grants.stream().filter(grant -> grant != null && IamS1Constants.PROTOCOL_VERSION.equals(
                        grant.getProtocolVersion()))
                .filter(grant -> switch (grant.getSubjectType()) {
                    case IamS1Constants.SUBJECT_USER -> userId.equals(grant.getSubjectId());
                    case IamS1Constants.SUBJECT_ROLE -> activeRoleIds.contains(grant.getSubjectId());
                    case IamS1Constants.SUBJECT_DEPARTMENT -> matchesDepartment(grant, departmentPath);
                    default -> false;
                }).toList();
    }

    private boolean matchesDepartment(IamS1DataGrant grant, DepartmentPath path) {
        if (!path.valid() || grant.getSubjectId() == null || !path.ids().contains(grant.getSubjectId())) {
            return false;
        }
        if (IamS1Constants.DEPARTMENT_SCOPE_SELF.equals(grant.getDepartmentScope())) {
            return grant.getSubjectId().equals(path.currentId());
        }
        return IamS1Constants.DEPARTMENT_SCOPE_INCLUDE_DESCENDANTS.equals(grant.getDepartmentScope());
    }

    private DepartmentPath resolveDepartmentPath(Long currentDepartmentId) {
        if (currentDepartmentId == null || currentDepartmentId == 0L) {
            return new DepartmentPath(true, null, Set.of());
        }
        List<IamS1DepartmentNode> nodes = departmentMapper.selectAll();
        if (nodes == null) {
            return new DepartmentPath(false, currentDepartmentId, Set.of());
        }
        Map<Long, IamS1DepartmentNode> byId = nodes.stream().filter(item -> item != null && item.getId() != null)
                .collect(Collectors.toMap(IamS1DepartmentNode::getId, item -> item, (left, right) -> left));
        LinkedHashSet<Long> path = new LinkedHashSet<>();
        Long current = currentDepartmentId;
        while (current != null && current != 0L) {
            if (!path.add(current)) {
                return new DepartmentPath(false, currentDepartmentId, path);
            }
            IamS1DepartmentNode node = byId.get(current);
            if (node == null || !Integer.valueOf(IamS1Constants.ENABLED).equals(node.getStatus())) {
                return new DepartmentPath(false, currentDepartmentId, path);
            }
            current = node.getParentId();
        }
        return new DepartmentPath(true, currentDepartmentId, path);
    }

    private boolean isValidGrantShape(IamS1DataGrant grant) {
        if (grant == null || !IamS1Constants.PROTOCOL_VERSION.equals(grant.getProtocolVersion())
                || grant.getId() == null || grant.getDatasourceId() == null
                || grant.getStatus() == null || !IamS1Constants.DATA_GRANT_STATUS_ACTIVE.equals(grant.getStatus())
                || grant.getValidFrom() == null) {
            return false;
        }
        if (grant.getValidUntil() != null && !grant.getValidUntil().isAfter(grant.getValidFrom())) {
            return false;
        }
        if (!Set.of(IamS1Constants.SUBJECT_USER, IamS1Constants.SUBJECT_ROLE,
                IamS1Constants.SUBJECT_DEPARTMENT).contains(grant.getSubjectType())
                || !Set.of(IamS1Constants.EFFECT_ALLOW, IamS1Constants.EFFECT_DENY).contains(grant.getEffect())) {
            return false;
        }
        if (IamS1Constants.SUBJECT_DEPARTMENT.equals(grant.getSubjectType())
                && !Set.of(IamS1Constants.DEPARTMENT_SCOPE_SELF,
                IamS1Constants.DEPARTMENT_SCOPE_INCLUDE_DESCENDANTS).contains(grant.getDepartmentScope())) {
            return false;
        }
        if (!IamS1Constants.SUBJECT_DEPARTMENT.equals(grant.getSubjectType())
                && grant.getDepartmentScope() != null) {
            return false;
        }
        if (!Set.of(IamS1Constants.RESOURCE_SCOPE_DATASOURCE, IamS1Constants.RESOURCE_SCOPE_TABLE)
                .contains(grant.getResourceScope())) {
            return false;
        }
        if (IamS1Constants.RESOURCE_SCOPE_DATASOURCE.equals(grant.getResourceScope())) {
            return IamS1Constants.EFFECT_DENY.equals(grant.getEffect()) && grant.getTableName() == null;
        }
        return grant.getMetadataSnapshotId() != null && grant.getTableName() != null
                && !grant.getTableName().isBlank();
    }

    private boolean isEffective(IamS1DataGrant grant, LocalDateTime at) {
        return grant.getValidFrom() != null && !at.isBefore(grant.getValidFrom())
                && (grant.getValidUntil() == null || at.isBefore(grant.getValidUntil()));
    }

    private LocalDateTime nextEffectiveAt(List<IamS1DataGrant> grants, LocalDateTime at) {
        return grants.stream().filter(this::isShapePartiallyValid)
                .map(grant -> futureBoundary(grant, at)).filter(item -> item != null)
                .min(Comparator.naturalOrder()).orElse(null);
    }

    private boolean isShapePartiallyValid(IamS1DataGrant grant) {
        return grant != null && IamS1Constants.PROTOCOL_VERSION.equals(grant.getProtocolVersion())
                && IamS1Constants.DATA_GRANT_STATUS_ACTIVE.equals(grant.getStatus())
                && grant.getValidFrom() != null;
    }

    private LocalDateTime futureBoundary(IamS1DataGrant grant, LocalDateTime at) {
        LocalDateTime boundary = null;
        if (grant.getValidFrom() != null && grant.getValidFrom().isAfter(at)) {
            boundary = grant.getValidFrom();
        }
        if (grant.getValidUntil() != null && grant.getValidUntil().isAfter(at)) {
            boundary = earliest(boundary, grant.getValidUntil());
        }
        return boundary;
    }

    private IamS1FieldProtection mergeProtection(List<IamS1FieldProtection> rules) {
        IamS1FieldProtection strongest = null;
        for (IamS1FieldProtection rule : rules) {
            if (rule == null || !IamS1Constants.PROTOCOL_VERSION.equals(rule.getProtocolVersion())
                    || !"ACTIVE".equals(rule.getStatus())) {
                continue;
            }
            if (!isKnownProtectionLevel(rule.getProtectionLevel())) {
                IamS1FieldProtection hidden = new IamS1FieldProtection();
                hidden.setColumnMetaId(rule.getColumnMetaId());
                hidden.setColumnName(rule.getColumnName());
                hidden.setTableName(rule.getTableName());
                hidden.setProtectionLevel(IamS1Constants.PROTECTION_HIDDEN);
                hidden.setMaskPolicy(null);
                rule = hidden;
            } else if (IamS1Constants.PROTECTION_MASKED.equals(rule.getProtectionLevel())
                    && (rule.getMaskPolicy() == null || !SAFE_MASK_POLICIES.contains(rule.getMaskPolicy()))) {
                IamS1FieldProtection hidden = new IamS1FieldProtection();
                hidden.setColumnMetaId(rule.getColumnMetaId());
                hidden.setColumnName(rule.getColumnName());
                hidden.setTableName(rule.getTableName());
                hidden.setProtectionLevel(IamS1Constants.PROTECTION_HIDDEN);
                hidden.setMaskPolicy(null);
                rule = hidden;
            }
            if (strongest == null || protectionRank(rule.getProtectionLevel()) > protectionRank(strongest.getProtectionLevel())) {
                strongest = rule;
            } else if (strongest != null && protectionRank(rule.getProtectionLevel()) == protectionRank(strongest.getProtectionLevel())
                    && IamS1Constants.PROTECTION_MASKED.equals(rule.getProtectionLevel())
                    && !java.util.Objects.equals(rule.getMaskPolicy(), strongest.getMaskPolicy())) {
                IamS1FieldProtection hidden = new IamS1FieldProtection();
                hidden.setColumnMetaId(rule.getColumnMetaId());
                hidden.setColumnName(rule.getColumnName());
                hidden.setTableName(rule.getTableName());
                hidden.setProtectionLevel(IamS1Constants.PROTECTION_HIDDEN);
                hidden.setMaskPolicy(null);
                strongest = hidden;
            }
        }
        return strongest;
    }

    private boolean isProtectedForCondition(List<IamS1FieldProtection> rules) {
        IamS1FieldProtection merged = mergeProtection(rules);
        return merged != null && (IamS1Constants.PROTECTION_HIDDEN.equals(merged.getProtectionLevel())
                || IamS1Constants.PROTECTION_MASKED.equals(merged.getProtectionLevel()));
    }

    private boolean isProjectionOnly(IamS1TableRequestDTO request, String columnName) {
        if (request.getColumnUsages() == null || !request.getColumnUsages().containsKey(columnName)) {
            return false;
        }
        Set<IamS1ColumnUsage> usages = request.getColumnUsages().get(columnName);
        return usages != null && usages.size() == 1 && usages.contains(IamS1ColumnUsage.PROJECTION);
    }

    private Set<String> normalizeReferencedColumns(IamS1TableRequestDTO request) {
        if (request == null || request.getReferencedColumns() == null) {
            return Set.of();
        }
        return request.getReferencedColumns().stream().filter(item -> item != null && !item.isBlank())
                .map(String::trim).filter(item -> !"*".equals(item)).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean hasTablesAndColumns(IamS1DataAuthorizationRequestDTO request) {
        if (request.getTables() == null || request.getTables().isEmpty()) {
            return false;
        }
        for (IamS1TableRequestDTO table : request.getTables()) {
            if (table == null || table.getTableName() == null || table.getTableName().isBlank()
                    || normalizeReferencedColumns(table).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasDuplicateTables(IamS1DataAuthorizationRequestDTO request) {
        Set<String> tableNames = new HashSet<>();
        for (IamS1TableRequestDTO table : request.getTables()) {
            if (table == null || table.getTableName() == null
                    || !tableNames.add(table.getTableName().trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean isUsableCachedSnapshot(IamS1DataAuthorizationSnapshot snapshot, LocalDateTime at) {
        return snapshot != null && (snapshot.getNextEffectiveAt() == null
                || at.isBefore(snapshot.getNextEffectiveAt()));
    }

    private String buildCacheKey(IamS1DataAuthorizationRequestDTO request, Long revision, Long departmentId,
                                 Collection<Long> departmentPath) {
        String resourceShape = request.getTables().stream().map(table -> table.getTableName() + ":"
                        + normalizeReferencedColumns(table).stream().sorted().collect(Collectors.joining(",")) + ":"
                        + String.valueOf(table.getColumnUsages()))
                .sorted().collect(Collectors.joining("|"));
        String digest = HashUtils.sha256Hex(resourceShape + "|dept=" + departmentId + "|path=" + departmentPath);
        return IamS1Constants.PERMISSION_CACHE_PREFIX + IamS1Constants.PROTOCOL_VERSION + ":"
                + request.getUserId() + ":" + request.getDatasourceId() + ":" + revision + ":" + digest;
    }

    private IamS1DataAuthorizationSnapshot deny(String reason, IamS1DataAuthorizationRequestDTO request,
                                                 Long revision, String datasourceName) {
        return IamS1DataAuthorizationSnapshot.deny(reason, request, revision, datasourceName);
    }

    private TableComputation tableDeny(String tableName, String reasonCode, String reason) {
        return new TableComputation(false, reasonCode, null,
                new IamS1TablePermissionVO(false, reasonCode, tableName, List.of(), List.of(), List.of(), List.of(reason)));
    }

    private TableComputation tableDeny(String tableName, String reasonCode, String reason,
                                       List<IamS1FieldProtectionVO> protections) {
        return new TableComputation(false, reasonCode, null,
                new IamS1TablePermissionVO(false, reasonCode, tableName, List.of(), List.of(), protections, List.of(reason)));
    }

    private String sourceSummary(IamS1DataGrant grant) {
        return switch (grant.getSubjectType()) {
            case IamS1Constants.SUBJECT_USER -> "用户个人授权";
            case IamS1Constants.SUBJECT_ROLE -> "角色授权";
            case IamS1Constants.SUBJECT_DEPARTMENT -> "部门默认授权";
            default -> "S1 授权";
        };
    }

    private String protectionReason(String level) {
        return switch (level) {
            case IamS1Constants.PROTECTION_HIDDEN -> "字段保护为隐藏";
            case IamS1Constants.PROTECTION_MASKED -> "字段保护为脱敏";
            default -> "未命中字段保护规则";
        };
    }

    private int protectionRank(String level) {
        return switch (level) {
            case IamS1Constants.PROTECTION_HIDDEN -> 3;
            case IamS1Constants.PROTECTION_MASKED -> 2;
            case IamS1Constants.PROTECTION_NORMAL -> 1;
            default -> 3;
        };
    }

    private boolean isKnownProtectionLevel(String level) {
        return IamS1Constants.PROTECTION_NORMAL.equals(level)
                || IamS1Constants.PROTECTION_HIDDEN.equals(level)
                || IamS1Constants.PROTECTION_MASKED.equals(level);
    }

    private String governanceReason(String status) {
        if (GovernanceStatuses.BLOCKED.equals(status)) {
            return "RESOURCE_BLOCKED";
        }
        if (GovernanceStatuses.DEPRECATED.equals(status)) {
            return "RESOURCE_DEPRECATED";
        }
        return "RESOURCE_GOVERNANCE_UNKNOWN";
    }

    private boolean isBlocked(String status) {
        return status == null || GovernanceStatuses.BLOCKED.equals(status)
                || GovernanceStatuses.DEPRECATED.equals(status);
    }

    private String protectionKey(String tableName, Long columnMetaId) {
        return tableName + "#" + columnMetaId;
    }

    private LocalDateTime earliest(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    private record DepartmentPath(boolean valid, Long currentId, Set<Long> ids) {
    }

    private record Computation(boolean allowed, String reasonCode, LocalDateTime nextEffectiveAt,
                               List<IamS1TablePermissionVO> tables) {
    }

    private record TableComputation(boolean allowed, String reasonCode, LocalDateTime nextEffectiveAt,
                                    IamS1TablePermissionVO permission) {
    }
}
