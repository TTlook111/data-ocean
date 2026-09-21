package com.dataocean.module.fieldtag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.fieldtag.entity.FieldTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
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

    /**
     * 按标签编码查询负责源范围内的字段 ID。范围必须下推到 JOIN，不能先查出全部再在内存过滤。
     */
    @Select("""
            <script>
            SELECT DISTINCT ft.column_meta_id
            FROM field_tag ft
            INNER JOIN db_column_meta c ON c.id = ft.column_meta_id
            WHERE ft.tag_code = #{tagCode}
              AND c.datasource_id IN
            <foreach collection="datasourceIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> selectColumnIdsByTagCodeInDatasources(@Param("tagCode") String tagCode,
                                                     @Param("datasourceIds") Collection<Long> datasourceIds);
}
