package com.dataocean.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.iam.entity.dto.RoleProfileDTO;
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
public interface RoleMapper extends BaseMapper<RolePO> {

    /**
     * 在数据库中使用同一过滤范围完成排序、统计及分页。
     */
    default Page<RolePO> page(Page<RolePO> page, RoleQuery query) {
        String lifecycle = query.getLifecycle();
        var conditions = Wrappers.<RolePO>lambdaQuery()
                .eq(lifecycle != null && !lifecycle.isEmpty(), RolePO::getLifecycle, lifecycle);
        for (String term : query.getNameTerms()) {
            // 搜索词已按字面匹配规则转义，保持名称键原有的 SQL 比较语义。
            conditions.apply("name_key LIKE {0} ESCAPE '!'", term);
        }
        return selectPage(page, conditions);
    }

    /**
     * 读取所属业务范围的完整当前记录，供服务在可信边界内核对。
     */
    default List<RolePO> all() {
        return selectList(Wrappers.<RolePO>lambdaQuery().orderByAsc(RolePO::getId));
    }

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
    default int profile(long id, RoleProfileDTO data, String key) {
        return update(Wrappers.<RolePO>lambdaUpdate()
                .eq(RolePO::getId, id)
                .set(RolePO::getName, data.getName())
                .set(RolePO::getNameKey, key)
                .set(RolePO::getDescription, data.getDescription())
                .setIncrBy(RolePO::getVersion, 1)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 因当前关系或内容实际变化递增聚合修订和更新时间。
     */
    default int touch(long id) {
        return update(Wrappers.<RolePO>lambdaUpdate()
                .eq(RolePO::getId, id)
                .setIncrBy(RolePO::getVersion, 1)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }

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
     * 在所属事务中变更对象生命周期及真实状态时间。
     */
    @Update(
            "UPDATE iam_role SET lifecycle=#{lifecycle},status_reason=#{reason},version=version+1,disabled_at=CASE WHEN #{lifecycle}='DISABLED' THEN UTC_TIMESTAMP(3) ELSE disabled_at END,archived_at=CASE WHEN #{lifecycle}='ARCHIVED' THEN UTC_TIMESTAMP(3) ELSE archived_at END,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int lifecycle(@Param("id") long id, @Param("lifecycle") String lifecycle, @Param("reason") String reason);
}
