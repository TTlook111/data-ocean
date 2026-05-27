package com.dataocean.module.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.permission.entity.DatasourceAccessPolicy;
import com.dataocean.module.permission.entity.vo.AccessPolicyVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 数据源行列级策略 Mapper 接口
 * <p>
 * 提供对 datasource_access_policy 表的基础 CRUD 操作，
 * 以及按主体维度查询策略列表的自定义方法。
 * </p>
 *
 * @author dataocean
 */
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
@Mapper
public interface DatasourceAccessPolicyMapper extends BaseMapper<DatasourceAccessPolicy> {

    /**
     * 查询指定数据源和主体的策略列表（带主体名称）
     *
     * @param datasourceId 数据源 ID
     * @param subjectType  主体类型（可选）
     * @param subjectId    主体 ID（可选）
     * @param tableName    表名（可选）
     * @return 策略视图对象列表
     */
    @Select("""
            <script>
            SELECT p.id, p.datasource_id AS datasourceId,
                   p.subject_type AS subjectType, p.subject_id AS subjectId,
                   p.table_name AS tableName, p.column_name AS columnName,
                   p.access_type AS accessType, p.mask_strategy AS maskStrategy,
                   p.row_filter_expression AS rowFilterExpression,
                   p.created_at AS createdAt,
                   CASE p.subject_type
                       WHEN 'USER' THEN (SELECT real_name FROM sys_user WHERE id = p.subject_id)
                       WHEN 'ROLE' THEN (SELECT role_name FROM sys_role WHERE id = p.subject_id)
                       WHEN 'DEPARTMENT' THEN (SELECT dept_name FROM sys_department WHERE id = p.subject_id)
                   END AS subjectName
            FROM datasource_access_policy p
            WHERE p.datasource_id = #{datasourceId}
            <if test="subjectType != null and subjectType != ''">
                AND p.subject_type = #{subjectType}
            </if>
            <if test="subjectId != null">
                AND p.subject_id = #{subjectId}
            </if>
            <if test="tableName != null and tableName != ''">
                AND p.table_name = #{tableName}
            </if>
            ORDER BY p.table_name, p.column_name, p.id
            </script>
            """)
    List<AccessPolicyVO> selectPolicies(@Param("datasourceId") Long datasourceId,
                                        @Param("subjectType") String subjectType,
                                        @Param("subjectId") Long subjectId,
                                        @Param("tableName") String tableName);

    /**
     * 查询指定数据源下某主体的所有策略（用于权限计算）
     *
     * @param datasourceId 数据源 ID
     * @param subjectType  主体类型
     * @param subjectId    主体 ID
     * @return 策略实体列表
     */
    @Select("""
            SELECT * FROM datasource_access_policy
            WHERE datasource_id = #{datasourceId}
              AND subject_type = #{subjectType}
              AND subject_id = #{subjectId}
            """)
    List<DatasourceAccessPolicy> selectBySubject(@Param("datasourceId") Long datasourceId,
                                                  @Param("subjectType") String subjectType,
                                                  @Param("subjectId") Long subjectId);
}
