package com.dataocean.module.iam.mapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.iam.entity.po.MembershipPO;


public interface MembershipMapper {
    @Select("SELECT * FROM iam_user_department WHERE user_id=#{id} AND left_at IS NULL ORDER BY is_primary DESC,id") List<MembershipPO> forUser(long id);
    @Select("SELECT * FROM iam_user_department WHERE department_id=#{id} AND left_at IS NULL ORDER BY user_id") List<MembershipPO> forDepartment(long id);
    @Select("SELECT * FROM iam_user_department WHERE id=#{id} AND left_at IS NULL") MembershipPO find(long id);
    @Insert("INSERT INTO iam_user_department(user_id,department_id,is_primary,joined_by,joined_at) VALUES(#{user},#{department},#{primary},#{actor},UTC_TIMESTAMP(3))")
    int add(@Param("user") long user,@Param("department") long department,@Param("primary") boolean primary,@Param("actor") long actor);
    @Update("UPDATE iam_user_department SET left_by=#{actor},left_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND left_at IS NULL")
    int end(@Param("id") long id,@Param("actor") long actor);
    @Update("UPDATE iam_user_department SET left_by=#{actor},left_at=UTC_TIMESTAMP(3) WHERE user_id=#{user} AND left_at IS NULL")
    int endUser(@Param("user") long user,@Param("actor") long actor);
}
