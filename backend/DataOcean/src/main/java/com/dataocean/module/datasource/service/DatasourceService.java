package com.dataocean.module.datasource.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.datasource.entity.query.DatasourceQueryRequest;
import com.dataocean.module.datasource.entity.req.DatasourceCreateRequest;
import com.dataocean.module.datasource.entity.req.DatasourceTestRequest;
import com.dataocean.module.datasource.entity.req.DatasourceUpdateRequest;
import com.dataocean.module.datasource.entity.vo.DatasourceConnectionTestResult;
import com.dataocean.module.datasource.entity.vo.DatasourceVO;

public interface DatasourceService {

    DatasourceVO createDatasource(DatasourceCreateRequest request, Long creatorId);

    DatasourceVO updateDatasource(Long id, DatasourceUpdateRequest request);

    void deleteDatasource(Long id);

    DatasourceVO getDatasourceById(Long id);

    Page<DatasourceVO> listDatasources(DatasourceQueryRequest request);

    DatasourceVO updateStatus(Long id, Integer status);

    DatasourceConnectionTestResult testConnection(DatasourceTestRequest request);

    DatasourceConnectionTestResult testSavedConnection(Long id);
}
