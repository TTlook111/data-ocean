package com.dataocean.module.governance.service;

import java.util.List;
import java.util.Map;

/**
 * 元数据治理状态服务。
 * <p>
 * 负责表和字段治理状态调整，并判断治理状态是否允许进入 RAG 检索范围。
 * </p>
 */
public interface GovernanceStatusService {

    /**
     * 更新指定表的治理状态。
     *
     * @param snapshotId  快照 ID
     * @param tableName   表名
     * @param newStatus   新治理状态
     * @param operatorId  操作人 ID
     * @param remark      变更备注
     * @return 状态变更结果
     */
    Map<String, String> updateTableStatus(Long snapshotId, String tableName,
                                           String newStatus, Long operatorId, String remark);

    /**
     * 更新指定字段的治理状态。
     *
     * @param snapshotId  快照 ID
     * @param columnId    字段元数据 ID
     * @param newStatus   新治理状态
     * @param operatorId  操作人 ID
     * @param remark      变更备注
     * @return 状态变更结果
     */
    Map<String, String> updateColumnStatus(Long snapshotId, Long columnId,
                                            String newStatus, Long operatorId, String remark);

    /**
     * 批量更新表下字段的治理状态。
     *
     * @param snapshotId      快照 ID
     * @param tableName       表名
     * @param newStatus       新治理状态
     * @param operatorId      操作人 ID
     * @param remark          变更备注
     * @param excludeColumns  不参与更新的字段名列表
     * @return 批量更新统计结果
     */
    Map<String, Object> batchUpdateColumnStatus(Long snapshotId, String tableName,
                                                 String newStatus, Long operatorId,
                                                 String remark, List<String> excludeColumns);

}
