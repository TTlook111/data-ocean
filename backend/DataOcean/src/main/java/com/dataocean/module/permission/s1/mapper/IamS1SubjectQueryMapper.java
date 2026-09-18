package com.dataocean.module.permission.s1.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * S1 表单选择对象的最小信息读取。
 * <p>
 * 只返回必要名称，不返回密码、联系方式或其他认证秘密；不读取任何旧权限关系。
 * </p>
 */
@Mapper
public interface IamS1SubjectQueryMapper {

    @Select("""
            SELECT id, username, real_name
            FROM sys_user
            WHERE deleted = 0
              AND status = 1
              AND (#{keyword} IS NULL OR #{keyword} = ''
                   OR real_name LIKE CONCAT('%', #{keyword}, '%')
                   OR username LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY id
            LIMIT #{limit}
            """)
    List<Map<String, Object>> searchEnabledUsers(@Param("keyword") String keyword,
                                                 @Param("limit") int limit);

    @Select("""
            SELECT id, dept_name, parent_id
            FROM sys_department
            WHERE status = 1
            ORDER BY sort_order, id
            """)
    List<Map<String, Object>> selectEnabledDepartments();

    @Select("""
            SELECT id, real_name, username
            FROM sys_user
            WHERE id = #{userId}
              AND deleted = 0
            """)
    Map<String, Object> selectUserBrief(@Param("userId") Long userId);

    @Select("""
            SELECT id, dept_name
            FROM sys_department
            WHERE id = #{departmentId}
            """)
    Map<String, Object> selectDepartmentBrief(@Param("departmentId") Long departmentId);
}
