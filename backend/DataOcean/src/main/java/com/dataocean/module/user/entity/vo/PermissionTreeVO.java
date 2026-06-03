package com.dataocean.module.user.entity.vo;

import com.dataocean.module.user.entity.SysPermission;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PermissionTreeVO {

    private String module;

    private String moduleName;

    private List<SysPermission> permissions;
}
