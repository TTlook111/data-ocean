package com.dataocean.module.fieldtag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.fieldtag.entity.FeedbackReview;
import org.apache.ibatis.annotations.Mapper;

/**
 * 反馈审核 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供反馈审核的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface FeedbackReviewMapper extends BaseMapper<FeedbackReview> {
}
