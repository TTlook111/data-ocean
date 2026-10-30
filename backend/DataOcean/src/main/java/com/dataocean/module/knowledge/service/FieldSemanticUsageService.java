package com.dataocean.module.knowledge.service;

import com.dataocean.module.knowledge.entity.vo.FieldSemanticUsageVO;

public interface FieldSemanticUsageService {
    FieldSemanticUsageVO status(Long columnMetaId, Long snapshotId, Long userId);
}
