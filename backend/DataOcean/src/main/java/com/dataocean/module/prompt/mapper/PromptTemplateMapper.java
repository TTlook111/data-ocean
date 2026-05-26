package com.dataocean.module.prompt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.prompt.entity.PromptTemplate;
import org.apache.ibatis.annotations.Mapper;

/**
 * Prompt 模板 Mapper 接口
 */
@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplate> {
}
