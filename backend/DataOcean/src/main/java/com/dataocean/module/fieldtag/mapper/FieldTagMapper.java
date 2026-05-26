package com.dataocean.module.fieldtag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.fieldtag.entity.FieldTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 字段标签 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供字段标签的基础 CRUD 操作，
 * 并扩展批量插入方法。
 * </p>
 */
@Mapper
public interface FieldTagMapper extends BaseMapper<FieldTag> {

    /**
     * 批量插入字段标签
     *
     * @param tags 标签列表
     * @return 插入行数
     */
    int batchInsert(@Param("tags") List<FieldTag> tags);
}
