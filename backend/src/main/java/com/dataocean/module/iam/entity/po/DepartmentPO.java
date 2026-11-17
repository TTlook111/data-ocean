package com.dataocean.module.iam.entity.po;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class DepartmentPO {
    private Long id;
    private Long parentId;
    private String code;
    private String name;
    private Long managerUserId;
    private String note;
    private int sortOrder;
    private String lifecycle;
    private boolean accessReviewRequired;
    private int version;
    private LocalDateTime disabledAt;
    private LocalDateTime archivedAt;
    private String statusReason;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
