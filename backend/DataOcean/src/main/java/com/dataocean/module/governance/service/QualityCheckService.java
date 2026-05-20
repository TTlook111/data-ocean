package com.dataocean.module.governance.service;

import com.dataocean.module.governance.entity.vo.QualityCheckResultVO;

import java.util.List;

public interface QualityCheckService {

    QualityCheckResultVO executeQualityCheck(Long snapshotId, List<String> dimensions, List<String> tableNames);
}
