package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class MutationVO {
    private String commandId;
    private String targetType;
    private String targetId;
    private int version;
}
