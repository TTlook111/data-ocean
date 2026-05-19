package com.dataocean.module.datasource.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.datasource.entity.query.DatasourceQuery;
import com.dataocean.module.datasource.entity.dto.DatasourceCreateDTO;
import com.dataocean.module.datasource.entity.dto.DatasourceTestDTO;
import com.dataocean.module.datasource.entity.dto.DatasourceUpdateDTO;
import com.dataocean.module.datasource.entity.vo.DatasourceConnectionTestVO;
import com.dataocean.module.datasource.entity.vo.DatasourceVO;

public interface DatasourceService {

    DatasourceVO createDatasource(DatasourceCreateDTO request);

    DatasourceVO updateDatasource(Long id, DatasourceUpdateDTO request);

    void deleteDatasource(Long id);

    DatasourceVO getDatasourceById(Long id);

    Page<DatasourceVO> listDatasources(DatasourceQuery request);

    DatasourceVO updateStatus(Long id, Integer status);

    DatasourceConnectionTestVO testConnection(DatasourceTestDTO request);

    DatasourceConnectionTestVO testSavedConnection(Long id);
}
