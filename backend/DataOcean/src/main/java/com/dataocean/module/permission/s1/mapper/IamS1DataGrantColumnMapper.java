package com.dataocean.module.permission.s1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.permission.s1.entity.IamS1DataGrantColumn;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/** IAM-SIMPLE-1 授权明确字段 Mapper。 */
@Mapper
public interface IamS1DataGrantColumnMapper extends BaseMapper<IamS1DataGrantColumn> {

    @Select({
            "<script>",
            "SELECT id, grant_id, metadata_snapshot_id, column_meta_id, table_name, column_name, created_at",
            "FROM iam_s1_data_grant_column WHERE grant_id IN",
            "<foreach collection='grantIds' item='grantId' open='(' separator=',' close=')'>#{grantId}</foreach>",
            "ORDER BY grant_id, id",
            "</script>"
    })
    List<IamS1DataGrantColumn> selectByGrantIds(@Param("grantIds") Collection<Long> grantIds);

    @Delete("DELETE FROM iam_s1_data_grant_column WHERE grant_id = #{grantId}")
    int deleteByGrantId(@Param("grantId") Long grantId);
}
