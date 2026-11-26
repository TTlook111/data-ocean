package com.dataocean.module.iam.service;

import com.dataocean.module.iam.entity.vo.PermissionPageVO;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 系统功能的业务边界，调用者通过明确类型协作。
 */
public interface PermissionService {

    /**
     * 合并当前有效角色的使用能力，不在会话保存永久权限快照。
     */
    Set<String> effective(long userId);

    /**
     * 按同一管理动作合并角色明确授予集合，不能用使用权代替。
     */
    Set<String> grantable(long userId, String operation);

    /**
     * 当前动作或显式所属页面不满足时抛出明确权限拒绝。
     */
    void require(String code);

    /**
     * 要求至少一个明确配置入口具备当前实际资格。
     */
    void requireAny(String... codes);

    /**
     * 同时检查当前动作及其显式所属页面权。
     */
    boolean has(String code);

    /**
     * 依据系统目录检查指定集合是否含受保护项。
     */
    boolean hasProtectedCodes(Collection<String> codes);

    /**
     * 检查人员保留的当前角色是否涉及受保护能力，包含停用角色。
     */
    boolean protectedUser(long userId);

    /**
     * 检查角色完整使用及授予集合是否包含受保护能力。
     */
    boolean protectedRole(long roleId);

    /**
     * 按相同的使用和授予规则批量识别指定角色中的受保护角色。
     */
    Set<Long> protectedRoles(Collection<Long> roleIds);

    /**
     * 核验目录项有效性及动作所属页面的完整选择。
     */
    void validateCodes(List<String> codes);

    /**
     * 按同一管理动作核对完整实际能力是否落在当前可授予集合内。
     */
    void checkGrant(List<String> codes, String operation);

    /**
     * 比较角色变更前后操作者的完整授予资格，阻止自我提权。
     */
    void checkSelfRoleChange(long roleId, List<String> permissions, List<String> grants, String lifecycle);

    /**
     * 核对个人角色调整不会提高操作者自己的管理上限。
     */
    void checkSelfAssignment(List<RoleVersionRefVO> roles);

    /**
     * 在配置锁内检查变更后仍至少存在一条实际可用恢复途径。
     */
    void ensureRecovery();

    /**
     * 按显式页面归属组装功能目录，不从权限前缀推断授权。
     */
    List<PermissionPageVO> catalog();
}
