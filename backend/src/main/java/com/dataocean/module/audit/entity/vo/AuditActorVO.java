package com.dataocean.module.audit.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class AuditActorVO {
    private String kind;
    private String userId;
    private String username;
    private String displayName;
}
