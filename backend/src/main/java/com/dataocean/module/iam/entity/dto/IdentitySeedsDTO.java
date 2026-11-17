package com.dataocean.module.iam.entity.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公共契约的明确传输结构，字段职责及校验与接口契约一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IdentitySeedsDTO {

    /**
     * 系统版本化功能页面目录。
     */
    private List<PermissionPageSeedDTO> pages;

    /**
     * 版本化的预设角色及其明确初始使用能力。
     */
    private List<RoleSeedDTO> roles;
}
