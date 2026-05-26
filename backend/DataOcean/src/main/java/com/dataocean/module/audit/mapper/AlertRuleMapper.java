package com.dataocean.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.audit.entity.AlertRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 告警规则 Mapper 接口
 */
@Mapper
public interface AlertRuleMapper extends BaseMapper<AlertRule> {
}
