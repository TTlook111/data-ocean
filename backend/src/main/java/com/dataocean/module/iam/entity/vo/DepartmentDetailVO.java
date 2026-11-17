package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class DepartmentDetailVO {
    private String id;
    private String code;
    private String name;
    private DepartmentRefVO parent;
    private UserRefVO manager;
    private String note;
    private int sortOrder;
    private String lifecycle;
    private int version;
    private int directMemberCount;
    private int descendantMemberCount;
    private int totalMemberCount;
    private boolean accessReviewRequired;
    private Instant updatedAt;
}
