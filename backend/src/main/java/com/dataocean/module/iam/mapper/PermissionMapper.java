package com.dataocean.module.iam.mapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.iam.entity.po.PermissionPO;


public interface PermissionMapper {
    @Select("SELECT * FROM iam_permission ORDER BY sort_order,code") List<PermissionPO> all();
    @Select("SELECT DISTINCT rp.permission_code FROM iam_user_role ur JOIN iam_role r ON r.id=ur.role_id JOIN iam_role_permission rp ON rp.role_id=r.id JOIN iam_permission p ON p.code=rp.permission_code WHERE ur.user_id=#{user} AND ur.ended_at IS NULL AND r.lifecycle='ENABLED' AND p.enabled=1 ORDER BY rp.permission_code") List<String> used(long user);
    @Select("SELECT DISTINCT gp.permission_code FROM iam_user_role ur JOIN iam_role r ON r.id=ur.role_id JOIN iam_role_grant_permission gp ON gp.role_id=r.id JOIN iam_permission p ON p.code=gp.permission_code WHERE ur.user_id=#{user} AND ur.ended_at IS NULL AND r.lifecycle='ENABLED' AND p.enabled=1 AND EXISTS(SELECT 1 FROM iam_role_permission op WHERE op.role_id=r.id AND op.permission_code=#{operation}) ORDER BY gp.permission_code") List<String> grantable(@Param("user") long user,@Param("operation") String operation);
    @Select("SELECT COUNT(*) FROM iam_user_role ur WHERE ur.user_id=#{user} AND ur.ended_at IS NULL AND (EXISTS(SELECT 1 FROM iam_role_permission rp JOIN iam_permission p ON p.code=rp.permission_code WHERE rp.role_id=ur.role_id AND p.is_protected=1) OR EXISTS(SELECT 1 FROM iam_role_grant_permission gp JOIN iam_permission p ON p.code=gp.permission_code WHERE gp.role_id=ur.role_id AND p.is_protected=1))") int protectedUser(long user);
    @Select("SELECT (SELECT COUNT(*) FROM iam_role_permission rp JOIN iam_permission p ON p.code=rp.permission_code WHERE rp.role_id=#{role} AND p.is_protected=1)+(SELECT COUNT(*) FROM iam_role_grant_permission gp JOIN iam_permission p ON p.code=gp.permission_code WHERE gp.role_id=#{role} AND p.is_protected=1)") int protectedRole(long role);
    @Select("SELECT COUNT(*) FROM iam_user u WHERE u.lifecycle='ENABLED' AND u.must_change_password=0 AND EXISTS(SELECT 1 FROM iam_user_role ur JOIN iam_role r ON r.id=ur.role_id JOIN iam_role_permission rp ON rp.role_id=r.id JOIN iam_permission p ON p.code=rp.permission_code WHERE ur.user_id=u.id AND ur.ended_at IS NULL AND r.lifecycle='ENABLED' AND rp.permission_code='users.recover' AND p.enabled=1) AND EXISTS(SELECT 1 FROM iam_user_role ur JOIN iam_role r ON r.id=ur.role_id JOIN iam_role_permission rp ON rp.role_id=r.id WHERE ur.user_id=u.id AND ur.ended_at IS NULL AND r.lifecycle='ENABLED' AND rp.permission_code='users.view') AND EXISTS(SELECT 1 FROM iam_user_role ur JOIN iam_role r ON r.id=ur.role_id JOIN iam_role_grant_permission gp ON gp.role_id=r.id WHERE ur.user_id=u.id AND ur.ended_at IS NULL AND r.lifecycle='ENABLED' AND gp.permission_code='users.recover')") int recoveryCount();
    @Insert("INSERT INTO iam_permission(code,page_permission_code,module_code,name,purpose,permission_kind,is_protected,enabled,sort_order) VALUES(#{d.code},#{d.pagePermissionCode},#{d.moduleCode},#{d.name},#{d.purpose},#{d.permissionKind},#{d.isProtected},1,#{d.sortOrder})") int insert(@Param("d") PermissionPO data);
}
