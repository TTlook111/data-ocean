package com.dataocean.module.glossary.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.glossary.entity.vo.TermColumnVO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface GlossaryFieldMapper {
    @Select("""
        <script>
        SELECT e.id AS entity_id, c.datasource_id, d.name AS datasource_name, c.snapshot_id,
               t.id AS table_id, c.id AS column_id, c.table_name, c.column_name,
               c.data_type AS column_type, c.column_comment AS description, e.fqn
        FROM db_column_meta c JOIN db_table_meta t ON t.id=c.table_meta_id
        JOIN datasource d ON d.id=c.datasource_id AND d.deleted=0
        JOIN metadata_entity e ON e.entity_type='COLUMN'
          AND CAST(LOWER(e.fqn) AS BINARY)=CAST(LOWER(CONCAT(d.name,'.',d.database_name,'.',c.table_name,'.',c.column_name)) AS BINARY)
          AND CAST(JSON_UNQUOTE(JSON_EXTRACT(e.entity_metadata,'$.snapshot_id')) AS UNSIGNED)=c.snapshot_id
          AND CAST(JSON_UNQUOTE(JSON_EXTRACT(e.entity_metadata,'$.datasource_id')) AS UNSIGNED)=c.datasource_id
        WHERE c.datasource_id=#{datasourceId} AND c.snapshot_id=#{snapshotId}
          AND t.datasource_id=c.datasource_id AND t.snapshot_id=c.snapshot_id
          AND c.governance_status NOT IN ('BLOCKED','DEPRECATED')
          AND t.governance_status NOT IN ('BLOCKED','DEPRECATED')
          AND c.table_name=#{tableName}
        <if test="keyword != null and keyword != ''">
          AND (c.column_name LIKE CONCAT('%',#{keyword},'%')
            OR c.column_comment LIKE CONCAT('%',#{keyword},'%'))
        </if>
        ORDER BY c.ordinal_position,c.id
        </script>
        """)
    IPage<TermColumnVO> columns(Page<TermColumnVO> page, @Param("datasourceId") Long datasourceId,
            @Param("snapshotId") Long snapshotId, @Param("tableName") String tableName,
            @Param("keyword") String keyword);
}
