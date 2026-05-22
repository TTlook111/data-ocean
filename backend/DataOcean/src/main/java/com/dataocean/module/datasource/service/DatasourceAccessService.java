package com.dataocean.module.datasource.service;

import com.dataocean.module.datasource.entity.dto.DatasourceAccessGrantDTO;
import com.dataocean.module.datasource.entity.vo.DatasourceAccessVO;
import com.dataocean.module.datasource.entity.vo.DatasourceSimpleVO;

import java.util.List;

/**
 * 数据源访问授权服务接口
 * <p>
 * 提供数据源访问权限的授予、撤销、查询等管理功能，
 * 以及用户端查询自身可访问数据源列表的能力。
 * </p>
 *
 * @author dataocean
 */
public interface DatasourceAccessService {

    /**
     * 批量授予用户对数据源的访问权限
     *
     * @param datasourceId 数据源 ID
     * @param request      授权请求参数（包含用户 ID 列表和过期时间）
     * @return 实际新增授权的数量
     */
    int grantAccess(Long datasourceId, DatasourceAccessGrantDTO request);

    /**
     * 撤销用户对数据源的访问权限
     *
     * @param datasourceId 数据源 ID
     * @param userId       被撤销权限的用户 ID
     */
    void revokeAccess(Long datasourceId, Long userId);

    /**
     * 查询指定数据源的授权用户列表
     *
     * @param datasourceId 数据源 ID
     * @return 授权用户视图对象列表
     */
    List<DatasourceAccessVO> listAccess(Long datasourceId);

    /**
     * 获取当前登录用户可访问的数据源列表
     *
     * @return 可访问的数据源简要信息列表
     */
    List<DatasourceSimpleVO> listAccessibleDatasources();

    /**
     * 检查当前登录用户是否有权访问指定数据源
     *
     * @param datasourceId 数据源 ID
     * @return true-有权访问，false-无权访问
     */
    boolean checkAccess(Long datasourceId);
}
