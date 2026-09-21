package com.dataocean.module.permission.s1.entity.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * S1 访问申请视图。业务用户只看本人申请；管理员只看到负责数据源的队列。
 */
public record IamS1AccessRequestVO(Long id,
                                   Long requesterId,
                                   String requesterName,
                                   Long datasourceId,
                                   String datasourceName,
                                   Long metadataSnapshotId,
                                   String tableName,
                                   List<String> requestedColumns,
                                   String rowScope,
                                   String rowScopeName,
                                   LocalDateTime requestedValidUntil,
                                   String purpose,
                                   String status,
                                   String statusName,
                                   LocalDateTime createdAt,
                                   IamS1AccessApprovalVO approval) {
}
