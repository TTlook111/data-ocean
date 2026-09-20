package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1ColumnOptionFact;
import com.dataocean.module.permission.s1.entity.IamS1FieldProtection;
import com.dataocean.module.permission.s1.entity.IamS1SnapshotOption;
import com.dataocean.module.permission.s1.entity.IamS1TableOptionFact;
import com.dataocean.module.permission.s1.entity.vo.IamS1ColumnOptionVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1TableOptionVO;
import com.dataocean.module.permission.s1.mapper.IamS1CapabilityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DatasourceIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1FieldProtectionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1ResourceOptionMapper;
import com.dataocean.module.permission.s1.service.IamS1DataAuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1UserResourceService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.dataocean.module.permission.s1.support.IamS1UsageDefaults;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IAM-SIMPLE-1 用户侧资源选择实现。
 * <p>
 * 与后台“负责源”语义严格区分，并按 scope 采用完全不同的可见性口径：
 * </p>
 * <ul>
 *   <li><b>QUERY</b>（问数资源声明）：按“用户 + 表 + 字段 + 当前时间”调用统一 Resolver，
 *       <b>只返回实际允许的表和字段</b>。未授权的表名、字段名与注释一律不返回；
 *       页面也不会出现“选了但一定被真实 Resolver 拒绝”的资源。</li>
 *   <li><b>APPLY</b>（访问申请）：只展示已发布快照中可申请的安全名称，
 *       不开放未授权数据、采样数据或他人权限；最终是否放行仍由审批与 Resolver 决定。</li>
 * </ul>
 * <p>
 * 使用位置（usage）按字段保护状态生成：脱敏字段只能直接投影，隐藏字段不出现。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class IamS1UserResourceServiceImpl implements IamS1UserResourceService {

    private static final String SCOPE_QUERY = "QUERY";
    private static final String SCOPE_APPLY = "APPLY";
    private static final Set<String> BLOCKED_GOVERNANCE = Set.of("BLOCKED", "DEPRECATED");
    /** 数据源级禁止：整源都不可查询，不能因为存在历史 ALLOW 而显示成“可用”。 */
    private static final String DATASOURCE_DENY = "DATASOURCE_DENY";
    /** 单次请求允许的统一 Resolver 探测次数上限，避免大快照下的无界调用。 */
    private static final int MAX_RESOLVER_PROBES = 60;
    /** 单次请求最多核对的表数量上限。 */
    private static final int MAX_TABLES_PROBED = 20;

    private final IamS1AdminGuard adminGuard;
    private final IamS1DataAuthorizationResolver dataAuthorizationResolver;
    private final IamS1CapabilityMapper capabilityMapper;
    private final IamS1ResourceOptionMapper resourceOptionMapper;
    private final IamS1FieldProtectionMapper fieldProtectionMapper;
    private final IamS1DatasourceIdentityMapper datasourceIdentityMapper;

    @Override
    public List<IamS1DatasourceRefVO> datasources(Long userId, String scope) {
        adminGuard.requireGlobalFunction(userId, "query:use");
        if (SCOPE_APPLY.equals(normalizeScope(scope))) {
            return applyDatasources();
        }
        return queryDatasources(userId);
    }

    @Override
    public List<IamS1SnapshotOption> publishedSnapshots(Long userId, String scope, Long datasourceId) {
        requireVisibleDatasource(userId, scope, datasourceId);
        List<IamS1SnapshotOption> snapshots = resourceOptionMapper.selectPublishedSnapshots(datasourceId);
        if (snapshots == null) {
            throw new BusinessException("已发布元数据快照读取失败");
        }
        return snapshots;
    }

    @Override
    public List<IamS1TableOptionVO> tables(Long userId, String scope, Long datasourceId, Long snapshotId) {
        String normalized = requireVisibleDatasource(userId, scope, datasourceId);
        requirePublishedSnapshot(datasourceId, snapshotId);
        List<IamS1TableOptionFact> facts = resourceOptionMapper.selectTables(datasourceId, snapshotId);
        if (SCOPE_APPLY.equals(normalized)) {
            return applyTables(datasourceId, snapshotId, facts);
        }
        return queryTables(userId, datasourceId, snapshotId, facts);
    }

    @Override
    public List<IamS1ColumnOptionVO> columns(Long userId, String scope, Long datasourceId, Long snapshotId,
                                             String tableName) {
        String normalized = requireVisibleDatasource(userId, scope, datasourceId);
        requirePublishedSnapshot(datasourceId, snapshotId);
        if (tableName == null || tableName.isBlank()) {
            throw new BusinessException("请先选择表");
        }
        Map<Long, String> levels = activeProtectionLevels(datasourceId, snapshotId);
        Map<String, String> levelsByName = protectionLevelsByName(datasourceId, snapshotId);
        List<IamS1ColumnOptionFact> facts = resourceOptionMapper.selectColumns(datasourceId, snapshotId,
                tableName.trim());
        if (SCOPE_APPLY.equals(normalized)) {
            return applyColumns(datasourceId, snapshotId, tableName, facts, levels);
        }
        Set<String> allowed = queryAllowedColumns(userId, datasourceId, snapshotId, tableName.trim(),
                namesOf(facts), levelsByName, new int[]{MAX_RESOLVER_PROBES});
        List<IamS1ColumnOptionVO> options = new ArrayList<>();
        for (IamS1ColumnOptionFact column : facts) {
            if (!allowed.contains(column.getColumnName())) {
                // 未授权的字段不返回，避免暴露字段名与注释。
                continue;
            }
            options.add(toColumn(datasourceId, snapshotId, tableName, column, levels));
        }
        return options;
    }

    // ------------------------------------------------------------------ QUERY

    private List<IamS1DatasourceRefVO> queryDatasources(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<IamS1DatasourceRefVO> result = new ArrayList<>();
        for (Map<String, Object> row : capabilityMapper.selectAllEnabledDatasources()) {
            Long id = asLong(row.get("id"));
            if (id == null || !dataAuthorizationResolver.hasEffectiveAllowGrant(userId, id, now)) {
                continue;
            }
            // hasEffectiveAllowGrant 只看 ALLOW；这里再探一次统一 Resolver，
            // 把“整源禁止”的数据源剔除，避免显示出并不能查询的假可用源。
            if (isDeniedByResolver(userId, id, now)) {
                continue;
            }
            result.add(new IamS1DatasourceRefVO(id, asString(row.get("name")), true));
        }
        return result;
    }

    /** 用统一 Resolver 探测数据源是否被整源禁止（不自建第二套判定）。 */
    private boolean isDeniedByResolver(Long userId, Long datasourceId, LocalDateTime now) {
        List<IamS1SnapshotOption> snapshots = resourceOptionMapper.selectPublishedSnapshots(datasourceId);
        if (snapshots == null || snapshots.isEmpty()) {
            return false;
        }
        List<IamS1TableOptionFact> tables = resourceOptionMapper.selectTables(datasourceId,
                snapshots.get(0).getId());
        if (tables == null || tables.isEmpty()) {
            return false;
        }
        IamS1TableOptionFact first = tables.get(0);
        List<IamS1ColumnOptionFact> columns = resourceOptionMapper.selectColumns(datasourceId,
                snapshots.get(0).getId(), first.getTableName());
        if (columns == null || columns.isEmpty()) {
            return false;
        }
        var snapshot = resolve(userId, datasourceId, snapshots.get(0).getId(), first.getTableName(),
                namesOf(columns), protectionLevelsByName(datasourceId, snapshots.get(0).getId()), now);
        return !snapshot.isAllowed() && DATASOURCE_DENY.equals(snapshot.getReasonCode());
    }

    private List<IamS1TableOptionVO> queryTables(Long userId, Long datasourceId, Long snapshotId,
                                                 List<IamS1TableOptionFact> facts) {
        Map<Long, String> levels = activeProtectionLevels(datasourceId, snapshotId);
        Map<String, String> levelsByName = protectionLevelsByName(datasourceId, snapshotId);
        int[] budget = {MAX_RESOLVER_PROBES};
        List<IamS1TableOptionVO> options = new ArrayList<>();
        int probed = 0;
        for (IamS1TableOptionFact table : facts) {
            if (probed >= MAX_TABLES_PROBED || budget[0] <= 0) {
                break;
            }
            probed++;
            List<IamS1ColumnOptionFact> columns = resourceOptionMapper.selectColumns(datasourceId, snapshotId,
                    table.getTableName());
            Set<String> allowed = queryAllowedColumns(userId, datasourceId, snapshotId, table.getTableName(),
                    namesOf(columns), levelsByName, budget);
            if (allowed.isEmpty()) {
                // 该表在当前时间没有任何可查询字段，QUERY 模式不返回。
                continue;
            }
            options.add(new IamS1TableOptionVO(datasourceId, snapshotId, table.getTableName(),
                    table.getTableComment(), table.getGovernanceStatus(), true, allowed.size()));
        }
        return options;
    }

    /**
     * 按“用户 + 表 + 字段 + 当前时间”核对可查询字段。
     * <p>
     * 先整表探测；整表不通过时按字段逐个探测（受预算限制），以保留“部分字段授权”的能力。
     * 全部判定都来自统一 Resolver，不在本类实现第二套匹配算法。
     * </p>
     */
    private Set<String> queryAllowedColumns(Long userId, Long datasourceId, Long snapshotId, String tableName,
                                            Set<String> columnNames, Map<String, String> levelsByName,
                                            int[] budget) {
        Set<String> allowed = new LinkedHashSet<>();
        if (columnNames.isEmpty() || budget[0] <= 0) {
            return allowed;
        }
        LocalDateTime now = LocalDateTime.now();
        budget[0]--;
        var whole = resolve(userId, datasourceId, snapshotId, tableName, columnNames, levelsByName, now);
        if (whole.isAllowed()) {
            allowed.addAll(columnNames);
            return allowed;
        }
        for (String column : columnNames) {
            if (budget[0] <= 0) {
                break;
            }
            budget[0]--;
            var single = resolve(userId, datasourceId, snapshotId, tableName, Set.of(column), levelsByName, now);
            if (single.isAllowed()) {
                allowed.add(column);
            }
        }
        return allowed;
    }

    private com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot resolve(
            Long userId, Long datasourceId, Long snapshotId, String tableName, Set<String> columns,
            Map<String, String> levelsByName, LocalDateTime now) {
        var request = new com.dataocean.module.permission.s1.entity.dto.IamS1DataAuthorizationRequestDTO();
        request.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
        request.setUserId(userId);
        request.setDatasourceId(datasourceId);
        request.setActiveMetadataSnapshotId(snapshotId);
        request.setCalculatedAt(now);
        var table = new com.dataocean.module.permission.s1.entity.dto.IamS1TableRequestDTO(tableName, columns);
        Map<String, Set<com.dataocean.module.permission.s1.enums.IamS1ColumnUsage>> usages = new LinkedHashMap<>();
        for (String column : columns) {
            // 脱敏字段只能直接投影，否则统一 Resolver 会以 MASKED_FIELD_USAGE_FORBIDDEN 拒绝。
            usages.put(column, IamS1UsageDefaults.forProtectionLevel(
                    levelsByName.getOrDefault(column, IamS1Constants.PROTECTION_NORMAL)));
        }
        table.setColumnUsages(usages);
        request.setTables(List.of(table));
        var result = dataAuthorizationResolver.resolve(request);
        if (result == null) {
            // fail-closed：拿不到判定结果时按“无权限”处理，绝不默认放行。
            throw new BusinessException("无法确认当前权限事实，已按无权限处理");
        }
        return result;
    }

    /** 按字段名取保护等级（更严格的优先），用于生成 usage。 */
    private Map<String, String> protectionLevelsByName(Long datasourceId, Long snapshotId) {
        Map<String, String> levels = new LinkedHashMap<>();
        List<IamS1FieldProtection> protections = fieldProtectionMapper.selectActiveBySnapshot(
                IamS1Constants.PROTOCOL_VERSION, datasourceId, snapshotId);
        if (protections == null) {
            return levels;
        }
        for (IamS1FieldProtection protection : protections) {
            if (protection.getColumnName() != null) {
                levels.merge(protection.getColumnName(), protection.getProtectionLevel(),
                        IamS1Constants::stricterProtection);
            }
        }
        return levels;
    }

    // ------------------------------------------------------------------ APPLY

    private List<IamS1DatasourceRefVO> applyDatasources() {
        List<IamS1DatasourceRefVO> result = new ArrayList<>();
        for (Map<String, Object> row : resourceOptionMapper.selectEnabledDatasourcesWithPublishedSnapshot()) {
            result.add(new IamS1DatasourceRefVO(asLong(row.get("id")), asString(row.get("name")), true));
        }
        return result;
    }

    private List<IamS1TableOptionVO> applyTables(Long datasourceId, Long snapshotId,
                                                 List<IamS1TableOptionFact> facts) {
        List<IamS1TableOptionVO> options = new ArrayList<>();
        for (IamS1TableOptionFact table : facts) {
            String governance = table.getGovernanceStatus();
            boolean selectable = governance != null && !BLOCKED_GOVERNANCE.contains(governance);
            int columnCount = resourceOptionMapper.selectColumns(datasourceId, snapshotId, table.getTableName()).size();
            options.add(new IamS1TableOptionVO(datasourceId, snapshotId, table.getTableName(),
                    table.getTableComment(), governance, selectable, columnCount));
        }
        return options;
    }

    private List<IamS1ColumnOptionVO> applyColumns(Long datasourceId, Long snapshotId, String tableName,
                                                   List<IamS1ColumnOptionFact> facts, Map<Long, String> levels) {
        List<IamS1ColumnOptionVO> options = new ArrayList<>();
        for (IamS1ColumnOptionFact column : facts) {
            options.add(toColumn(datasourceId, snapshotId, tableName, column, levels));
        }
        return options;
    }

    // ------------------------------------------------------------------ 共用

    private IamS1ColumnOptionVO toColumn(Long datasourceId, Long snapshotId, String tableName,
                                         IamS1ColumnOptionFact column, Map<Long, String> levels) {
        String governance = column.getGovernanceStatus();
        String protectionLevel = levels.getOrDefault(column.getId(), IamS1Constants.PROTECTION_NORMAL);
        boolean selectable = governance != null && !BLOCKED_GOVERNANCE.contains(governance)
                && !IamS1Constants.PROTECTION_HIDDEN.equals(protectionLevel);
        return new IamS1ColumnOptionVO(column.getId(), column.getColumnName(), column.getColumnComment(),
                column.getDataType(), governance, protectionLevel, selectable);
    }

    /** 校验数据源在本次 scope 下可见，并返回规范化后的 scope。 */
    private String requireVisibleDatasource(Long userId, String scope, Long datasourceId) {
        adminGuard.requireGlobalFunction(userId, "query:use");
        if (datasourceId == null) {
            throw new BusinessException("请先选择数据源");
        }
        String normalized = normalizeScope(scope);
        if (datasourceIdentityMapper.countEnabledDatasource(datasourceId) != 1) {
            throw new BusinessException("目标数据源不存在或未启用");
        }
        if (SCOPE_APPLY.equals(normalized)) {
            List<IamS1SnapshotOption> snapshots = resourceOptionMapper.selectPublishedSnapshots(datasourceId);
            if (snapshots == null || snapshots.isEmpty()) {
                throw new BusinessException("该数据源没有已发布元数据快照，暂时无法申请");
            }
            return normalized;
        }
        if (!dataAuthorizationResolver.hasEffectiveAllowGrant(userId, datasourceId, LocalDateTime.now())) {
            throw new BusinessException("当前数据源没有可用的 IAM-SIMPLE-1 数据授权，请先在访问申请中提交申请");
        }
        return normalized;
    }

    private void requirePublishedSnapshot(Long datasourceId, Long snapshotId) {
        if (snapshotId == null) {
            throw new BusinessException("请先选择已发布元数据快照");
        }
        if (resourceOptionMapper.countPublishedSnapshot(datasourceId, snapshotId) != 1) {
            throw new BusinessException("元数据快照不存在或未发布");
        }
    }

    private Map<Long, String> activeProtectionLevels(Long datasourceId, Long snapshotId) {
        Map<Long, String> levels = new LinkedHashMap<>();
        List<IamS1FieldProtection> protections = fieldProtectionMapper.selectActiveBySnapshot(
                IamS1Constants.PROTOCOL_VERSION, datasourceId, snapshotId);
        if (protections == null) {
            return levels;
        }
        for (IamS1FieldProtection protection : protections) {
            if (protection.getColumnMetaId() != null) {
                levels.merge(protection.getColumnMetaId(), protection.getProtectionLevel(),
                        IamS1Constants::stricterProtection);
            }
        }
        return levels;
    }

    private Set<String> namesOf(List<IamS1ColumnOptionFact> columns) {
        Set<String> names = new LinkedHashSet<>();
        if (columns == null) {
            return names;
        }
        for (IamS1ColumnOptionFact column : columns) {
            if (column.getColumnName() != null && !column.getColumnName().isBlank()) {
                names.add(column.getColumnName());
            }
        }
        return names;
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return SCOPE_QUERY;
        }
        String normalized = scope.trim().toUpperCase();
        if (!SCOPE_QUERY.equals(normalized) && !SCOPE_APPLY.equals(normalized)) {
            throw new BusinessException("资源范围只支持 QUERY 或 APPLY");
        }
        return normalized;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
