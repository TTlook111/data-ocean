package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class MembershipRefVO {
    private String id;
    private DepartmentRefVO department;
    private boolean isPrimary;
    private Instant joinedAt;
}
