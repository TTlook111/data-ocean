package com.dataocean.module.datasource.service;

import com.dataocean.module.datasource.entity.dto.DatasourceAccessGrantDTO;
import com.dataocean.module.datasource.entity.vo.DatasourceAccessVO;
import com.dataocean.module.datasource.entity.vo.DatasourceSimpleVO;

import java.util.List;

public interface DatasourceAccessService {

    int grantAccess(Long datasourceId, DatasourceAccessGrantDTO request);

    void revokeAccess(Long datasourceId, Long userId);

    List<DatasourceAccessVO> listAccess(Long datasourceId);

    List<DatasourceSimpleVO> listAccessibleDatasources();

    boolean checkAccess(Long datasourceId);
}
