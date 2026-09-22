package com.dataocean.module.permission.s1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.permission.s1.entity.IamS1Role;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** IAM-SIMPLE-1 角色和同一绑定判定 Mapper。 */
@Mapper
public interface IamS1RoleMapper extends BaseMapper<IamS1Role> {

    @Select("""
            SELECT COUNT(*)
            FROM iam_s1_role
            WHERE status = 1
              AND protected_role = 1
              AND built_in = 1
            """)
    long countActiveProtectedRoles();

    @Select("""
            SELECT COUNT(*)
            FROM iam_s1_user_role ur
            JOIN iam_s1_role r ON r.id = ur.role_id
            WHERE ur.user_id = #{userId}
              AND ur.status = 1
              AND r.status = 1
              AND r.protected_role = 1
              AND r.built_in = 1
            """)
    long countActiveProtectedBindings(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM iam_s1_user_role ur
            JOIN iam_s1_role r ON r.id = ur.role_id
            WHERE ur.status = 1
              AND r.status = 1
              AND r.protected_role = 1
              AND r.built_in = 1
            """)
    long countAllActiveProtectedBindings();

    /**
     * 查询有效 S1 系统管理员账号。用户状态和逻辑删除状态属于账号身份事实，
     * 不通过旧角色表回退；DISTINCT 防止异常重复绑定造成重复通知。
     */
    @Select("""
            SELECT DISTINCT ur.user_id
            FROM iam_s1_user_role ur
            JOIN iam_s1_role r ON r.id = ur.role_id
            JOIN sys_user u ON u.id = ur.user_id
            WHERE ur.status = 1
              AND r.status = 1
              AND r.protected_role = 1
              AND r.built_in = 1
              AND u.status = 1
              AND u.deleted = 0
            ORDER BY ur.user_id
            """)
    List<Long> selectActiveProtectedAdminUserIds();

    @Select("""
            SELECT COUNT(*)
            FROM iam_s1_user_role ur
            JOIN iam_s1_role r ON r.id = ur.role_id
            WHERE ur.user_id = #{userId}
              AND ur.status = 1
              AND r.status = 1
            """)
    long countActiveUserRoleBindings(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM iam_s1_user_role ur
            JOIN iam_s1_role r ON r.id = ur.role_id
            JOIN iam_s1_role_function rf ON rf.role_id = r.id
            JOIN iam_s1_function f ON f.id = rf.function_id
            WHERE ur.user_id = #{userId}
              AND ur.status = 1
              AND r.status = 1
              AND f.status = 'ACTIVE'
              AND f.function_code = #{functionCode}
            """)
    long countActiveUserFunction(@Param("userId") Long userId, @Param("functionCode") String functionCode);

    @Select("""
            SELECT COUNT(*)
            FROM iam_s1_user_role ur
            JOIN iam_s1_role r ON r.id = ur.role_id
            JOIN iam_s1_role_datasource rd ON rd.user_role_id = ur.id
            WHERE ur.user_id = #{userId}
              AND ur.status = 1
              AND r.status = 1
              AND rd.status = 1
              AND rd.datasource_id = #{datasourceId}
            """)
    long countActiveUserDatasource(@Param("userId") Long userId, @Param("datasourceId") Long datasourceId);

    /**
     * 功能与负责源必须在同一个用户角色绑定上，防止角色交叉相乘。
     */
    @Select("""
            SELECT COUNT(*)
            FROM iam_s1_user_role ur
            JOIN iam_s1_role r ON r.id = ur.role_id
            JOIN iam_s1_role_function rf ON rf.role_id = r.id
            JOIN iam_s1_function f ON f.id = rf.function_id
            JOIN iam_s1_role_datasource rd ON rd.user_role_id = ur.id
            WHERE ur.user_id = #{userId}
              AND ur.status = 1
              AND r.status = 1
              AND f.status = 'ACTIVE'
              AND f.function_code = #{functionCode}
              AND rd.status = 1
              AND rd.datasource_id = #{datasourceId}
            """)
    long countActiveUserFunctionDatasource(@Param("userId") Long userId,
                                           @Param("functionCode") String functionCode,
                                           @Param("datasourceId") Long datasourceId);

    @Select("""
            SELECT f.function_code
            FROM iam_s1_role_function rf
            JOIN iam_s1_function f ON f.id = rf.function_id
            WHERE rf.role_id = #{roleId}
              AND f.status = 'ACTIVE'
            ORDER BY f.id
            """)
    List<String> selectFunctionCodesByRoleId(@Param("roleId") Long roleId);

    /** 锁定受保护角色，供最后一个系统管理员变更的并发校验使用。 */
    @Select("""
            SELECT id, role_code, role_name, description, status,
                   protected_role, built_in, created_by, updated_by,
                   created_at, updated_at
            FROM iam_s1_role
            WHERE status = 1
              AND protected_role = 1
              AND built_in = 1
            LIMIT 1
            FOR UPDATE
            """)
    IamS1Role selectActiveProtectedRoleForUpdate();
}
