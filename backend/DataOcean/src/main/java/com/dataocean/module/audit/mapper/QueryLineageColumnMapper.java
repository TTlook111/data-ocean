package com.dataocean.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.audit.entity.QueryLineageColumn;
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
            WHERE l.source_table = #{tableName}
              AND l.source_column = #{columnName}
              AND t.datasource_id = #{datasourceId}
            ORDER BY l.created_at DESC
            LIMIT #{limit}
            """)
    List<QueryLineageColumn> selectByColumnAndDatasource(@Param("tableName") String tableName,
                                                         @Param("columnName") String columnName,
                                                         @Param("datasourceId") Long datasourceId,
                                                         @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM query_lineage_column l
            JOIN query_task t ON t.id = l.query_task_id
            WHERE l.source_table = #{tableName}
              AND l.source_column = #{columnName}
              AND t.datasource_id = #{datasourceId}
            """)
    Long countByColumnAndDatasource(@Param("tableName") String tableName,
                                    @Param("columnName") String columnName,
                                    @Param("datasourceId") Long datasourceId);
}
