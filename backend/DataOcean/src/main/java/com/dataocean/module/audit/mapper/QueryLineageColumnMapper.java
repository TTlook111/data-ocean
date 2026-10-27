package com.dataocean.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.audit.entity.po.QueryLineageColumn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 查询血缘-字段级关系 Mapper 接口
 */
@Mapper
public interface QueryLineageColumnMapper extends BaseMapper<QueryLineageColumn> {

    @Select("""
            SELECT l.*
            FROM query_lineage_column l
            JOIN query_task t ON t.id = l.query_task_id
            WHERE LOWER(l.source_table) = LOWER(#{tableName})
              AND LOWER(l.source_column) = LOWER(#{columnName})
              AND t.datasource_id = #{datasourceId}
              AND t.status = 'COMPLETED' AND t.iam_protocol_version = 'IAM-SIMPLE-1'
              AND t.trace_projection_status = 'COMPLETED'
              AND t.iam_final_protection_status IN ('FINAL_PROTECTED','FINAL_MASKED')
            ORDER BY l.created_at DESC
            LIMIT #{limit}
            """)
    List<QueryLineageColumn> selectByColumnAndDatasource(@Param("tableName") String tableName,
                                                         @Param("columnName") String columnName,
                                                         @Param("datasourceId") Long datasourceId,
                                                         @Param("limit") int limit);

    @Select("""
            SELECT COUNT(DISTINCT l.query_task_id)
            FROM query_lineage_column l
            JOIN query_task t ON t.id = l.query_task_id
            WHERE LOWER(l.source_table) = LOWER(#{tableName})
              AND LOWER(l.source_column) = LOWER(#{columnName})
              AND t.datasource_id = #{datasourceId}
              AND t.status = 'COMPLETED' AND t.iam_protocol_version = 'IAM-SIMPLE-1'
              AND t.trace_projection_status = 'COMPLETED'
              AND t.iam_final_protection_status IN ('FINAL_PROTECTED','FINAL_MASKED')
            """)
    Long countByColumnAndDatasource(@Param("tableName") String tableName,
                                    @Param("columnName") String columnName,
                                    @Param("datasourceId") Long datasourceId);

    @Select("""
            SELECT l.query_task_id FROM query_lineage_column l JOIN query_task t ON t.id = l.query_task_id
            WHERE LOWER(l.source_table) = LOWER(#{tableName}) AND LOWER(l.source_column) = LOWER(#{columnName})
              AND t.datasource_id = #{datasourceId}
              AND t.status = 'COMPLETED' AND t.iam_protocol_version = 'IAM-SIMPLE-1'
              AND t.trace_projection_status = 'COMPLETED'
              AND t.iam_final_protection_status IN ('FINAL_PROTECTED','FINAL_MASKED')
            GROUP BY l.query_task_id ORDER BY MAX(l.created_at) DESC, l.query_task_id DESC LIMIT 10
            """)
    List<Long> recentTasks(@Param("tableName") String tableName, @Param("columnName") String columnName, @Param("datasourceId") Long datasourceId);
}
