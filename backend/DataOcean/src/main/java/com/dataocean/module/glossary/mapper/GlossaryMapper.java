package com.dataocean.module.glossary.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.glossary.entity.Glossary;
import org.apache.ibatis.annotations.Mapper;

/**
 * 术语表 Mapper 接口
 *
 * @author dataocean
 */
@Mapper
public interface GlossaryMapper extends BaseMapper<Glossary> {
}
