package com.dataocean.module.permission.service;

import com.dataocean.module.permission.entity.dto.DatasourcePermissionGrantDTO;
import com.dataocean.module.permission.entity.vo.DatasourcePermissionVO;

import java.util.List;

/**
 * 数据源权限管理服务接口
 * <p>
 * 提供基于主体维度（用户/角色/部门）的数据源访问授权管理，
 * 以及综合权限检查（合并用户直接授权、角色授权、部门授权）。
 * </p>
 *
 * @author dataocean
 */
public interface DatasourcePermissionService {

    /**
     * 授予主体对数据源的访问权限
     *
     * @param dto 授权请求参数
     * @return 授权记录 ID
     */
    Long grant(DatasourcePermissionGrantDTO dto);

    /**
     * 更新授权权限
     *
     * @param id       授权记录 ID
     * @param canQuery 是否允许查询
     * @param canExport 是否允许导出
     * @param canViewSql 是否允许查看SQL
     */
    void update(Long id, Boolean canQuery, Boolean canExport, Boolean canViewSql, String accessEffect);

    /**
     * 撤销授权
     *
     * @param id 授权记录 ID
     */
    void revoke(Long id);

    /**
     * 查询指定数据源的授权列表
     *
     * @param datasourceId 数据源 ID
     * @param subjectType  主体类型（可选过滤）
     * @return 授权视图列表
     */
    List<DatasourcePermissionVO> listByDatasource(Long datasourceId, String subjectType);

    /**
     * 综合检查用户是否有权访问指定数据源
     * <p>
     * 合并检查：用户直接授权 + 用户角色授权 + 用户部门授权
     * </p>
     *
     * @param userId       用户 ID
     * @param datasourceId 数据源 ID
     * @return true-有权访问
     */
    boolean checkUserAccess(Long userId, Long datasourceId);
}
