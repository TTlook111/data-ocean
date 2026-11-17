package com.dataocean.module.iam.mapper;

import com.dataocean.module.iam.entity.po.PermissionPO;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 系统功能的持久化适配，业务状态和权限由所属服务核验。
 */
public interface PermissionMapper {

    /**
     * 读取所属业务范围的完整当前记录，供服务在可信边界内核对。
     */
    @Select("SELECT * FROM iam_permission ORDER BY sort_order,code")
    List<PermissionPO> all();

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
    @Select(
            "SELECT (SELECT COUNT(*) FROM iam_role_permission rp JOIN iam_permission p ON p.code=rp.permission_code WHERE rp.role_id=#{role} AND p.is_protected=1)+(SELECT COUNT(*) FROM iam_role_grant_permission gp JOIN iam_permission p ON p.code=gp.permission_code WHERE gp.role_id=#{role} AND p.is_protected=1)")
    int protectedRole(long role);

    /**
     * 依据当前账号、使用权、页面权和授予依据计算实际可用恢复途径。
     */
    @Select(
            "SELECT u.id FROM iam_user u WHERE u.lifecycle='ENABLED' AND u.must_change_password=0 AND EXISTS(SELECT 1 FROM iam_user_role ur JOIN iam_role r ON r.id=ur.role_id JOIN iam_role_permission rp ON rp.role_id=r.id JOIN iam_permission p ON p.code=rp.permission_code WHERE ur.user_id=u.id AND ur.ended_at IS NULL AND r.lifecycle='ENABLED' AND rp.permission_code='users.recover' AND p.enabled=1) ORDER BY u.id")
    List<Long> recoveryCandidates();

    /**
     * 在所属业务事务内保存明确类型记录。
     */
    @Insert(
            "INSERT INTO iam_permission(code,page_permission_code,module_code,name,purpose,permission_kind,is_protected,enabled,sort_order) VALUES(#{d.code},#{d.pagePermissionCode},#{d.moduleCode},#{d.name},#{d.purpose},#{d.permissionKind},#{d.isProtected},1,#{d.sortOrder})")
    int insert(@Param("d") PermissionPO data);
}
