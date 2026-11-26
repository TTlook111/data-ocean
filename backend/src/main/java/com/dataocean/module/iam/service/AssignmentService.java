package com.dataocean.module.iam.service;

import com.dataocean.module.iam.entity.dto.MembershipSelectionDTO;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;

import java.util.List;

/**
 * 公共契约的业务边界，调用者通过明确类型协作。
 */
public interface AssignmentService {

    /**
     * 核对唯一主部门和去重兼任，新增或恢复时要求正常组织。
     */
    void validateMembership(long userId, MembershipSelectionDTO selection, boolean requireNormal);

    /**
     * 比较人员当前完整主兼任事实与提交目标是否一致。
     */
    boolean membershipMatches(long userId, MembershipSelectionDTO selection);

    /**
     * 按完整主兼任目标结束变更关系并建立新关系，保持唯一主部门。
     */
    boolean applyMembership(long userId, MembershipSelectionDTO selection);

    /**
     * 核对所选角色当前状态、修订、完整实际能力及受保护分配边界。
     */
    void validateRoles(long userId, List<RoleVersionRefVO> selected, String operation, boolean protectedFlow);

    /**
     * 比较当前角色分配和完整最终集合是否一致。
     */
    boolean rolesMatch(long userId, List<RoleVersionRefVO> selected);

    /**
     * 按完整目标集合更新当前角色分配并保留结束历史。
     */
    boolean applyRoles(long userId, List<RoleVersionRefVO> selected);
}
