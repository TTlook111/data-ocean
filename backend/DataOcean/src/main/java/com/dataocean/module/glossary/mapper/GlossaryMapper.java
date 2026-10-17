package com.dataocean.module.glossary.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.glossary.entity.po.Glossary;
import org.apache.ibatis.annotations.Mapper;

/**
 * 术语表 Mapper 接口
 *
 * @author dataocean
 */
@Mapper
public interface GlossaryMapper extends BaseMapper<Glossary> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM glossary WHERE id=#{id} FOR UPDATE")
    Glossary selectForUpdate(@org.apache.ibatis.annotations.Param("id") Long id);
}
