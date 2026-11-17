package com.dataocean.module.iam.mapper;

import com.dataocean.module.iam.entity.po.MembershipPO;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 公共契约的持久化适配，业务状态和权限由所属服务核验。
 */
public interface MembershipMapper {

    /**
     * 一次读取当前关系，用于组织树各层去重统计，避免逐节点查询。
     */
    @Select("SELECT * FROM iam_user_department WHERE left_at IS NULL ORDER BY department_id,user_id")
    List<MembershipPO> current();

    /**
     * 读取指定人员尚未结束的主兼任关系。
     */
    @Select("SELECT * FROM iam_user_department WHERE user_id=#{id} AND left_at IS NULL ORDER BY is_primary DESC,id")
    List<MembershipPO> forUser(long id);

    /**
     * 读取指定部门尚未结束的当前成员关系。
     */
    @Select("SELECT * FROM iam_user_department WHERE department_id=#{id} AND left_at IS NULL ORDER BY user_id")
    List<MembershipPO> forDepartment(long id);

    /**
     * 按稳定标识读取当前记录，由所属服务继续校验可见性和状态。
     */
    @Select("SELECT * FROM iam_user_department WHERE id=#{id} AND left_at IS NULL")
    MembershipPO find(long id);

    /**
     * 建立一条明确主兼任性质的当前成员关系。
     */
    @Insert(
            "INSERT INTO iam_user_department(user_id,department_id,is_primary,joined_by,joined_at) VALUES(#{user},#{department},#{primary},#{actor},UTC_TIMESTAMP(3))")
    int add(
            @Param("user") long user,
            @Param("department") long department,
            @Param("primary") boolean primary,
            @Param("actor") long actor);

    /**
     * 结束当前成员关系并保留真实结束人员和时间。
     */
    @Update(
            "UPDATE iam_user_department SET left_by=#{actor},left_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND left_at IS NULL")
    int end(@Param("id") long id, @Param("actor") long actor);

    /**
     * 结束人员当前关联关系，用于归档事务而非历史硬删除。
     */
    @Update(
            "UPDATE iam_user_department SET left_by=#{actor},left_at=UTC_TIMESTAMP(3) WHERE user_id=#{user} AND left_at IS NULL")
    int endUser(@Param("user") long user, @Param("actor") long actor);
}
