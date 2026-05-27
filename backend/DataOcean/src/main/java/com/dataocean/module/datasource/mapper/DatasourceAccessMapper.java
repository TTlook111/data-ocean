package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.DatasourceAccess;
import com.dataocean.module.datasource.entity.vo.DatasourceAccessVO;
import com.dataocean.module.permission.entity.vo.DatasourcePermissionVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 数据源访问授权 Mapper 接口
 * <p>
 * 提供对 datasource_access 表的基础 CRUD 操作，
 * 以及自定义的授权列表查询和有效授权计数方法。
 * </p>
 *
 * @author dataocean
 */
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
@Mapper
public interface DatasourceAccessMapper extends BaseMapper<DatasourceAccess> {

    /**
     * 查询指定数据源的授权用户列表（兼容旧接口）
     */
    @Select("""
            SELECT a.id,
                   a.datasource_id AS datasourceId,
                   a.subject_id AS userId,
                   u.username,
                   u.real_name AS realName,
                   a.granted_by AS grantedBy,
                   a.granted_at AS grantedAt,
                   a.expires_at AS expiresAt
            FROM datasource_access a
            LEFT JOIN sys_user u ON u.id = a.subject_id
            WHERE a.datasource_id = #{datasourceId}
              AND a.subject_type = 'USER'
            ORDER BY a.granted_at DESC, a.id DESC
            """)
    List<DatasourceAccessVO> selectAccessList(@Param("datasourceId") Long datasourceId);

    /**
     * 统计用户对指定数据源的有效授权数量（兼容旧接口）
     */
    @Select("""
            SELECT COUNT(*)
            FROM datasource_access a
            JOIN datasource d ON d.id = a.datasource_id
            WHERE a.datasource_id = #{datasourceId}
              AND a.subject_id = #{userId}
              AND a.subject_type = 'USER'
              AND d.status = 1
              AND d.deleted = 0
              AND (a.expires_at IS NULL OR a.expires_at > NOW())
            """)
    Long countEnabledAccess(@Param("datasourceId") Long datasourceId, @Param("userId") Long userId);

    /**
     * 按主体类型和ID统计有效授权数量（支持多维度权限检查）
     *
     * @param datasourceId 数据源 ID
     * @param subjectType  主体类型
     * @param subjectId    主体 ID
     * @return 有效授权记录数
     */
    @Select("""
            SELECT COUNT(*)
            FROM datasource_access a
            JOIN datasource d ON d.id = a.datasource_id
            WHERE a.datasource_id = #{datasourceId}
              AND a.subject_type = #{subjectType}
              AND a.subject_id = #{subjectId}
              AND a.can_query = 1
              AND d.status = 1
              AND d.deleted = 0
              AND (a.expires_at IS NULL OR a.expires_at > NOW())
            """)
    Long countValidAccess(@Param("datasourceId") Long datasourceId,
                          @Param("subjectType") String subjectType,
                          @Param("subjectId") Long subjectId);

    /**
     * 查询指定数据源的全部授权列表（带主体名称，支持按类型过滤）
     *
     * @param datasourceId 数据源 ID
     * @param subjectType  主体类型（可选）
     * @return 授权视图列表
     */
    @Select("""
            <script>
            SELECT a.id, a.datasource_id AS datasourceId,
                   a.subject_type AS subjectType, a.subject_id AS subjectId,
                   a.can_query AS canQuery, a.can_export AS canExport,
                   a.can_view_sql AS canViewSql,
                   a.granted_at AS grantedAt, a.expires_at AS expiresAt,
                   CASE a.subject_type
                       WHEN 'USER' THEN (SELECT real_name FROM sys_user WHERE id = a.subject_id)
                       WHEN 'ROLE' THEN (SELECT role_name FROM sys_role WHERE id = a.subject_id)
                       WHEN 'DEPARTMENT' THEN (SELECT dept_name FROM sys_department WHERE id = a.subject_id)
                   END AS subjectName
            FROM datasource_access a
            WHERE a.datasource_id = #{datasourceId}
            <if test="subjectType != null and subjectType != ''">
                AND a.subject_type = #{subjectType}
            </if>
            ORDER BY a.subject_type, a.subject_id
            </script>
            """)
    List<DatasourcePermissionVO> selectPermissionList(@Param("datasourceId") Long datasourceId,
                                                      @Param("subjectType") String subjectType);

    /**
     * 查询指定主体对指定数据源的授权记录
     */
    @Select("""
            SELECT * FROM datasource_access
            WHERE datasource_id = #{datasourceId}
              AND subject_type = #{subjectType}
              AND subject_id = #{subjectId}
              AND (expires_at IS NULL OR expires_at > NOW())
            """)
    List<DatasourceAccess> selectBySubject(@Param("datasourceId") Long datasourceId,
                                           @Param("subjectType") String subjectType,
                                           @Param("subjectId") Long subjectId);
}
