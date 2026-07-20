package com.dataocean.module.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.governance.entity.QualityCheckResult;
import org.apache.ibatis.annotations.Mapper;

/**
 * 质量检查结果时序 Mapper 接口。
 * <p>
 * 用于记录每次质量检查的维度得分和问题数量，支持质量趋势分析。
 * </p>
 */
@Mapper
public interface QualityCheckResultMapper extends BaseMapper<QualityCheckResult> {
}
