package com.dataocean.module.prompt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.prompt.entity.PromptTemplateVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * Prompt 模板版本 Mapper 接口
 */
@Mapper
public interface PromptTemplateVersionMapper extends BaseMapper<PromptTemplateVersion> {
}
