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
     * 将 governance_status 为 BLOCKED 的表/列加入禁止列表
     */
    private void appendGovernanceBlocked(PermissionContextVO context, Long datasourceId) {
        // 查询 BLOCKED 状态的列
        List<DbColumnMeta> blockedColumns = columnMetaMapper.selectList(
                new LambdaQueryWrapper<DbColumnMeta>()
                        .eq(DbColumnMeta::getDatasourceId, datasourceId)
                        .in(DbColumnMeta::getGovernanceStatus, List.of("BLOCKED", "DEPRECATED")));

        if (blockedColumns.isEmpty()) return;

        List<String> denied = new ArrayList<>(context.getDeniedColumns());
        for (DbColumnMeta col : blockedColumns) {
            String ref = col.getTableName() + "." + col.getColumnName();
            if (!denied.contains(ref)) {
                denied.add(ref);
            }
        }
        context.setDeniedColumns(denied);
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
        // 收集各维度的 mask columns（如果任一维度允许明文，则不脱敏）
        Map<String, Set<String>> maskColumnsBySubject = new HashMap<>();
        // 收集行级过滤（多维度用 OR 合并）
        Map<String, Set<String>> rowFiltersByTable = new HashMap<>();

        for (SubjectKey subject : subjects) {
            List<DatasourceAccessPolicy> subjectPolicies = policiesBySubject.getOrDefault(subject, List.of());
            if (subjectPolicies.isEmpty()) continue;

            Set<String> subjectDenied = new HashSet<>();
            for (DatasourceAccessPolicy policy : subjectPolicies) {
                String accessType = policy.getAccessType();

                if ("DENY".equals(accessType) && policy.getColumnName() != null) {
                    // 列级禁止
                    subjectDenied.add(policy.getTableName() + "." + policy.getColumnName());
                } else if ("MASK".equals(accessType) && policy.getColumnName() != null) {
                    // 列级脱敏
                    String key = policy.getTableName() + "." + policy.getColumnName();
                    maskColumnsBySubject.computeIfAbsent(key, k -> new HashSet<>())
                            .add(policy.getMaskStrategy());
                }

                // 行级过滤
                if (policy.getRowFilterExpression() != null && !policy.getRowFilterExpression().isBlank()) {
                    rowFiltersByTable.computeIfAbsent(policy.getTableName(), k -> new HashSet<>())
                            .add(policy.getRowFilterExpression());
                }
            }

            if (!subjectDenied.isEmpty()) {
                deniedColumnSets.add(subjectDenied);
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

        // 计算最终 mask columns（所有维度都要求脱敏才脱敏）
        List<PermissionContextVO.MaskColumnItem> finalMask = new ArrayList<>();
        int totalSubjectsWithPolicies = (int) subjects.stream()
                .filter(s -> policiesBySubject.containsKey(s))
                .count();
        for (Map.Entry<String, Set<String>> entry : maskColumnsBySubject.entrySet()) {
            String[] parts = entry.getKey().split("\\.", 2);
            if (parts.length == 2) {
                // 取第一个脱敏策略
                String strategy = entry.getValue().iterator().next();
                finalMask.add(new PermissionContextVO.MaskColumnItem(parts[0], parts[1], strategy));
            }
        }

        // 计算行级过滤（多条件 OR 合并 → 用户看到更多数据）
        List<PermissionContextVO.RowFilterItem> finalRowFilters = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : rowFiltersByTable.entrySet()) {
            String tableName = entry.getKey();
            Set<String> conditions = entry.getValue();
            if (conditions.size() == 1) {
                finalRowFilters.add(new PermissionContextVO.RowFilterItem(tableName, conditions.iterator().next()));
            } else {
                // 多条件 OR 合并
                String merged = conditions.stream()
                        .map(c -> "(" + c + ")")
                        .collect(Collectors.joining(" OR "));
                finalRowFilters.add(new PermissionContextVO.RowFilterItem(tableName, merged));
            }
        }

        // 构建结果
        PermissionContextVO context = new PermissionContextVO();
        context.setAllowedTables(List.of());
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
