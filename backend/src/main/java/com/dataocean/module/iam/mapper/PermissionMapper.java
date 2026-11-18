package com.dataocean.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.iam.entity.po.PermissionPO;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 系统功能的持久化适配，业务状态和权限由所属服务核验。
 */
public interface PermissionMapper extends BaseMapper<PermissionPO> {

    /**
     * 角色的使用或授予集合包含受保护功能时命中，停用功能也计入。
     */
    String ROLE_PROTECTION =
            """
            (EXISTS(SELECT 1 FROM iam_role_permission rp JOIN iam_permission p ON p.code=rp.permission_code
                    WHERE rp.role_id=r.id AND p.is_protected=1)
            OR EXISTS(SELECT 1 FROM iam_role_grant_permission gp JOIN iam_permission p ON p.code=gp.permission_code
                      WHERE gp.role_id=r.id AND p.is_protected=1))
            """;

    /**
     * 读取所属业务范围的完整当前记录，供服务在可信边界内核对。
     */
    default List<PermissionPO> all() {
        return selectList(
                Wrappers.<PermissionPO>lambdaQuery().orderByAsc(PermissionPO::getSortOrder, PermissionPO::getCode));
    }

    /**
     * 读取正常角色中当前启用的功能使用代码。
     */
    @Select(
            "SELECT DISTINCT rp.permission_code FROM iam_user_role ur JOIN iam_role r ON r.id=ur.role_id JOIN iam_role_permission rp ON rp.role_id=r.id JOIN iam_permission p ON p.code=rp.permission_code WHERE ur.user_id=#{user} AND ur.ended_at IS NULL AND r.lifecycle='ENABLED' AND p.enabled=1 ORDER BY rp.permission_code")
    List<String> used(long user);

    /**
     * 按同一管理动作合并角色明确授予集合，不能用使用权代替。
     */
    @Select(
            "SELECT DISTINCT gp.permission_code FROM iam_user_role ur JOIN iam_role r ON r.id=ur.role_id JOIN iam_role_grant_permission gp ON gp.role_id=r.id JOIN iam_permission p ON p.code=gp.permission_code WHERE ur.user_id=#{user} AND ur.ended_at IS NULL AND r.lifecycle='ENABLED' AND p.enabled=1 AND EXISTS(SELECT 1 FROM iam_role_permission op WHERE op.role_id=r.id AND op.permission_code=#{operation}) ORDER BY gp.permission_code")
    List<String> grantable(@Param("user") long user, @Param("operation") String operation);

    /**
     * 检查人员保留的当前角色是否涉及受保护能力，包含停用角色。
     */
    @Select(
            "SELECT COUNT(*) FROM iam_user_role ur WHERE ur.user_id=#{user} AND ur.ended_at IS NULL AND (EXISTS(SELECT 1 FROM iam_role_permission rp JOIN iam_permission p ON p.code=rp.permission_code WHERE rp.role_id=ur.role_id AND p.is_protected=1) OR EXISTS(SELECT 1 FROM iam_role_grant_permission gp JOIN iam_permission p ON p.code=gp.permission_code WHERE gp.role_id=ur.role_id AND p.is_protected=1))")
    int protectedUser(long user);

    /**
     * 检查角色完整使用及授予集合是否包含受保护能力。
     */
    @Select("SELECT COUNT(*) FROM iam_role r WHERE r.id=#{role} AND " + ROLE_PROTECTION)
    int protectedRole(long role);

    /**
     * 一次查出指定的非空角色范围内所有受保护角色的编号。
     */
    @Select(
            """
            <script>
              SELECT r.id FROM iam_role r WHERE r.id IN
                <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
              AND
            """
                    + ROLE_PROTECTION
                    + "</script>")
    List<Long> protectedRoles(@Param("ids") Collection<Long> ids);

    /**
     * 依据当前账号、使用权、页面权和授予依据计算实际可用恢复途径。
     */
    @Select(
            "SELECT u.id FROM iam_user u WHERE u.lifecycle='ENABLED' AND u.must_change_password=0 AND EXISTS(SELECT 1 FROM iam_user_role ur JOIN iam_role r ON r.id=ur.role_id JOIN iam_role_permission rp ON rp.role_id=r.id JOIN iam_permission p ON p.code=rp.permission_code WHERE ur.user_id=u.id AND ur.ended_at IS NULL AND r.lifecycle='ENABLED' AND rp.permission_code='users.recover' AND p.enabled=1) ORDER BY u.id")
    List<Long> recoveryCandidates();
}
