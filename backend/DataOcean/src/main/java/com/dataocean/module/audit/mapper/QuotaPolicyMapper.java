package com.dataocean.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.audit.entity.QuotaPolicy;
import org.apache.ibatis.annotations.Mapper;

/**
 * 查询配额策略 Mapper 接口
 */
@Mapper
public interface QuotaPolicyMapper extends BaseMapper<QuotaPolicy> {
}
