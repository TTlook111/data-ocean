package com.dataocean.module.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 统一关系边 Mapper 接口
 *
 * @author dataocean
 */
@Mapper
public interface MetadataRelationshipMapper extends BaseMapper<MetadataRelationship> {

    /**
     * 查询指定实体的所有出向关系
     */
    @Select("SELECT * FROM metadata_relationship WHERE source_id = #{sourceId} AND source_type = #{sourceType}")
    List<MetadataRelationship> selectBySource(@Param("sourceId") Long sourceId,
                                               @Param("sourceType") String sourceType);

    /**
     * 查询指定实体的所有入向关系
     */
    @Select("SELECT * FROM metadata_relationship WHERE target_id = #{targetId} AND target_type = #{targetType}")
    List<MetadataRelationship> selectByTarget(@Param("targetId") Long targetId,
                                               @Param("targetType") String targetType);

    /**
     * 查询指定关系类型的所有关系
     */
    @Select("SELECT * FROM metadata_relationship WHERE relation_type = #{relationType}")
    List<MetadataRelationship> selectByRelationType(@Param("relationType") String relationType);

    /**
     * 查询两个实体之间的指定类型关系
     */
    @Select("SELECT * FROM metadata_relationship WHERE source_id = #{sourceId} AND target_id = #{targetId} AND relation_type = #{relationType} LIMIT 1")
    MetadataRelationship selectBetween(@Param("sourceId") Long sourceId,
                                        @Param("targetId") Long targetId,
                                        @Param("relationType") String relationType);

    /**
     * 查询指定实体的血缘关系（上游或下游）
     */
    @Select("SELECT * FROM metadata_relationship WHERE relation_type = 'LINEAGE' AND (source_id = #{entityId} OR target_id = #{entityId})")
    List<MetadataRelationship> selectLineageByEntityId(@Param("entityId") Long entityId);
}
