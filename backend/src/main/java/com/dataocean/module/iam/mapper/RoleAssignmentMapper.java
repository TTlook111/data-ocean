package com.dataocean.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.iam.entity.po.RoleAssignmentPO;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 读取当前角色分配及其成员范围。
 */
public interface RoleAssignmentMapper extends BaseMapper<RoleAssignmentPO> {

    /**
     * 按角色编号读取人员尚未结束的分配。
     */
    default List<RoleAssignmentPO> assignments(long id) {
        return selectList(Wrappers.<RoleAssignmentPO>lambdaQuery()
                .eq(RoleAssignmentPO::getUserId, id)
                .isNull(RoleAssignmentPO::getEndedAt)
                .orderByAsc(RoleAssignmentPO::getRoleId));
    }

    /**
     * 统计角色保留的分配，停用人员仍计入。
     */
    default int memberCount(long role) {
        return Math.toIntExact(selectCount(Wrappers.<RoleAssignmentPO>lambdaQuery()
                .eq(RoleAssignmentPO::getRoleId, role)
                .isNull(RoleAssignmentPO::getEndedAt)));
    }

    /**
     * 建立角色分配并绑定当时的角色修订。
     */
    @Insert(
            "INSERT INTO iam_user_role(user_id,role_id,assigned_role_version,assigned_by,assigned_at) VALUES(#{user},#{role},#{version},#{actor},UTC_TIMESTAMP(3))")
    int assign(
            @Param("user") long user,
            @Param("role") long role,
            @Param("version") int version,
            @Param("actor") long actor);

    /**
     * 结束指定当前角色分配，历史记录继续保留。
     */
    default int endAssignment(long id, long actor) {
        return update(Wrappers.<RoleAssignmentPO>lambdaUpdate()
                .eq(RoleAssignmentPO::getId, id)
                .isNull(RoleAssignmentPO::getEndedAt)
                .set(RoleAssignmentPO::getEndedBy, actor)
                .setSql("ended_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 归档人员时仅结束仍有效的角色分配。
     */
    default int endUser(long user, long actor) {
        return update(Wrappers.<RoleAssignmentPO>lambdaUpdate()
                .eq(RoleAssignmentPO::getUserId, user)
                .isNull(RoleAssignmentPO::getEndedAt)
                .set(RoleAssignmentPO::getEndedBy, actor)
                .setSql("ended_at=UTC_TIMESTAMP(3)"));
    }
}
