package com.dataocean.module.glossary.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.glossary.entity.GlossaryTerm;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 术语条目 Mapper 接口
 *
 * @author dataocean
 */
@Mapper
public interface GlossaryTermMapper extends BaseMapper<GlossaryTerm> {

    /**
     * 按 FQN 精确查询术语
     */
    @Select("SELECT * FROM glossary_term WHERE fqn = #{fqn} LIMIT 1")
    GlossaryTerm selectByFqn(@Param("fqn") String fqn);
}
