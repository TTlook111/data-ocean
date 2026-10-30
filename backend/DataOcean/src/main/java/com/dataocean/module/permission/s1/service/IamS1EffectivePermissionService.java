package com.dataocean.module.permission.s1.service;

import com.dataocean.module.permission.s1.entity.dto.IamS1DataAuthorizationRequestDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot;

/**
 * IAM-SIMPLE-1 用户实际权限预览。
 * <p>
 * 预览必须调用与真实查询完全相同的统一 Resolver（{@code IamS1DataAuthorizationResolver}），
 * 不在前端或管理端另做一套“看起来差不多”的合并算法，也不返回业务记录或权限参数原值。
 * </p>
 */
public interface IamS1EffectivePermissionService {

    /**
     * 计算并返回实际权限快照。
     *
     * @param callerUserId 当前登录用户
     * @param request      预览请求；预览他人时必须具备查看用户实际权限且负责目标源
     */
    IamS1DataAuthorizationSnapshot preview(Long callerUserId, IamS1DataAuthorizationRequestDTO request);
}
