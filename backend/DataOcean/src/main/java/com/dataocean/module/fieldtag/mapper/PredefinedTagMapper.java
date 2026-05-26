package com.dataocean.module.fieldtag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.fieldtag.entity.PredefinedTag;
import org.apache.ibatis.annotations.Mapper;

/**
 * 预定义标签 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供预定义标签的基础查询操作。
 * </p>
 */
@Mapper
public interface PredefinedTagMapper extends BaseMapper<PredefinedTag> {
}
