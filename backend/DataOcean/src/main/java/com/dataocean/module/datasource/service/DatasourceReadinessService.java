package com.dataocean.module.datasource.service;

import com.dataocean.module.datasource.entity.vo.DatasourceReadinessVO;

/**
 * 数据源可询问状态聚合服务。
 */
public interface DatasourceReadinessService {

    /**
     * 查询管理端视角的数据源可询问状态。
     *
     * @param datasourceId 数据源 ID
     * @return 可询问状态
     */
    DatasourceReadinessVO getAdminReadiness(Long datasourceId);

    /**
     * 查询当前用户视角的数据源可询问状态。
     *
     * @param datasourceId 数据源 ID
     * @return 可询问状态
     */
    DatasourceReadinessVO getCurrentUserReadiness(Long datasourceId);
}
