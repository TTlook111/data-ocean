package com.dataocean.module.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.datasource.entity.DatasourceAccess;
import com.dataocean.module.datasource.mapper.DatasourceAccessMapper;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.permission.entity.DatasourceAccessPolicy;
import com.dataocean.module.permission.entity.vo.PermissionContextVO;
import com.dataocean.module.permission.mapper.DatasourceAccessPolicyMapper;
import com.dataocean.module.permission.service.PermissionCalculator;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.mapper.RoleMapper;
import com.dataocean.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 权限计算器实现
 * <p>
 * 合并用户直接权限、角色权限、部门权限，生成传给 Python 的 PermissionContext。
 * </p>
 *
 * @author dataocean
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionCalculatorImpl implements PermissionCalculator {

    private final DatasourceAccessMapper accessMapper;
    private final DatasourceAccessPolicyMapper policyMapper;
    private final RoleMapper roleMapper;
    private final UserMapper userMapper;
    private final DbTableMetaMapper tableMetaMapper;
    private final DbColumnMetaMapper columnMetaMapper;
    private final com.dataocean.module.metadata.service.SchemaSnapshotService schemaSnapshotService;

    /** 简单本地缓存：key → (结果, 过期时间戳) */
    private final java.util.concurrent.ConcurrentHashMap<String, CacheEntry> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5000;

    private record CacheEntry(PermissionContextVO context, long expiresAt) {}

    @Override
    public PermissionContextVO calculate(Long userId, Long datasourceId) {
        String cacheKey = userId + ":" + datasourceId;
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && System.currentTimeMillis() < entry.expiresAt) {
            return entry.context;
        }

        PermissionContextVO result = doCalculate(userId, datasourceId);
        cache.put(cacheKey, new CacheEntry(result, System.currentTimeMillis() + CACHE_TTL_MS));

        // 惰性清理过期条目（避免内存泄漏）
        if (cache.size() > 500) {
            long now = System.currentTimeMillis();
            cache.entrySet().removeIf(e -> now >= e.getValue().expiresAt);
        }

        return result;
    }

    @Override
    public void invalidate(Long subjectId, Long datasourceId) {
        // 按 datasourceId 维度清除所有相关缓存（因为角色/部门变更会影响多个用户）
        String suffix = ":" + datasourceId;
        cache.entrySet().removeIf(e -> e.getKey().endsWith(suffix));
        log.debug("权限缓存已失效 datasourceId={}", datasourceId);
    }

    /**
     * 实际计算权限上下文
     */
    private PermissionContextVO doCalculate(Long userId, Long datasourceId) {
        // 收集用户所有维度的主体标识
        List<SubjectKey> subjects = collectSubjects(userId);

        // 收集所有维度的策略
        List<DatasourceAccessPolicy> allPolicies = new ArrayList<>();
        for (SubjectKey subject : subjects) {
            allPolicies.addAll(policyMapper.selectBySubject(datasourceId, subject.type, subject.id));
        }

        // 合并计算权限策略
        PermissionContextVO context;
        if (allPolicies.isEmpty()) {
            context = buildEmptyContext();
        } else {
            context = mergePermissions(allPolicies, subjects, datasourceId);
        }

        // 合并计算 canViewSql / canExport（任一维度允许即允许）
        mergeAccessFlags(context, subjects, datasourceId);

        // governance_status 联动：BLOCKED 表的所有列自动加入 deniedColumns
        appendGovernanceBlocked(context, datasourceId);

        return context;
    }

    /**
     * 合并所有维度的 canViewSql / canExport 标志（最宽松原则：任一维度允许即允许）
     */
    private void mergeAccessFlags(PermissionContextVO context, List<SubjectKey> subjects, Long datasourceId) {
        boolean anyCanViewSql = false;
        boolean anyCanExport = false;
        boolean hasAnyAccess = false;

        for (SubjectKey subject : subjects) {
            List<DatasourceAccess> accessList = accessMapper.selectBySubject(datasourceId, subject.type, subject.id);
            for (DatasourceAccess access : accessList) {
                hasAnyAccess = true;
                if (Boolean.TRUE.equals(access.getCanViewSql())) anyCanViewSql = true;
                if (Boolean.TRUE.equals(access.getCanExport())) anyCanExport = true;
            }
        }

        // 如果没有任何授权记录，默认允许查看 SQL（向后兼容）
        context.setCanViewSql(hasAnyAccess ? anyCanViewSql : true);
        context.setCanExport(hasAnyAccess ? anyCanExport : false);
    }

    /**
     * 将 governance_status 为 BLOCKED/DEPRECATED 的表/列加入禁止列表，
     * 同时处理策略中的表级 DENY（deniedTables）。
     * 只查询当前发布快照的元数据，避免旧快照污染。
     */
    private void appendGovernanceBlocked(PermissionContextVO context, Long datasourceId) {
        // 获取当前发布快照 ID，无快照则跳过 governance 联动
        var publishedSnapshot = schemaSnapshotService.getPublishedSnapshot(datasourceId);
        Long snapshotId = publishedSnapshot != null ? publishedSnapshot.getId() : null;

        // 收集所有需要排除的表：governance BLOCKED + 策略 DENY
        Set<String> allExcludedTables = new HashSet<>();

        // 查询表级 BLOCKED/DEPRECATED（限定快照）
        if (snapshotId != null) {
            List<DbTableMeta> blockedTables = tableMetaMapper.selectList(
                    new LambdaQueryWrapper<DbTableMeta>()
                            .eq(DbTableMeta::getDatasourceId, datasourceId)
                            .eq(DbTableMeta::getSnapshotId, snapshotId)
                            .in(DbTableMeta::getGovernanceStatus, List.of("BLOCKED", "DEPRECATED"))
                            .select(DbTableMeta::getTableName));
            blockedTables.forEach(t -> allExcludedTables.add(t.getTableName().toLowerCase()));
        }

        // 加入策略中的表级 DENY
        if (context.getDeniedTables() != null) {
            context.getDeniedTables().forEach(t -> allExcludedTables.add(t.toLowerCase()));
        }

        // 如果有需要排除的表，通过 allowedTables 白名单机制实现
        if (!allExcludedTables.isEmpty()) {
            if (context.getAllowedTables().isEmpty()) {
                // 当前无白名单限制 → 构建白名单（当前快照所有表 - 排除表）
                LambdaQueryWrapper<DbTableMeta> allTablesQuery = new LambdaQueryWrapper<DbTableMeta>()
                        .eq(DbTableMeta::getDatasourceId, datasourceId)
                        .select(DbTableMeta::getTableName);
                if (snapshotId != null) {
                    allTablesQuery.eq(DbTableMeta::getSnapshotId, snapshotId);
                }
                List<DbTableMeta> allTables = tableMetaMapper.selectList(allTablesQuery);
                List<String> allowed = allTables.stream()
                        .map(t -> t.getTableName().toLowerCase())
                        .filter(name -> !allExcludedTables.contains(name))
                        .distinct()
                        .collect(Collectors.toList());
                context.setAllowedTables(allowed);
            } else {
                // 已有白名单 → 从中移除排除表
                List<String> filtered = context.getAllowedTables().stream()
                        .filter(t -> !allExcludedTables.contains(t.toLowerCase()))
                        .collect(Collectors.toList());
                context.setAllowedTables(filtered);
            }
        }

        // 查询列级 BLOCKED/DEPRECATED（限定快照，加入 deniedColumns）
        if (snapshotId != null) {
            List<DbColumnMeta> blockedColumns = columnMetaMapper.selectList(
                    new LambdaQueryWrapper<DbColumnMeta>()
                            .eq(DbColumnMeta::getDatasourceId, datasourceId)
                            .eq(DbColumnMeta::getSnapshotId, snapshotId)
                            .in(DbColumnMeta::getGovernanceStatus, List.of("BLOCKED", "DEPRECATED")));

            if (!blockedColumns.isEmpty()) {
                List<String> denied = new ArrayList<>(context.getDeniedColumns());
                for (DbColumnMeta col : blockedColumns) {
                    String ref = col.getTableName() + "." + col.getColumnName();
                    if (!denied.contains(ref)) {
                        denied.add(ref);
                    }
                }
                context.setDeniedColumns(denied);
            }
        }
    }

    /**
     * 收集用户的所有主体标识（用户自身 + 角色 + 部门）
     */
    private List<SubjectKey> collectSubjects(Long userId) {
        List<SubjectKey> subjects = new ArrayList<>();

        // 用户自身
        subjects.add(new SubjectKey("USER", userId));

        // 用户的所有角色
        List<SysRole> roles = roleMapper.selectByUserId(userId);
        for (SysRole role : roles) {
            subjects.add(new SubjectKey("ROLE", role.getId()));
        }

        // 用户所属部门
        Long departmentId = userMapper.selectDepartmentIdByUserId(userId);
        if (departmentId != null) {
            subjects.add(new SubjectKey("DEPARTMENT", departmentId));
        }

        return subjects;
    }

    /**
     * 合并多维度权限策略
     */
    private PermissionContextVO mergePermissions(List<DatasourceAccessPolicy> allPolicies,
                                                  List<SubjectKey> subjects,
                                                  Long datasourceId) {
        // 按主体分组策略
        Map<SubjectKey, List<DatasourceAccessPolicy>> policiesBySubject = new HashMap<>();
        for (DatasourceAccessPolicy policy : allPolicies) {
            SubjectKey key = new SubjectKey(policy.getSubjectType(), policy.getSubjectId());
            policiesBySubject.computeIfAbsent(key, k -> new ArrayList<>()).add(policy);
        }

        // 收集各维度的 denied columns（取交集：所有维度都禁止才真正禁止）
        List<Set<String>> deniedColumnSets = new ArrayList<>();
        // 收集各维度的 denied tables（表级 DENY，取交集）
        List<Set<String>> deniedTableSets = new ArrayList<>();
        // 收集各维度的 allowed tables（表级 ALLOW）
        Set<String> allAllowedTables = new HashSet<>();
        boolean hasAllowPolicy = false;
        // 收集各维度的 mask columns（取交集：所有有策略的维度都 MASK 才脱敏）
        List<Map<String, String>> maskColumnSets = new ArrayList<>();
        // 收集行级过滤（多维度 AND 合并）
        Map<String, Set<String>> rowFiltersByTable = new HashMap<>();

        for (SubjectKey subject : subjects) {
            List<DatasourceAccessPolicy> subjectPolicies = policiesBySubject.getOrDefault(subject, List.of());
            if (subjectPolicies.isEmpty()) continue;

            Set<String> subjectDenied = new HashSet<>();
            Set<String> subjectDeniedTables = new HashSet<>();
            Map<String, String> subjectMask = new HashMap<>();
            for (DatasourceAccessPolicy policy : subjectPolicies) {
                String accessType = policy.getAccessType();

                if ("DENY".equals(accessType)) {
                    if (policy.getColumnName() != null) {
                        subjectDenied.add(policy.getTableName() + "." + policy.getColumnName());
                    } else {
                        subjectDeniedTables.add(policy.getTableName());
                    }
                } else if ("ALLOW".equals(accessType) && policy.getColumnName() == null) {
                    allAllowedTables.add(policy.getTableName());
                    hasAllowPolicy = true;
                } else if ("MASK".equals(accessType) && policy.getColumnName() != null) {
                    if (policy.getMaskStrategy() != null && !policy.getMaskStrategy().isBlank()) {
                        String key = policy.getTableName() + "." + policy.getColumnName();
                        subjectMask.put(key, policy.getMaskStrategy());
                    }
                }

                if (policy.getRowFilterExpression() != null && !policy.getRowFilterExpression().isBlank()) {
                    rowFiltersByTable.computeIfAbsent(policy.getTableName(), k -> new HashSet<>())
                            .add(policy.getRowFilterExpression());
                }
            }

            if (!subjectDenied.isEmpty()) {
                deniedColumnSets.add(subjectDenied);
            }
            if (!subjectDeniedTables.isEmpty()) {
                deniedTableSets.add(subjectDeniedTables);
            }
            if (!subjectMask.isEmpty()) {
                maskColumnSets.add(subjectMask);
            }
        }

        // 计算最终 denied columns（交集：所有维度都禁止才禁止）
        List<String> finalDenied = new ArrayList<>();
        if (!deniedColumnSets.isEmpty()) {
            Set<String> intersection = new HashSet<>(deniedColumnSets.get(0));
            for (int i = 1; i < deniedColumnSets.size(); i++) {
                intersection.retainAll(deniedColumnSets.get(i));
            }
            finalDenied.addAll(intersection);
        }

        // 计算最终 mask columns（交集：所有有策略的维度都 MASK 才脱敏）
        List<PermissionContextVO.MaskColumnItem> finalMask = new ArrayList<>();
        if (!maskColumnSets.isEmpty()) {
            // 取所有维度 mask 列的交集
            Set<String> maskIntersection = new HashSet<>(maskColumnSets.get(0).keySet());
            for (int i = 1; i < maskColumnSets.size(); i++) {
                maskIntersection.retainAll(maskColumnSets.get(i).keySet());
            }
            // 对交集中的列，取第一个维度的策略
            for (String key : maskIntersection) {
                String[] parts = key.split("\\.", 2);
                if (parts.length == 2) {
                    String strategy = maskColumnSets.get(0).get(key);
                    finalMask.add(new PermissionContextVO.MaskColumnItem(parts[0], parts[1], strategy));
                }
            }
        }

        // 计算行级过滤（多条件 AND 合并 → 取交集，限制数据范围）
        List<PermissionContextVO.RowFilterItem> finalRowFilters = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : rowFiltersByTable.entrySet()) {
            String tableName = entry.getKey();
            Set<String> conditions = entry.getValue();
            if (conditions.size() == 1) {
                finalRowFilters.add(new PermissionContextVO.RowFilterItem(tableName, conditions.iterator().next()));
            } else {
                // 多条件 AND 合并（所有过滤条件同时生效）
                String merged = conditions.stream()
                        .map(c -> "(" + c + ")")
                        .collect(Collectors.joining(" AND "));
                finalRowFilters.add(new PermissionContextVO.RowFilterItem(tableName, merged));
            }
        }

        // 构建结果
        PermissionContextVO context = new PermissionContextVO();

        // 计算最终被禁止的表（交集：所有维度都禁止才真正禁止）
        Set<String> finalDeniedTables = new HashSet<>();
        if (!deniedTableSets.isEmpty()) {
            finalDeniedTables.addAll(deniedTableSets.get(0));
            for (int i = 1; i < deniedTableSets.size(); i++) {
                finalDeniedTables.retainAll(deniedTableSets.get(i));
            }
        }

        if (hasAllowPolicy) {
            // 有 ALLOW 表级策略：只允许这些表，并移除被禁止的表
            allAllowedTables.removeAll(finalDeniedTables);
            context.setAllowedTables(new ArrayList<>(allAllowedTables));
        } else if (!finalDeniedTables.isEmpty()) {
            // 无 ALLOW 但有表级 DENY：需要构建白名单排除被禁止的表
            // 标记需要在 appendGovernanceBlocked 阶段构建白名单时排除这些表
            context.setAllowedTables(List.of());
            context.setDeniedTables(new ArrayList<>(finalDeniedTables));
        } else {
            context.setAllowedTables(List.of());
        }

        context.setDeniedColumns(finalDenied);
        context.setRowFilters(finalRowFilters);
        context.setMaskColumns(finalMask);

        log.debug("权限计算完成 datasourceId={} denied={} masks={} filters={}",
                datasourceId, finalDenied.size(), finalMask.size(), finalRowFilters.size());
        return context;
    }

    /**
     * 构建空权限上下文（无限制）
     */
    private PermissionContextVO buildEmptyContext() {
        PermissionContextVO context = new PermissionContextVO();
        context.setAllowedTables(List.of());
        context.setDeniedColumns(List.of());
        context.setRowFilters(List.of());
        context.setMaskColumns(List.of());
        return context;
    }

    /**
     * 主体标识（类型 + ID）
     */
    private record SubjectKey(String type, Long id) {}
}
