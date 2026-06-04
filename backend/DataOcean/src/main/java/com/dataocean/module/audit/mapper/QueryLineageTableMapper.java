package com.dataocean.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.audit.entity.QueryLineageTable;
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
            WHERE l.source_table = #{tableName}
              AND t.datasource_id = #{datasourceId}
            ORDER BY l.created_at DESC
            LIMIT #{limit}
            """)
    List<QueryLineageTable> selectByTableAndDatasource(@Param("tableName") String tableName,
                                                       @Param("datasourceId") Long datasourceId,
                                                       @Param("limit") int limit);
}
