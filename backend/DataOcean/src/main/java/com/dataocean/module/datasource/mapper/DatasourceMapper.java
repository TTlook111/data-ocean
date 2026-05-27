package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.vo.DatasourceSimpleVO;
import com.dataocean.module.datasource.entity.vo.DatasourceVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

import java.util.List;

/**
 * 数据源 Mapper 接口。
 * <p>
 * 提供 datasource 表基础 CRUD，以及数据源详情、授权可见数据源和依赖资源统计查询。
 * </p>
 */
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
@Mapper
public interface DatasourceMapper extends BaseMapper<Datasource> {

    /**
     * 检查当前平台库中指定表是否存在。
     *
     * @param tableName 表名
     * @return 匹配表数量
     */
    @Select("""
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE LOWER(table_schema) = LOWER(DATABASE())
              AND table_name = #{tableName}
            """)
    Long tableExists(@Param("tableName") String tableName);

    /**
     * 统计数据源已发布快照数量。
     *
     * @param datasourceId 数据源 ID
     * @return 已发布快照数量
     */
    @Select("""
            SELECT COUNT(*)
            FROM metadata_snapshot
            WHERE datasource_id = #{datasourceId}
              AND status = 'PUBLISHED'
            """)
    Long countPublishedSnapshots(@Param("datasourceId") Long datasourceId);

    /**
     * 统计数据源下可用的知识文档数量。
     *
     * @param datasourceId 数据源 ID
     * @return 已审核或已发布且未删除的知识文档数量
     */
    @Select("""
            SELECT COUNT(*)
            FROM knowledge_doc
            WHERE datasource_id = #{datasourceId}
              AND status IN ('APPROVED', 'PUBLISHED')
              AND deleted = 0
            """)
    Long countActiveKnowledgeDocs(@Param("datasourceId") Long datasourceId);

    /**
     * 查询数据源详情视图。
     *
     * @param id 数据源 ID
     * @return 数据源详情视图，数据源不存在时返回 null
     */
    @Select("""
            SELECT d.id,
                   d.name,
                   d.description,
                   d.db_type AS dbType,
                   d.host,
                   d.port,
                   d.database_name AS databaseName,
                   d.charset,
                   d.status,
                   d.health_status AS healthStatus,
                   s.username,
                   u.real_name AS creatorName,
                   hc.success AS lastCheckSuccess,
                   hc.checked_at AS lastCheckTime,
                   d.created_at AS createdAt
            FROM datasource d
            LEFT JOIN datasource_secret s ON s.datasource_id = d.id
            LEFT JOIN sys_user u ON u.id = d.creator_id
            LEFT JOIN datasource_health_check hc ON hc.id = (
                SELECT h.id
                FROM datasource_health_check h
                WHERE h.datasource_id = d.id
                ORDER BY h.checked_at DESC, h.id DESC
                LIMIT 1
            )
            WHERE d.id = #{id}
              AND d.deleted = 0
            """)
    DatasourceVO selectVOById(@Param("id") Long id);

    /**
     * 批量查询数据源详情视图（避免 N+1 查询）。
     *
     * @param ids 数据源 ID 列表
     * @return 数据源详情视图列表
     */
    @Select("""
            <script>
            SELECT d.id,
                   d.name,
                   d.description,
                   d.db_type AS dbType,
                   d.host,
                   d.port,
                   d.database_name AS databaseName,
                   d.charset,
                   d.status,
                   d.health_status AS healthStatus,
                   s.username,
                   u.real_name AS creatorName,
                   hc.success AS lastCheckSuccess,
                   hc.checked_at AS lastCheckTime,
                   d.created_at AS createdAt
            FROM datasource d
            LEFT JOIN datasource_secret s ON s.datasource_id = d.id
            LEFT JOIN sys_user u ON u.id = d.creator_id
            LEFT JOIN datasource_health_check hc ON hc.id = (
                SELECT h.id
                FROM datasource_health_check h
                WHERE h.datasource_id = d.id
                ORDER BY h.checked_at DESC, h.id DESC
                LIMIT 1
            )
            WHERE d.deleted = 0
              AND d.id IN
              <foreach item="id" collection="ids" open="(" separator="," close=")">
                #{id}
              </foreach>
            </script>
            """)
    List<DatasourceVO> selectVOByIds(@Param("ids") List<Long> ids);

    /**
     * 查询指定用户已授权访问的启用数据源。
     *
     * @param userId 用户 ID
     * @return 用户可访问的数据源简要列表
     */
    @Select("""
            SELECT d.id,
                   d.name,
                   d.description,
                   d.database_name AS databaseName
            FROM datasource d
            JOIN datasource_access a ON a.datasource_id = d.id
            WHERE a.subject_id = #{userId}
              AND a.subject_type = 'USER'
              AND d.status = 1
              AND d.deleted = 0
              AND (a.expires_at IS NULL OR a.expires_at > NOW())
            ORDER BY d.name, d.id
            """)
    List<DatasourceSimpleVO> selectAccessibleByUserId(@Param("userId") Long userId);

    /**
     * 查询用户可访问的数据源（合并用户直接授权 + 角色授权 + 部门授权）
     *
     * @param userId    用户 ID
     * @param roleIds   用户角色 ID 列表
     * @param deptId    用户部门 ID（可为 null）
     * @return 去重后的可访问数据源列表
     */
    @Select("""
            <script>
            SELECT DISTINCT d.id, d.name, d.description, d.database_name AS databaseName
            FROM datasource d
            JOIN datasource_access a ON a.datasource_id = d.id
            WHERE d.status = 1
              AND d.deleted = 0
              AND a.can_query = 1
              AND (a.expires_at IS NULL OR a.expires_at > NOW())
              AND (
                  (a.subject_type = 'USER' AND a.subject_id = #{userId})
                  <if test="roleIds != null and roleIds.size() > 0">
                  OR (a.subject_type = 'ROLE' AND a.subject_id IN
                      <foreach collection="roleIds" item="rid" open="(" separator="," close=")">#{rid}</foreach>)
                  </if>
                  <if test="deptId != null">
                  OR (a.subject_type = 'DEPARTMENT' AND a.subject_id = #{deptId})
                  </if>
              )
            ORDER BY d.name, d.id
            </script>
            """)
    List<DatasourceSimpleVO> selectAccessibleMultiDimension(@Param("userId") Long userId,
                                                            @Param("roleIds") List<Long> roleIds,
                                                            @Param("deptId") Long deptId);

    /**
     * 查询所有启用且未删除的数据源简要信息。
     *
     * @return 启用数据源简要列表
     */
    @Select("""
            SELECT d.id,
                   d.name,
                   d.description,
                   d.database_name AS databaseName
            FROM datasource d
            WHERE d.status = 1
              AND d.deleted = 0
            ORDER BY d.name, d.id
            """)
    List<DatasourceSimpleVO> selectEnabledSimple();
}
