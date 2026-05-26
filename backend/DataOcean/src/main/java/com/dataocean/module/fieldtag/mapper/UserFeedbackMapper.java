package com.dataocean.module.fieldtag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.fieldtag.entity.UserFeedback;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户反馈 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供用户反馈的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface UserFeedbackMapper extends BaseMapper<UserFeedback> {
}
