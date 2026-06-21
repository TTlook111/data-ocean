package com.dataocean.module.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.datasource.entity.vo.DatasourcePermissionDecisionVO;
import com.dataocean.module.datasource.service.DatasourceAccessService;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.permission.entity.DatasourceAccessPolicy;
import com.dataocean.module.permission.entity.vo.PermissionContextVO;
import com.dataocean.module.permission.event.PermissionChangedEvent;
import com.dataocean.module.permission.mapper.DatasourceAccessPolicyMapper;
import com.dataocean.module.permission.service.PermissionCalculator;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.mapper.RoleMapper;
import com.dataocean.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

    private static final String TABLE_WILDCARD = "*";
    private static final String TABLE_SCOPE_UNRESTRICTED = "UNRESTRICTED";
    private static final String TABLE_SCOPE_ALLOWLIST = "ALLOWLIST";

    private final DatasourceAccessService datasourceAccessService;
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
        if (datasourceId == null) {
            cache.clear();
            log.debug("Permission cache fully invalidated subjectId={}", subjectId);
            return;
        }
        // 按 datasourceId 维度清除所有相关缓存（因为角色/部门变更会影响多个用户）
        String suffix = ":" + datasourceId;
        cache.entrySet().removeIf(e -> e.getKey().endsWith(suffix));
        log.debug("权限缓存已失效 datasourceId={}", datasourceId);
    }

    /**
     * 监听权限变更事件，事务提交后才清除缓存（避免脏读）
     * <p>
     * 使用 AFTER_COMMIT 阶段确保：事务提交成功 → 缓存清除。
     * 如果事务回滚，缓存不会被清除，避免不必要的性能损失。
     * </p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPermissionChanged(PermissionChangedEvent event) {
        invalidate(event.getSubjectId(), event.getDatasourceId());
        log.debug("事务提交后权限缓存已清除 datasourceId={}", event.getDatasourceId());
    }

    /**
     * 实际计算权限上下文
     * <p>
     * 批量加载该数据源的所有策略，在内存中按主体过滤，
     * 避免 N+1 查询（原先每个主体单独查库）。
     * </p>
     */
    private PermissionContextVO doCalculate(Long userId, Long datasourceId) {
        // 收集用户所有维度的主体标识（用户自身 + 角色 + 部门）
        List<SubjectKey> subjects = collectSubjects(userId);

        // 批量加载该数据源的所有策略（单次查询，消除 N+1）
        List<DatasourceAccessPolicy> allPolicies = policyMapper.selectAllByDatasourceId(datasourceId);

        // 在内存中按主体过滤，只保留当前用户相关的策略
        Set<SubjectKey> subjectSet = new HashSet<>(subjects);
        List<DatasourceAccessPolicy> userPolicies = allPolicies.stream()
                .filter(p -> subjectSet.contains(new SubjectKey(p.getSubjectType(), p.getSubjectId())))
                .filter(this::isPolicyActiveByTime)
                .sorted(Comparator.comparingInt(p -> p.getPriority() != null ? p.getPriority() : 200))
                .collect(Collectors.toList());

        // 合并计算权限策略（安全优先：任一维度 DENY 即禁止，任一维度 MASK 即脱敏）
        PermissionContextVO context;
        if (userPolicies.isEmpty()) {
            context = buildEmptyContext();
        } else {
            context = mergePermissions(userPolicies, subjects, datasourceId);
        }

        // 合并计算 canViewSql / canExport（任一维度允许即允许）
        mergeAccessFlags(context, userId, datasourceId);

        // governance_status 联动：BLOCKED/DEPRECATED 表列自动加入禁止列表
        appendGovernanceBlocked(context, datasourceId);

        return context;
    }

    /**
     * 合并所有维度的 canViewSql / canExport 标志（最宽松原则：任一维度允许即允许）
     */
    private void mergeAccessFlags(PermissionContextVO context, Long userId, Long datasourceId) {
        DatasourcePermissionDecisionVO decision = datasourceAccessService.calculateDecision(userId, datasourceId);
        context.setCanViewSql(decision.isCanViewSql());
        context.setCanExport(decision.isCanExport());
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

        // DENY * 表示禁止该数据源下所有表，直接进入空白名单模式。
        if (allExcludedTables.contains(TABLE_WILDCARD)) {
            context.setAllowedTables(List.of());
            context.setTableScopeMode(TABLE_SCOPE_ALLOWLIST);
            return;
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
                context.setTableScopeMode(TABLE_SCOPE_ALLOWLIST);
            } else {
                // 已有白名单 → 从中移除排除表
                List<String> filtered = context.getAllowedTables().stream()
                        .filter(t -> !allExcludedTables.contains(t.toLowerCase()))
                        .collect(Collectors.toList());
                context.setAllowedTables(filtered);
                context.setTableScopeMode(TABLE_SCOPE_ALLOWLIST);
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
     * 判断策略是否在当前时间条件内生效。
     * <p>
     * 检查三个维度：
     * <ol>
     *   <li>valid_from / valid_until：绝对时间范围，当前时间必须在范围内</li>
     *   <li>time_schedule：周期性时间计划（JSON格式），包含工作日和工作时间限制</li>
     * </ol>
     * </p>
     * <p>
     * JSON 格式示例：
     * <pre>
     * {
     *   "weekdays": [1, 2, 3, 4, 5],  // 1=周一, 7=周日
     *   "hours": {"from": "09:00", "to": "18:00"}
     * }
     * </pre>
     * </p>
     *
     * @param policy 访问策略
     * @return 如果策略在当前时间条件内生效返回 true，否则返回 false
     */
    private boolean isPolicyActiveByTime(DatasourceAccessPolicy policy) {
        LocalDateTime now = LocalDateTime.now();

        // 检查绝对时间范围
        if (policy.getValidFrom() != null && now.isBefore(policy.getValidFrom())) {
            return false;
        }
        if (policy.getValidUntil() != null && now.isAfter(policy.getValidUntil())) {
            return false;
        }

        // 检查周期性时间计划
        // JSON 格式：{"weekdays":[1,2,3,4,5],"hours":{"from":"09:00","to":"18:00"}}
        // weekdays: 允许的工作日数组（1=周一, 7=周日）
        // hours: 允许的工作时间范围（from: 开始时间, to: 结束时间）
        if (policy.getTimeSchedule() != null && !policy.getTimeSchedule().isBlank()) {
            try {
                String schedule = policy.getTimeSchedule();
                // 检查工作日限制
                if (schedule.contains("\"weekdays\"")) {
                    int dayOfWeek = now.getDayOfWeek().getValue(); // 1=Monday, 7=Sunday
                    // 提取 weekdays 数组内容：从 "weekdays" 后的 [ 开始，到 ] 结束
                    String weekdaysStr = schedule.substring(schedule.indexOf("\"weekdays\"") + 11);
                    weekdaysStr = weekdaysStr.substring(0, weekdaysStr.indexOf("]"));
                    // 检查当前星期几是否在允许的 weekdays 数组中
                    boolean dayAllowed = weekdaysStr.contains(String.valueOf(dayOfWeek));
                    if (!dayAllowed) return false;
                }
                // 检查工作时间限制
                if (schedule.contains("\"hours\"")) {
                    LocalTime currentTime = now.toLocalTime();
                    // 提取 from 和 to 时间值
                    String fromStr = extractJsonValue(schedule, "from");
                    String toStr = extractJsonValue(schedule, "to");
                    if (fromStr != null && toStr != null) {
                        LocalTime from = LocalTime.parse(fromStr);  // 解析开始时间（如 "09:00"）
                        LocalTime to = LocalTime.parse(toStr);      // 解析结束时间（如 "18:00"）
                        // 检查当前时间是否在允许范围内
                        if (currentTime.isBefore(from) || currentTime.isAfter(to)) {
                            return false;
                        }
                    }
                }
            } catch (Exception e) {
                // 时间计划解析失败时默认放行，避免阻断正常访问
                log.warn("策略时间计划解析失败 policyId={}, 默认放行", policy.getId(), e);
            }
        }

        return true;
    }

    /**
     * 从简单 JSON 字符串中提取指定 key 的值。
     * <p>
     * 使用简单的字符串查找方式解析 JSON，适用于固定格式的简单 JSON。
     * 例如：从 {"from":"09:00","to":"18:00"} 中提取 "from" 的值 "09:00"。
     * </p>
     *
     * @param json JSON 字符串
     * @param key  要提取的 key
     * @return 提取的值，如果未找到返回 null
     */
    private String extractJsonValue(String json, String key) {
        // 构建搜索模式："key":""
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();  // 移动到值的开始位置
        int end = json.indexOf("\"", start);  // 查找值的结束引号
        if (end < 0) return null;
        return json.substring(start, end);  // 提取值
    }

    /**
     * 合并多维度权限策略（安全优先：并集语义）。
     * <p>
     * 安全原则：
     * <ul>
     *   <li>denied columns / denied tables：任一维度 DENY 即禁止（并集）</li>
     *   <li>mask columns：任一维度 MASK 即脱敏（并集）</li>
     *   <li>allowed tables：任一维度 ALLOW 即允许（并集），但减去 denied</li>
     *   <li>row filters：多条件 AND 合并（限制数据范围）</li>
     * </ul>
     * </p>
     * <p>
     * 合并流程：
     * <ol>
     *   <li>遍历所有策略，按 accessType 收集到统一集合（并集语义）</li>
     *   <li>检测 ALLOW 和 DENY 冲突，记录审计日志</li>
     *   <li>计算最终 denied columns（并集：所有维度的 DENY 列汇总）</li>
     *   <li>计算最终 mask columns（并集：所有维度的 MASK 列汇总，首个策略生效）</li>
     *   <li>计算最终 allowed tables（并集减去 denied）</li>
     *   <li>合并行级过滤条件（AND 语义）</li>
     * </ol>
     * </p>
     *
     * @param allPolicies  所有有效的访问策略列表（已过滤时间条件）
     * @param subjects     主体列表（用户和角色）
     * @param datasourceId 数据源ID，用于日志记录
     * @return 合并后的权限上下文
     */
    private PermissionContextVO mergePermissions(List<DatasourceAccessPolicy> allPolicies,
                                                  List<SubjectKey> subjects,
                                                  Long datasourceId) {
        // 安全优先合并：DENY/MASK 取并集（任一维度生效即生效）
        Set<String> deniedColumns = new HashSet<>();      // 被拒绝的列集合（表名.列名格式）
        Set<String> deniedTables = new HashSet<>();       // 被拒绝的表集合
        Map<String, String> allMasks = new HashMap<>();   // 需要脱敏的列（key: 表名.列名, value: 脱敏类型）
        Set<String> allowedTables = new HashSet<>();      // 被允许的表集合
        boolean hasAllowPolicy = false;                   // 是否存在 ALLOW 策略
        boolean hasAllowAllPolicy = false;                // 是否存在 ALLOW ALL 策略（无表名限制）
        boolean hasDenyAllPolicy = false;                 // 是否存在 DENY ALL 策略（无表名限制）
        Map<String, Set<String>> rowFiltersByTable = new HashMap<>();  // 行级过滤条件（key: 表名, value: 过滤条件集合）

        // 遍历所有策略，按 accessType 收集到统一集合（并集语义）
        for (DatasourceAccessPolicy policy : allPolicies) {
            PolicyCollectionResult result = collectPolicy(
                    policy, deniedColumns, deniedTables, allMasks, allowedTables, rowFiltersByTable);
            hasAllowAllPolicy = hasAllowAllPolicy || result.allowAll();
            hasDenyAllPolicy = hasDenyAllPolicy || result.denyAll();
            if ("ALLOW".equals(policy.getAccessType()) && policy.getColumnName() == null) {
                hasAllowPolicy = true;
            }
        }

        // 冲突审计：同一表同时存在 ALLOW 和 DENY 时记录日志
        Set<String> conflictTables = new HashSet<>(allowedTables);
        conflictTables.retainAll(deniedTables);  // 取交集
        if (!conflictTables.isEmpty()) {
            log.warn("权限策略冲突 datasourceId={} 冲突表={}（同时存在 ALLOW 和 DENY，DENY 优先）",
                    datasourceId, conflictTables);
        }

        // 计算最终 denied columns（并集：所有维度的 DENY 列汇总）
        List<String> finalDenied = new ArrayList<>(deniedColumns);

        // 计算最终 mask columns（并集：所有维度的 MASK 列汇总，首个策略生效）
        List<PermissionContextVO.MaskColumnItem> finalMask = new ArrayList<>();
        for (Map.Entry<String, String> entry : allMasks.entrySet()) {
            String[] parts = entry.getKey().split("\\.", 2);
            if (parts.length == 2) {
                finalMask.add(new PermissionContextVO.MaskColumnItem(parts[0], parts[1], entry.getValue()));
            }
        }

        // 计算行级过滤（多条件 AND 合并，限制数据范围）
        List<PermissionContextVO.RowFilterItem> finalRowFilters = buildRowFilters(rowFiltersByTable);

        // 构建结果：表级策略（安全优先：DENY 优先于 ALLOW）
        PermissionContextVO context = new PermissionContextVO();

        // 表级策略合并逻辑（按优先级顺序判断）：
        // 1. DENY ALL（hasDenyAllPolicy）：禁止所有表，设置为白名单模式，白名单为空
        if (hasDenyAllPolicy) {
            context.setAllowedTables(List.of());
            context.setTableScopeMode(TABLE_SCOPE_ALLOWLIST);
            context.setDeniedTables(List.of(TABLE_WILDCARD));  // * 表示所有表
        }
        // 2. ALLOW ALL + 无 DENY：不限制表访问
        else if (hasAllowAllPolicy && deniedTables.isEmpty()) {
            context.setAllowedTables(List.of());
            context.setTableScopeMode(TABLE_SCOPE_UNRESTRICTED);
        }
        // 3. ALLOW ALL + 有 DENY 部分表：不限制表访问，但排除被 DENY 的表
        else if (hasAllowAllPolicy) {
            context.setAllowedTables(List.of());
            context.setTableScopeMode(TABLE_SCOPE_UNRESTRICTED);
            context.setDeniedTables(new ArrayList<>(deniedTables));
        }
        // 4. 有 ALLOW 表级策略：只允许这些表，并移除被禁止的表（DENY 优先）
        else if (hasAllowPolicy) {
            // 从允许表集合中移除被拒绝的表（并集语义：DENY 优先）
            allowedTables.removeAll(deniedTables);
            context.setAllowedTables(new ArrayList<>(allowedTables));
            context.setTableScopeMode(TABLE_SCOPE_ALLOWLIST);
        }
        // 5. 无 ALLOW 但有表级 DENY：传递给 appendGovernanceBlocked 构建排除白名单
        else if (!deniedTables.isEmpty()) {
            context.setAllowedTables(List.of());
            context.setTableScopeMode(TABLE_SCOPE_UNRESTRICTED);
            context.setDeniedTables(new ArrayList<>(deniedTables));
        }
        // 6. 无任何表级策略：不限制表访问
        else {
            context.setAllowedTables(List.of());
            context.setTableScopeMode(TABLE_SCOPE_UNRESTRICTED);
        }

        // 设置列级权限（脱敏列、拒绝列）
        context.setDeniedColumns(finalDenied);
        // 设置行级过滤条件
        context.setRowFilters(finalRowFilters);
        // 设置脱敏列
        context.setMaskColumns(finalMask);

        log.debug("权限计算完成 datasourceId={} deniedColumns={} deniedTables={} masks={} filters={}",
                datasourceId, finalDenied.size(), deniedTables.size(), finalMask.size(), finalRowFilters.size());
        return context;
    }

    /**
     * 收集单条策略到统一集合（并集语义：追加而非覆盖）
     */
    private PolicyCollectionResult collectPolicy(DatasourceAccessPolicy policy,
                                                  Set<String> deniedColumns, Set<String> deniedTables,
                                                  Map<String, String> allMasks, Set<String> allowedTables,
                                                  Map<String, Set<String>> rowFiltersByTable) {
        String accessType = policy.getAccessType();
        String tableName = policy.getTableName();
        boolean tableWildcard = TABLE_WILDCARD.equals(tableName);
        boolean allowAll = false;
        boolean denyAll = false;

        if ("DENY".equals(accessType)) {
            // DENY：任一维度禁止即禁止（并集）
            if (policy.getColumnName() != null) {
                deniedColumns.add(tableName + "." + policy.getColumnName());
            } else if (tableWildcard) {
                denyAll = true;
            } else {
                deniedTables.add(tableName);
            }
        } else if ("ALLOW".equals(accessType) && policy.getColumnName() == null) {
            // ALLOW：表级允许
            if (tableWildcard) {
                allowAll = true;
            } else {
                allowedTables.add(tableName);
            }
        } else if ("MASK".equals(accessType) && policy.getColumnName() != null) {
            // MASK：任一维度脱敏即脱敏（并集），首次出现的策略生效
            if (policy.getMaskStrategy() != null && !policy.getMaskStrategy().isBlank()) {
                String key = tableName + "." + policy.getColumnName();
                allMasks.putIfAbsent(key, policy.getMaskStrategy());
            }
        }

        // 行级过滤：多维度 AND 合并
        if (policy.getRowFilterExpression() != null && !policy.getRowFilterExpression().isBlank()) {
            rowFiltersByTable.computeIfAbsent(tableName, k -> new HashSet<>())
                    .add(policy.getRowFilterExpression());
        }
        return new PolicyCollectionResult(allowAll, denyAll);
    }

    /**
     * 构建行级过滤条件列表（多条件 AND 合并）
     */
    private List<PermissionContextVO.RowFilterItem> buildRowFilters(Map<String, Set<String>> rowFiltersByTable) {
        List<PermissionContextVO.RowFilterItem> result = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : rowFiltersByTable.entrySet()) {
            String tableName = entry.getKey();
            Set<String> conditions = entry.getValue();
            if (conditions.size() == 1) {
                result.add(new PermissionContextVO.RowFilterItem(tableName, conditions.iterator().next()));
            } else {
                // 多条件 AND 合并（所有过滤条件同时生效）
                String merged = conditions.stream()
                        .map(c -> "(" + c + ")")
                        .collect(Collectors.joining(" AND "));
                result.add(new PermissionContextVO.RowFilterItem(tableName, merged));
            }
        }
        return result;
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

    private record PolicyCollectionResult(boolean allowAll, boolean denyAll) {}
}
