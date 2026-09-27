package com.dataocean.module.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.knowledge.entity.RagIndexState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RagIndexStateMapper extends BaseMapper<RagIndexState> {
    @Select("SELECT * FROM rag_index_state WHERE datasource_id = #{datasourceId} FOR UPDATE")
    RagIndexState selectForUpdate(@Param("datasourceId") Long datasourceId);
}
