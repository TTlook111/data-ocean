package com.dataocean.module.permission.service;

import com.dataocean.module.permission.entity.vo.PermissionContextVO;

/**
 * 权限计算器服务接口
 * <p>
 * 负责根据用户的所有角色和部门，合并计算出针对特定数据源的完整权限上下文，
 * 输出格式与 Python UserPermissions 模型对齐，用于 SQL AST 层强制执行权限。
 * </p>
 *
 * @author dataocean
 */
public interface PermissionCalculator {

    /**
     * 计算用户对指定数据源的完整权限上下文
     * <p>
     * 安全优先合并逻辑（并集语义）：
     * - deniedColumns: 所有维度禁止的列取并集（任一维度 DENY 即禁止）
     * - deniedTables: 所有维度禁止的表取并集（任一维度 DENY 即禁止）
     * - maskColumns: 所有维度脱敏的列取并集（任一维度 MASK 即脱敏）
     * - allowedTables: 所有维度允许的表取并集，减去 deniedTables
     * - rowFilters: 多个过滤条件用 AND 合并（限制数据范围）
     * </p>
     *
     * @param userId       用户 ID
     * @param datasourceId 数据源 ID
     * @return 权限上下文，传给 Python 服务
     */
    PermissionContextVO calculate(Long userId, Long datasourceId);

    /**
     * 主动失效指定主体和数据源的权限缓存
     * <p>
     * 在授权/撤销/策略变更后调用，确保下次查询读到最新权限。
     * subjectId 可能是用户/角色/部门 ID，实现侧按 datasourceId 维度清除所有相关缓存。
     * </p>
     *
     * @param subjectId    变更涉及的主体 ID
     * @param datasourceId 变更涉及的数据源 ID
     */
    void invalidate(Long subjectId, Long datasourceId);
}
