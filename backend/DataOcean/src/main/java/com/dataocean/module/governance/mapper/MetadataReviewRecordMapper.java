package com.dataocean.module.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.governance.entity.po.MetadataReviewRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 元数据治理审核记录 Mapper 接口。
 */
@Mapper
public interface MetadataReviewRecordMapper extends BaseMapper<MetadataReviewRecord> {
}
