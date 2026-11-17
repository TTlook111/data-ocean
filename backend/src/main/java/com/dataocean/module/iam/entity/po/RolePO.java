package com.dataocean.module.iam.entity.po;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class RolePO {
    private Long id;
    private String code;
    private String name;
    private String nameKey;
    private String description;
    private boolean isBuiltin;
    private String lifecycle;
    private int version;
    private LocalDateTime disabledAt;
    private LocalDateTime archivedAt;
    private String statusReason;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
