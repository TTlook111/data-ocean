package com.dataocean.module.governance.service;

import com.dataocean.module.governance.entity.vo.QualityIssueTrendVO;

import java.util.Collection;

/** 在问题查看负责范围内读取单个快照的真实历史。 */
public interface QualityIssueTrendService {
    QualityIssueTrendVO read(long snapshotId, int days, Collection<Long> responsibleSources);
}
