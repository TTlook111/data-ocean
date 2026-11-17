package com.dataocean.module.iam.entity.po;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class GuardPO {
    private Integer id;
    private long securityVersion;
    private boolean bootstrapCompleted;
    private LocalDateTime updatedAt;
}
