package com.dataocean.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.iam.entity.dto.NewUserProfileDTO;
import com.dataocean.module.iam.entity.dto.UpdateContactDTO;
import com.dataocean.module.iam.entity.dto.UpdateUserDTO;
import com.dataocean.module.iam.entity.po.UserPO;
import com.dataocean.module.iam.entity.query.PersonQuery;

import java.util.List;
import java.util.Objects;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 人员资料的持久化适配，业务状态和权限由所属服务核验。
 */
public interface UserMapper extends BaseMapper<UserPO> {

    /**
     * 列表与总数共用参数化条件，明确传入空部门范围时不匹配任何人员。
     */
    String FILTER =
            """
        <where>
          <foreach collection="q.displayNameTerms" item="term"> AND u.display_name COLLATE utf8mb4_0900_ai_ci LIKE #{term} ESCAPE '!'</foreach>
          <foreach collection="q.usernameTerms" item="term"> AND u.username COLLATE utf8mb4_0900_ai_ci LIKE #{term} ESCAPE '!'</foreach>
          <if test="departments != null">
            <choose>
              <when test="departments.size() &gt; 0">
                AND EXISTS(SELECT 1 FROM iam_user_department ud WHERE ud.user_id=u.id AND ud.left_at IS NULL AND ud.department_id IN
                  <foreach collection="departments" item="id" open="(" separator="," close=")">#{id}</foreach>)
              </when>
              <otherwise>AND 1 = 0</otherwise>
            </choose>
          </if>
          <if test="roleId != null">AND EXISTS(SELECT 1 FROM iam_user_role ur WHERE ur.user_id=u.id AND ur.role_id=#{roleId} AND ur.ended_at IS NULL)</if>
          <if test="q.displayStatus != null and q.displayStatus != ''">AND (CASE WHEN u.lifecycle!='ENABLED' THEN u.lifecycle
              WHEN u.must_change_password=1 AND u.first_login_at IS NULL THEN 'PENDING_FIRST_LOGIN'
              WHEN u.must_change_password=1 THEN 'PASSWORD_CHANGE_REQUIRED' ELSE 'ENABLED' END)=#{q.displayStatus}</if>
        </where>
        """;

    /**
     * 在数据库中使用同一过滤范围完成排序、统计及分页。
     */
    @Select("<script>SELECT u.* FROM iam_user u " + FILTER + "</script>")
    Page<UserPO> page(
            Page<UserPO> page,
            @Param("q") PersonQuery query,
            @Param("departments") List<Long> departments,
            @Param("roleId") Long roleId);

    /**
     * 领取当前配置或业务记录的排他锁，提交前重新核验权威事实。
     */
    @Select("SELECT * FROM iam_user WHERE id=#{id} FOR UPDATE")
    UserPO lock(long id);

    /**
     * 按契约的精确登录名读取人员，不进行大小写折叠。
     */
    default UserPO byUsername(String username) {
        return selectOne(Wrappers.<UserPO>lambdaQuery().eq(UserPO::getUsername, username));
    }

    /**
     * 读取所属业务范围的完整当前记录，供服务在可信边界内核对。
     */
    default List<UserPO> all() {
        return selectList(Wrappers.<UserPO>lambdaQuery().orderByAsc(UserPO::getId));
    }

    /**
     * 使用数据库时钟创建人员，并回填同一条插入返回的编号。
     */
    @Insert(
            """
            INSERT INTO iam_user(username,display_name,phone,email,management_note,created_by,created_at,updated_at)
            VALUES(#{username},#{displayName},#{phone},#{email},#{managementNote},#{createdBy},UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertCreated(UserPO row);

    /**
     * 只将新建资料映射为待插入记录，认证和版本列使用表默认值。
     */
    default long create(NewUserProfileDTO data, Long actor) {
        var row = new UserPO();
        row.setUsername(data.getUsername());
        row.setDisplayName(data.getDisplayName());
        row.setPhone(data.getPhone());
        row.setEmail(data.getEmail());
        row.setManagementNote(data.getManagementNote());
        row.setCreatedBy(actor);
        insertCreated(row);
        return Objects.requireNonNull(row.getId(), "数据库未返回人员编号");
    }

    /**
     * 按当前对象版本原子更新业务资料并递增内容修订。
     */
    default int profile(long id, UpdateUserDTO data) {
        return update(Wrappers.<UserPO>lambdaUpdate()
                .eq(UserPO::getId, id)
                .eq(UserPO::getVersion, data.getExpectedVersion())
                .set(UserPO::getDisplayName, data.getDisplayName())
                .set(UserPO::getPhone, data.getPhone())
                .set(UserPO::getEmail, data.getEmail())
                .set(UserPO::getManagementNote, data.getManagementNote())
                .setIncrBy(UserPO::getVersion, 1)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 以当前本人身份和版本原子保存联系方式，允许显式清空。
     */
    default int contact(long id, UpdateContactDTO data) {
        return update(Wrappers.<UserPO>lambdaUpdate()
                .eq(UserPO::getId, id)
                .eq(UserPO::getVersion, data.getExpectedVersion())
                .set(UserPO::getPhone, data.getPhone())
                .set(UserPO::getEmail, data.getEmail())
                .setIncrBy(UserPO::getVersion, 1)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 因当前关系或内容实际变化递增聚合修订和更新时间。
     */
    default int touch(long id) {
        return update(Wrappers.<UserPO>lambdaUpdate()
                .eq(UserPO::getId, id)
                .setIncrBy(UserPO::getVersion, 1)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 仅在认证版本仍匹配且尚未登录时记录首次认证事实。
     */
    default int firstLogin(long id, long authenticationVersion) {
        return update(Wrappers.<UserPO>lambdaUpdate()
                .eq(UserPO::getId, id)
                .eq(UserPO::getAuthenticationVersion, authenticationVersion)
                .isNull(UserPO::getFirstLoginAt)
                .setSql("first_login_at=UTC_TIMESTAMP(3)")
                .setIncrBy(UserPO::getVersion, 1)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 递增认证修订并设置必要改密标记，使所有旧版本会话失效。
     */
    default int invalidate(long id, boolean mustChange) {
        return update(Wrappers.<UserPO>lambdaUpdate()
                .eq(UserPO::getId, id)
                .set(UserPO::isMustChangePassword, mustChange)
                .setIncrBy(UserPO::getAuthenticationVersion, 1)
                .setIncrBy(UserPO::getVersion, 1)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 在所属事务中变更对象生命周期及真实状态时间。
     */
    @Update(
            "UPDATE iam_user SET lifecycle=#{lifecycle},status_reason=#{reason},version=version+1,authentication_version=authentication_version+1,disabled_at=CASE WHEN #{lifecycle}='DISABLED' THEN UTC_TIMESTAMP(3) ELSE disabled_at END,archived_at=CASE WHEN #{lifecycle}='ARCHIVED' THEN UTC_TIMESTAMP(3) ELSE archived_at END,archived_affiliation=COALESCE(#{snapshot},archived_affiliation),updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int lifecycle(
            @Param("id") long id,
            @Param("lifecycle") String lifecycle,
            @Param("reason") String reason,
            @Param("snapshot") String snapshot);
}
