package com.dataocean.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.audit.entity.po.QueryLineageTable;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 查询血缘-表级关系 Mapper 接口
 */
@Mapper
public interface QueryLineageTableMapper extends BaseMapper<QueryLineageTable> {

    @Select("""
            SELECT l.*
            FROM query_lineage_table l
            JOIN query_task t ON t.id = l.query_task_id
            WHERE LOWER(l.source_table) = LOWER(#{tableName})
              AND t.datasource_id = #{datasourceId}
              AND t.status = 'COMPLETED' AND t.iam_protocol_version = 'IAM-SIMPLE-1'
              AND t.trace_projection_status = 'COMPLETED'
              AND t.iam_final_protection_status IN ('FINAL_PROTECTED','FINAL_MASKED')
            ORDER BY l.created_at DESC
            LIMIT #{limit}
            """)
    List<QueryLineageTable> selectByTableAndDatasource(@Param("tableName") String tableName,
                                                       @Param("datasourceId") Long datasourceId,
                                                       @Param("limit") int limit);

    @Select("""
            SELECT COUNT(DISTINCT l.query_task_id) FROM query_lineage_table l
            JOIN query_task t ON t.id = l.query_task_id
            WHERE LOWER(l.source_table) = LOWER(#{tableName}) AND t.datasource_id = #{datasourceId}
              AND t.status = 'COMPLETED' AND t.iam_protocol_version = 'IAM-SIMPLE-1'
              AND t.trace_projection_status = 'COMPLETED'
              AND t.iam_final_protection_status IN ('FINAL_PROTECTED','FINAL_MASKED')
            """)
    Long countByTableAndDatasource(@Param("tableName") String tableName, @Param("datasourceId") Long datasourceId);

    @Select("""
            SELECT MIN(t.completed_at) FROM query_task t
            WHERE t.datasource_id = #{datasourceId} AND t.status = 'COMPLETED'
              AND t.iam_protocol_version = 'IAM-SIMPLE-1' AND t.trace_projection_status = 'COMPLETED'
              AND t.iam_final_protection_status IN ('FINAL_PROTECTED','FINAL_MASKED')
            """)
    java.time.LocalDateTime coverageStart(@Param("datasourceId") Long datasourceId);
}
