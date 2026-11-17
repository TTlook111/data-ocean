package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class PermissionPageVO {
    private String pageCode;
    private String moduleCode;
    private String name;
    private List<PermissionVO> operations;
}
