package com.dataocean.module.glossary.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.glossary.entity.GlossaryTerm;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 术语条目 Mapper 接口
 *
 * @author dataocean
 */
@Mapper
public interface GlossaryTermMapper extends BaseMapper<GlossaryTerm> {

    /**
     * 查询指定术语表下所有已审核通过的术语（用于 RAG 查询改写）
     */
    @Select("SELECT * FROM glossary_term WHERE glossary_id = #{glossaryId} AND status = 'APPROVED'")
    List<GlossaryTerm> selectApprovedByGlossaryId(@Param("glossaryId") Long glossaryId);

    /**
     * 按 FQN 精确查询术语
     */
    @Select("SELECT * FROM glossary_term WHERE fqn = #{fqn} LIMIT 1")
    GlossaryTerm selectByFqn(@Param("fqn") String fqn);
}
