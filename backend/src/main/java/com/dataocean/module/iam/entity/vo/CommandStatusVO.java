package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class CommandStatusVO {
    private String commandId;
    private String operationCode;
    private String state;
    private String resultCode;
    private EntityVersionRefVO target;
}
