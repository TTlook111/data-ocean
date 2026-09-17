package com.dataocean.module.permission.s1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.permission.s1.entity.IamS1UserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** IAM-SIMPLE-1 用户角色绑定 Mapper。 */
@Mapper
public interface IamS1UserRoleMapper extends BaseMapper<IamS1UserRole> {

    @Select("""
            SELECT id, user_id, role_id, status, revision_no,
                   created_by, updated_by, created_at, updated_at
            FROM iam_s1_user_role
            WHERE user_id = #{userId}
              AND role_id = #{roleId}
            """)
    IamS1UserRole selectByUserAndRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    @Select("""
            SELECT id, user_id, role_id, status, revision_no,
                   created_by, updated_by, created_at, updated_at
            FROM iam_s1_user_role
            WHERE user_id = #{userId}
              AND status = 1
            ORDER BY id
            """)
    List<IamS1UserRole> selectActiveByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM iam_s1_user_role
            WHERE user_id = #{userId}
              AND role_id = #{roleId}
              AND status = 1
            """)
    long countActiveByUserAndRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    /** 锁定全部有效系统管理员绑定，防止并发移除最后一个管理员。 */
    @Select("""
            SELECT ur.id, ur.user_id, ur.role_id, ur.status, ur.revision_no,
                   ur.created_by, ur.updated_by, ur.created_at, ur.updated_at
            FROM iam_s1_user_role ur
            JOIN iam_s1_role r ON r.id = ur.role_id
            WHERE ur.status = 1
              AND r.status = 1
              AND r.protected_role = 1
              AND r.built_in = 1
            ORDER BY ur.id
            FOR UPDATE
            """)
    List<IamS1UserRole> selectActiveProtectedBindingsForUpdate();
}
