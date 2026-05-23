package com.dataocean.module.governance.service;

import com.dataocean.module.governance.entity.vo.QualityCheckResultVO;

import java.util.List;

/**
 * 元数据质量校验服务。
 */
public interface QualityCheckService {

    /**
     * 执行快照质量校验。
     *
     * @param snapshotId  快照 ID
     * @param dimensions  可选校验维度；为空时校验全部维度
     * @param tableNames  可选表名范围；为空时校验全部表
     * @return 质量校验结果
     */
    QualityCheckResultVO executeQualityCheck(Long snapshotId, List<String> dimensions, List<String> tableNames);
}
