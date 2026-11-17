package com.dataocean.module.iam.entity.po;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class MembershipPO {
    private Long id;
    private Long userId;
    private Long departmentId;
    private boolean isPrimary;
    private Long joinedBy;
    private LocalDateTime joinedAt;
    private Long leftBy;
    private LocalDateTime leftAt;
}
