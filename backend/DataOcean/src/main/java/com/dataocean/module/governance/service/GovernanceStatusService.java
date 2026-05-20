package com.dataocean.module.governance.service;

import java.util.List;
import java.util.Map;

public interface GovernanceStatusService {

    Map<String, String> updateTableStatus(Long snapshotId, String tableName,
                                          String newStatus, Long operatorId, String remark);

    Map<String, String> updateColumnStatus(Long snapshotId, Long columnId,
                                           String newStatus, Long operatorId, String remark);

    Map<String, Object> batchUpdateColumnStatus(Long snapshotId, String tableName,
                                                String newStatus, Long operatorId,
                                                String remark, List<String> excludeColumns);

    boolean isEligibleForRag(String governanceStatus);
}
