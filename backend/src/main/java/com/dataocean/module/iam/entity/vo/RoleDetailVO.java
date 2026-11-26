package com.dataocean.module.iam.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 角色详情复用当前状态及成员数量摘要，只增加完整使用功能。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
public final class RoleDetailVO extends RoleRowVO {

    /**
     * 角色当前完整使用功能集合，授予上限由独立核权接口读取。
     */
    private List<String> permissionCodes;
}
