package com.dataocean.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.audit.entity.LlmUsageLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * LLM 调用日志 Mapper 接口
 */
@Mapper
public interface LlmUsageLogMapper extends BaseMapper<LlmUsageLog> {
}
