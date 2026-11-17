package com.dataocean.module.iam.mapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.iam.entity.dto.NewUserProfileDTO;
import com.dataocean.module.iam.entity.dto.UpdateContactDTO;
import com.dataocean.module.iam.entity.dto.UpdateUserDTO;
import com.dataocean.module.iam.entity.po.CredentialPO;
import com.dataocean.module.iam.entity.po.UserPO;
import com.dataocean.module.iam.entity.query.PersonQuery;


public interface UserMapper {
    String FILTER = """
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
    @Select("<script>SELECT u.* FROM iam_user u "+FILTER+"</script>")
    Page<UserPO> page(Page<UserPO> page, @Param("q") PersonQuery query, @Param("departments") List<Long> departments, @Param("roleId") Long roleId);
    @Select("SELECT * FROM iam_user WHERE id=#{id}") UserPO find(long id);
    @Select("SELECT * FROM iam_user WHERE id=#{id} FOR UPDATE") UserPO lock(long id);
    @Select("SELECT * FROM iam_user WHERE username=#{username}") UserPO byUsername(String username);
    @Select("SELECT * FROM iam_user_credential WHERE user_id=#{id}") CredentialPO credential(long id);
    @Select("SELECT COUNT(*) FROM iam_user") int count();
    @Select("SELECT * FROM iam_user ORDER BY id") List<UserPO> all();
    @Insert("INSERT INTO iam_user(username,display_name,phone,email,management_note,created_by,created_at,updated_at) VALUES(#{d.username},#{d.displayName},#{d.phone},#{d.email},#{d.managementNote},#{actor},UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))")
    int insert(@Param("d") NewUserProfileDTO data,@Param("actor") Long actor);
    @Insert("INSERT INTO iam_user_credential(user_id,password_hash,changed_at) VALUES(#{id},#{hash},UTC_TIMESTAMP(3))")
    int insertCredential(@Param("id") long id,@Param("hash") String hash);
    @Update("UPDATE iam_user SET display_name=#{d.displayName},phone=#{d.phone},email=#{d.email},management_note=#{d.managementNote},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND version=#{d.expectedVersion}")
    int profile(@Param("id") long id,@Param("d") UpdateUserDTO data);
    @Update("UPDATE iam_user SET phone=#{d.phone},email=#{d.email},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND version=#{d.expectedVersion}")
    int contact(@Param("id") long id,@Param("d") UpdateContactDTO data);
    @Update("UPDATE iam_user SET version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}") int touch(long id);
    @Update("UPDATE iam_user SET first_login_at=UTC_TIMESTAMP(3),version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND authentication_version=#{authenticationVersion} AND first_login_at IS NULL")
    int firstLogin(@Param("id") long id,@Param("authenticationVersion") long authenticationVersion);
    @Update("UPDATE iam_user_credential SET password_hash=#{hash},credential_revision=credential_revision+1,changed_at=UTC_TIMESTAMP(3) WHERE user_id=#{id}")
    int password(@Param("id") long id,@Param("hash") String hash);
    @Update("UPDATE iam_user SET must_change_password=#{mustChange},authentication_version=authentication_version+1,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int invalidate(@Param("id") long id,@Param("mustChange") boolean mustChange);
    @Update("UPDATE iam_user SET lifecycle=#{lifecycle},status_reason=#{reason},version=version+1,authentication_version=authentication_version+1,disabled_at=CASE WHEN #{lifecycle}='DISABLED' THEN UTC_TIMESTAMP(3) ELSE disabled_at END,archived_at=CASE WHEN #{lifecycle}='ARCHIVED' THEN UTC_TIMESTAMP(3) ELSE archived_at END,archived_affiliation=COALESCE(#{snapshot},archived_affiliation),updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int lifecycle(@Param("id") long id,@Param("lifecycle") String lifecycle,@Param("reason") String reason,@Param("snapshot") String snapshot);
}
