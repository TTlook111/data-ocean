package com.dataocean.module.fieldsemantic.service;

import com.dataocean.module.fieldsemantic.entity.vo.FieldGovernanceProgressVO;

public interface FieldGovernanceProgressService {
    FieldGovernanceProgressVO progress(Long columnId, Long snapshotId, Long userId);
}
