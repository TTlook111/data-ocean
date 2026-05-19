package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.vo.DatasourceSimpleVO;
import com.dataocean.module.datasource.entity.vo.DatasourceVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DatasourceMapper extends BaseMapper<Datasource> {

    @Select("""
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE LOWER(table_schema) = LOWER(DATABASE())
              AND table_name = #{tableName}
            """)
    Long tableExists(@Param("tableName") String tableName);

    @Select("""
            SELECT COUNT(*)
            FROM metadata_snapshot
            WHERE datasource_id = #{datasourceId}
              AND status = 'PUBLISHED'
            """)
    Long countPublishedSnapshots(@Param("datasourceId") Long datasourceId);

    @Select("""
            SELECT COUNT(*)
            FROM knowledge_doc
            WHERE datasource_id = #{datasourceId}
              AND status IN ('APPROVED', 'PUBLISHED')
              AND deleted = 0
            """)
    Long countActiveKnowledgeDocs(@Param("datasourceId") Long datasourceId);

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

    @Select("""
            SELECT d.id,
                   d.name,
                   d.description,
                   d.database_name AS databaseName
            FROM datasource d
            JOIN datasource_access a ON a.datasource_id = d.id
            WHERE a.user_id = #{userId}
              AND d.status = 1
              AND d.deleted = 0
              AND (a.expires_at IS NULL OR a.expires_at > NOW())
            ORDER BY d.name ASC, d.id ASC
            """)
    List<DatasourceSimpleVO> selectAccessibleByUserId(@Param("userId") Long userId);
}
