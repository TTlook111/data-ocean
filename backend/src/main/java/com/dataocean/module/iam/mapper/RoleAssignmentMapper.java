package com.dataocean.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.iam.entity.po.RoleAssignmentPO;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 读取当前角色分配及其成员范围。
 */
public interface RoleAssignmentMapper extends BaseMapper<RoleAssignmentPO> {

    /**
     * 按角色编号读取人员尚未结束的分配。
     */
    default List<RoleAssignmentPO> assignments(long id) {
        return assignmentsForUsers(List.of(id));
    }

    /**
     * 按本页人员批量读取当前角色分配，按角色编号保持每个人的展示顺序。
     */
    default List<RoleAssignmentPO> assignmentsForUsers(Collection<Long> ids) {
        if (ids.isEmpty()) {
            // 空人员范围没有角色分配。
            return List.of();
        }

        return selectList(Wrappers.<RoleAssignmentPO>lambdaQuery()
                .in(RoleAssignmentPO::getUserId, ids)
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
     * 批量统计本页角色的当前分配，停用人员仍计入。
     */
    @Select(
            """
            <script>
              SELECT role_id AS roleId, COUNT(*) AS memberCount
              FROM iam_user_role
              WHERE ended_at IS NULL AND role_id IN
                <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
              GROUP BY role_id
            </script>
            """)
    List<MemberCount> memberCounts(@Param("ids") Collection<Long> ids);

    /**
     * 当前角色分配的统计结果。
     *
     * @param roleId 角色的稳定标识
     * @param memberCount 仍保留该分配的人员数
     */
    record MemberCount(long roleId, long memberCount) {}

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
