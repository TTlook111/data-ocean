package com.dataocean.module.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.metadata.entity.MetadataEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 统一实体 Mapper 接口
 *
 * @author dataocean
 */
@Mapper
public interface MetadataEntityMapper extends BaseMapper<MetadataEntity> {

    /**
     * 按 FQN 精确查询实体
     */
    @Select("SELECT * FROM metadata_entity WHERE fqn = #{fqn} LIMIT 1")
    MetadataEntity selectByFqn(@Param("fqn") String fqn);

    /**
     * 按数据源 ID 查询所有 TABLE 类型实体
     * <p>
     * 通过 FQN 前缀匹配（fqn LIKE 'datasourceName.db.%'）
     * </p>
     */
    @Select("SELECT * FROM metadata_entity WHERE entity_type = 'TABLE' AND fqn LIKE CONCAT(#{fqnPrefix}, '.%')")
    List<MetadataEntity> selectTablesByFqnPrefix(@Param("fqnPrefix") String fqnPrefix);

    /**
     * 按数据源 ID 查询所有实体（通过 entity_metadata 中的 datasource_id）
     */
    @Select("SELECT * FROM metadata_entity WHERE JSON_EXTRACT(entity_metadata, '$.datasource_id') = #{datasourceId}")
    List<MetadataEntity> selectByDatasourceId(@Param("datasourceId") Long datasourceId);

    /**
     * 全文搜索实体
     */
    @Select("""
            SELECT *, MATCH(name, display_name, description) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS relevance
            FROM metadata_entity
            WHERE MATCH(name, display_name, description) AGAINST(#{query} IN NATURAL LANGUAGE MODE)
            <if test="entityType != null">
              AND entity_type = #{entityType}
            </if>
            ORDER BY relevance DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<MetadataEntity> fullTextSearch(@Param("query") String query,
                                         @Param("entityType") String entityType,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);
}
