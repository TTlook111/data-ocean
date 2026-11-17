package com.dataocean.module.iam.mapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.iam.entity.dto.RoleProfileDTO;
import com.dataocean.module.iam.entity.po.RoleAssignmentPO;
import com.dataocean.module.iam.entity.po.RolePO;
import com.dataocean.module.iam.entity.po.RoleSnapshotPO;
import com.dataocean.module.iam.entity.query.RoleQuery;


public interface RoleMapper {
    @Select("<script>SELECT r.* FROM iam_role r <where><foreach collection='q.nameTerms' item='term'>AND r.name_key LIKE #{term} ESCAPE '!'</foreach><if test='q.lifecycle != null and q.lifecycle != &quot;&quot;'> AND r.lifecycle=#{q.lifecycle}</if></where></script>")
    Page<RolePO> page(Page<RolePO> page,@Param("q") RoleQuery query);
    @Select("SELECT * FROM iam_role ORDER BY id") List<RolePO> all();
    @Select("SELECT * FROM iam_role WHERE id=#{id}") RolePO find(long id);
    @Select("SELECT * FROM iam_role WHERE id=#{id} FOR UPDATE") RolePO lock(long id);
    @Select("SELECT * FROM iam_role_snapshot WHERE role_id=#{id} AND role_version=#{version}") RoleSnapshotPO snapshot(@Param("id") long id,@Param("version") int version);
    @Select("SELECT permission_code FROM iam_role_permission WHERE role_id=#{id} ORDER BY permission_code") List<String> permissions(long id);
    @Select("SELECT permission_code FROM iam_role_grant_permission WHERE role_id=#{id} ORDER BY permission_code") List<String> grantable(long id);
    @Select("SELECT * FROM iam_user_role WHERE user_id=#{id} AND ended_at IS NULL ORDER BY role_id") List<RoleAssignmentPO> assignments(long id);
    @Select("SELECT COUNT(*) FROM iam_user_role WHERE role_id=#{id} AND ended_at IS NULL") int memberCount(long id);
    @Select("SELECT COUNT(*) FROM iam_user_role WHERE role_id=#{role} AND user_id=#{user} AND ended_at IS NULL") int assigned(@Param("role") long role,@Param("user") long user);
    @Insert("INSERT INTO iam_role(code,name,name_key,description,is_builtin,created_by,created_at,updated_at) VALUES(#{code},#{d.name},#{key},#{d.description},#{builtin},#{actor},UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))")
    int insert(@Param("d") RoleProfileDTO data,@Param("code") String code,@Param("key") String key,@Param("builtin") boolean builtin,@Param("actor") Long actor);
    @Update("UPDATE iam_role SET name=#{d.name},name_key=#{key},description=#{d.description},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int profile(@Param("id") long id,@Param("d") RoleProfileDTO data,@Param("key") String key);
    @Update("UPDATE iam_role SET version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}") int touch(long id);
    @Delete("DELETE FROM iam_role_permission WHERE role_id=#{id}") int clearPermissions(long id);
    @Delete("DELETE FROM iam_role_grant_permission WHERE role_id=#{id}") int clearGrants(long id);
    @Insert("INSERT INTO iam_role_permission(role_id,permission_code,granted_by,granted_at) VALUES(#{id},#{code},#{actor},UTC_TIMESTAMP(3))")
    int addPermission(@Param("id") long id,@Param("code") String code,@Param("actor") Long actor);
    @Insert("INSERT INTO iam_role_grant_permission(role_id,permission_code,configured_by,configured_at) VALUES(#{id},#{code},#{actor},UTC_TIMESTAMP(3))")
    int addGrant(@Param("id") long id,@Param("code") String code,@Param("actor") Long actor);
    @Insert("INSERT INTO iam_role_snapshot(role_id,role_version,name,description,lifecycle,permission_codes,grantable_permission_codes,changed_by,changed_at) SELECT id,version,name,description,lifecycle,#{permissions},#{grants},#{actor},UTC_TIMESTAMP(3) FROM iam_role WHERE id=#{id}")
    int appendSnapshot(@Param("id") long id,@Param("permissions") String permissions,@Param("grants") String grants,@Param("actor") Long actor);
    @Insert("INSERT INTO iam_user_role(user_id,role_id,assigned_role_version,assigned_by,assigned_at) VALUES(#{user},#{role},#{version},#{actor},UTC_TIMESTAMP(3))")
    int assign(@Param("user") long user,@Param("role") long role,@Param("version") int version,@Param("actor") long actor);
    @Update("UPDATE iam_user_role SET ended_by=#{actor},ended_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND ended_at IS NULL") int endAssignment(@Param("id") long id,@Param("actor") long actor);
    @Update("UPDATE iam_user_role SET ended_by=#{actor},ended_at=UTC_TIMESTAMP(3) WHERE user_id=#{user} AND ended_at IS NULL") int endUser(@Param("user") long user,@Param("actor") long actor);
    @Update("UPDATE iam_role SET lifecycle=#{lifecycle},status_reason=#{reason},version=version+1,disabled_at=CASE WHEN #{lifecycle}='DISABLED' THEN UTC_TIMESTAMP(3) ELSE disabled_at END,archived_at=CASE WHEN #{lifecycle}='ARCHIVED' THEN UTC_TIMESTAMP(3) ELSE archived_at END,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int lifecycle(@Param("id") long id,@Param("lifecycle") String lifecycle,@Param("reason") String reason);
}
