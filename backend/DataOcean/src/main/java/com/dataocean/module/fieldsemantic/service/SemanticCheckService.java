package com.dataocean.module.fieldsemantic.service;

import com.dataocean.module.fieldsemantic.entity.vo.SemanticCheckBatchVO;

public interface SemanticCheckService {
    SemanticCheckBatchVO run(Long snapshotId, Long userId);
    SemanticCheckBatchVO latest(Long snapshotId, Long userId);
}
