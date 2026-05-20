package com.dataocean.module.governance.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.governance.entity.vo.QualityIssueVO;

public interface QualityIssueService {

    Page<QualityIssueVO> listIssues(Long snapshotId, String dimension, String severity,
                                    String status, String tableName, int page, int size);

    void handleIssue(Long issueId, String targetStatus, String resolutionNote, Long operatorId);

    int batchHandle(java.util.List<Long> issueIds, String targetStatus, Long operatorId);

    void assignIssue(Long issueId, Long assigneeId);
}
