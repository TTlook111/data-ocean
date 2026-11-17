package com.dataocean.module.iam.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.iam.entity.dto.RoleProfileDTO;
import com.dataocean.module.iam.entity.po.RoleAssignmentPO;
import com.dataocean.module.iam.entity.po.RolePO;
import com.dataocean.module.iam.entity.po.RoleSnapshotPO;
import com.dataocean.module.iam.entity.query.RoleQuery;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 角色与授予的持久化适配，业务状态和权限由所属服务核验。
 */
public interface RoleMapper {

    /**
     * 在数据库中使用同一过滤范围完成排序、统计及分页。
     */
    @Select(
            "<script>SELECT r.* FROM iam_role r <where><foreach collection='q.nameTerms' item='term'>AND r.name_key LIKE #{term} ESCAPE '!'</foreach><if test='q.lifecycle != null and q.lifecycle != &quot;&quot;'> AND r.lifecycle=#{q.lifecycle}</if></where></script>")
    Page<RolePO> page(Page<RolePO> page, @Param("q") RoleQuery query);

    /**
     * 读取所属业务范围的完整当前记录，供服务在可信边界内核对。
     */
    @Select("SELECT * FROM iam_role ORDER BY id")
    List<RolePO> all();

    /**
     * 按稳定标识读取当前记录，由所属服务继续校验可见性和状态。
     */
    @Select("SELECT * FROM iam_role WHERE id=#{id}")
    RolePO find(long id);

    /**
     * 领取当前配置或业务记录的排他锁，提交前重新核验权威事实。
     */
    @Select("SELECT * FROM iam_role WHERE id=#{id} FOR UPDATE")
    RolePO lock(long id);

    /**
     * 读取明确角色与版本的不可变历史快照。
     */
    @Select("SELECT * FROM iam_role_snapshot WHERE role_id=#{id} AND role_version=#{version}")
    RoleSnapshotPO snapshot(@Param("id") long id, @Param("version") int version);

    /**
     * 读取角色当前明确的功能使用集合。
     */
    @Select("SELECT permission_code FROM iam_role_permission WHERE role_id=#{id} ORDER BY permission_code")
    List<String> permissions(long id);

    /**
     * 按同一管理动作合并角色明确授予集合，不能用使用权代替。
     */
    @Select("SELECT permission_code FROM iam_role_grant_permission WHERE role_id=#{id} ORDER BY permission_code")
    List<String> grantable(long id);

    /**
     * 读取人员尚未结束的当前角色分配。
     */
    @Select("SELECT * FROM iam_user_role WHERE user_id=#{id} AND ended_at IS NULL ORDER BY role_id")
    List<RoleAssignmentPO> assignments(long id);

    /**
     * 统计角色尚未结束的当前分配，停用人员仍属于保留分配。
     */
    @Select("SELECT COUNT(*) FROM iam_user_role WHERE role_id=#{id} AND ended_at IS NULL")
    int memberCount(long id);

    /**
     * 检查人员是否仍持有指定角色的当前分配。
     */
    @Select("SELECT COUNT(*) FROM iam_user_role WHERE role_id=#{role} AND user_id=#{user} AND ended_at IS NULL")
    int assigned(@Param("role") long role, @Param("user") long user);

    /**
     * 在所属业务事务内保存明确类型记录。
     */
    @Insert(
            "INSERT INTO iam_role(code,name,name_key,description,is_builtin,created_by,created_at,updated_at) VALUES(#{code},#{d.name},#{key},#{d.description},#{builtin},#{actor},UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))")
    int insert(
            @Param("d") RoleProfileDTO data,
            @Param("code") String code,
            @Param("key") String key,
            @Param("builtin") boolean builtin,
            @Param("actor") Long actor);

    /**
     * 按当前对象版本原子更新业务资料并递增内容修订。
     */
    @Update(
            "UPDATE iam_role SET name=#{d.name},name_key=#{key},description=#{d.description},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int profile(@Param("id") long id, @Param("d") RoleProfileDTO data, @Param("key") String key);

    /**
     * 因当前关系或内容实际变化递增聚合修订和更新时间。
     */
    @Update("UPDATE iam_role SET version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int touch(long id);

    /**
     * 在所属配置事务内清空待重建的角色使用集合。
     */
    @Delete("DELETE FROM iam_role_permission WHERE role_id=#{id}")
    int clearPermissions(long id);

    /**
     * 在所属配置事务内清空待重建的角色授予集合。
     */
    @Delete("DELETE FROM iam_role_grant_permission WHERE role_id=#{id}")
    int clearGrants(long id);

    /**
     * 为指定角色保存一项功能使用能力。
     */
    @Insert(
            "INSERT INTO iam_role_permission(role_id,permission_code,granted_by,granted_at) VALUES(#{id},#{code},#{actor},UTC_TIMESTAMP(3))")
    int addPermission(@Param("id") long id, @Param("code") String code, @Param("actor") Long actor);

    /**
     * 为指定角色保存一项显式可授予能力。
     */
    @Insert(
            "INSERT INTO iam_role_grant_permission(role_id,permission_code,configured_by,configured_at) VALUES(#{id},#{code},#{actor},UTC_TIMESTAMP(3))")
    int addGrant(@Param("id") long id, @Param("code") String code, @Param("actor") Long actor);

    /**
     * 与角色当前版本同时保存不可变的使用和授予集合快照。
     */
    @Insert(
            "INSERT INTO iam_role_snapshot(role_id,role_version,name,description,lifecycle,permission_codes,grantable_permission_codes,changed_by,changed_at) SELECT id,version,name,description,lifecycle,#{permissions},#{grants},#{actor},UTC_TIMESTAMP(3) FROM iam_role WHERE id=#{id}")
    int appendSnapshot(
            @Param("id") long id,
            @Param("permissions") String permissions,
            @Param("grants") String grants,
            @Param("actor") Long actor);

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
    @Update("UPDATE iam_user_role SET ended_by=#{actor},ended_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND ended_at IS NULL")
    int endAssignment(@Param("id") long id, @Param("actor") long actor);

    /**
     * 结束人员当前关联关系，用于归档事务而非历史硬删除。
     */
    @Update(
            "UPDATE iam_user_role SET ended_by=#{actor},ended_at=UTC_TIMESTAMP(3) WHERE user_id=#{user} AND ended_at IS NULL")
    int endUser(@Param("user") long user, @Param("actor") long actor);

    /**
     * 在所属事务中变更对象生命周期及真实状态时间。
     */
    @Update(
            "UPDATE iam_role SET lifecycle=#{lifecycle},status_reason=#{reason},version=version+1,disabled_at=CASE WHEN #{lifecycle}='DISABLED' THEN UTC_TIMESTAMP(3) ELSE disabled_at END,archived_at=CASE WHEN #{lifecycle}='ARCHIVED' THEN UTC_TIMESTAMP(3) ELSE archived_at END,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int lifecycle(@Param("id") long id, @Param("lifecycle") String lifecycle, @Param("reason") String reason);
}
