package com.dataocean.module.fieldsemantic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.fieldsemantic.entity.po.BusinessKnowledgeRevision;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BusinessKnowledgeRevisionMapper extends BaseMapper<BusinessKnowledgeRevision> {
    @org.apache.ibatis.annotations.Select("SELECT id FROM metadata_snapshot WHERE id=#{snapshotId} FOR UPDATE")
    Long lockScope(@org.apache.ibatis.annotations.Param("snapshotId") Long snapshotId);
}
