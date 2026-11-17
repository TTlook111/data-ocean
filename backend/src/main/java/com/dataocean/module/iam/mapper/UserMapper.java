package com.dataocean.module.iam.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.iam.entity.dto.NewUserProfileDTO;
import com.dataocean.module.iam.entity.dto.UpdateContactDTO;
import com.dataocean.module.iam.entity.dto.UpdateUserDTO;
import com.dataocean.module.iam.entity.po.CredentialPO;
import com.dataocean.module.iam.entity.po.UserPO;
import com.dataocean.module.iam.entity.query.PersonQuery;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 人员资料的持久化适配，业务状态和权限由所属服务核验。
 */
public interface UserMapper {

    /**
     * 列表与总数共用的参数化过滤片段，文本条件按字面转义。
     */
    String FILTER =
            """
        <where>
          <foreach collection="q.displayNameTerms" item="term"> AND u.display_name COLLATE utf8mb4_0900_ai_ci LIKE #{term} ESCAPE '!'</foreach>
          <foreach collection="q.usernameTerms" item="term"> AND u.username COLLATE utf8mb4_0900_ai_ci LIKE #{term} ESCAPE '!'</foreach>
          <if test="departments != null">AND EXISTS(SELECT 1 FROM iam_user_department ud WHERE ud.user_id=u.id AND ud.left_at IS NULL AND ud.department_id IN
            <foreach collection="departments" item="id" open="(" separator="," close=")">#{id}</foreach>)</if>
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
     * 按稳定标识读取当前记录，由所属服务继续校验可见性和状态。
     */
    @Select("SELECT * FROM iam_user WHERE id=#{id}")
    UserPO find(long id);

    /**
     * 领取当前配置或业务记录的排他锁，提交前重新核验权威事实。
     */
    @Select("SELECT * FROM iam_user WHERE id=#{id} FOR UPDATE")
    UserPO lock(long id);

    /**
     * 按契约的精确登录名读取人员，不进行大小写折叠。
     */
    @Select("SELECT * FROM iam_user WHERE username=#{username}")
    UserPO byUsername(String username);

    /**
     * 读取指定人员的受控凭据记录，仅用于服务内部验密。
     */
    @Select("SELECT * FROM iam_user_credential WHERE user_id=#{id}")
    CredentialPO credential(long id);

    /**
     * 读取平台人员记录数量，仅用于受控空库初始化判断。
     */
    @Select("SELECT COUNT(*) FROM iam_user")
    int count();

    /**
     * 读取所属业务范围的完整当前记录，供服务在可信边界内核对。
     */
    @Select("SELECT * FROM iam_user ORDER BY id")
    List<UserPO> all();

    /**
     * 在所属业务事务内保存明确类型记录。
     */
    @Insert(
            "INSERT INTO iam_user(username,display_name,phone,email,management_note,created_by,created_at,updated_at) VALUES(#{d.username},#{d.displayName},#{d.phone},#{d.email},#{d.managementNote},#{actor},UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))")
    int insert(@Param("d") NewUserProfileDTO data, @Param("actor") Long actor);

    /**
     * 只向受控凭据表保存框架编码的安全散列。
     */
    @Insert("INSERT INTO iam_user_credential(user_id,password_hash,changed_at) VALUES(#{id},#{hash},UTC_TIMESTAMP(3))")
    int insertCredential(@Param("id") long id, @Param("hash") String hash);

    /**
     * 按当前对象版本原子更新业务资料并递增内容修订。
     */
    @Update(
            "UPDATE iam_user SET display_name=#{d.displayName},phone=#{d.phone},email=#{d.email},management_note=#{d.managementNote},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND version=#{d.expectedVersion}")
    int profile(@Param("id") long id, @Param("d") UpdateUserDTO data);

    /**
     * 以当前本人身份和版本原子保存联系方式，允许显式清空。
     */
    @Update(
            "UPDATE iam_user SET phone=#{d.phone},email=#{d.email},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND version=#{d.expectedVersion}")
    int contact(@Param("id") long id, @Param("d") UpdateContactDTO data);

    /**
     * 因当前关系或内容实际变化递增聚合修订和更新时间。
     */
    @Update("UPDATE iam_user SET version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int touch(long id);

    /**
     * 仅在认证版本仍匹配且尚未登录时记录首次认证事实。
     */
    @Update(
            "UPDATE iam_user SET first_login_at=UTC_TIMESTAMP(3),version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND authentication_version=#{authenticationVersion} AND first_login_at IS NULL")
    int firstLogin(@Param("id") long id, @Param("authenticationVersion") long authenticationVersion);

    /**
     * 在原子凭据变更中写入安全散列并递增凭据修订。
     */
    @Update(
            "UPDATE iam_user_credential SET password_hash=#{hash},credential_revision=credential_revision+1,changed_at=UTC_TIMESTAMP(3) WHERE user_id=#{id}")
    int password(@Param("id") long id, @Param("hash") String hash);

    /**
     * 递增认证修订并设置必要改密标记，使所有旧版本会话失效。
     */
    @Update(
            "UPDATE iam_user SET must_change_password=#{mustChange},authentication_version=authentication_version+1,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int invalidate(@Param("id") long id, @Param("mustChange") boolean mustChange);

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
