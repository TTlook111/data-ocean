package com.dataocean.module.permission.s1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.permission.s1.entity.IamS1RowCondition;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/** IAM-SIMPLE-1 结构化记录条件 Mapper。 */
@Mapper
public interface IamS1RowConditionMapper extends BaseMapper<IamS1RowCondition> {

    @Select({
            "<script>",
            "SELECT id, grant_id, metadata_snapshot_id, table_name, match_type, sequence_no,",
            "column_meta_id, column_name, operator_code, value_type, structured_value_json,",
            "parameter_reference, created_at FROM iam_s1_row_condition WHERE grant_id IN",
            "<foreach collection='grantIds' item='grantId' open='(' separator=',' close=')'>#{grantId}</foreach>",
            "ORDER BY grant_id, sequence_no",
            "</script>"
    })
    List<IamS1RowCondition> selectByGrantIds(@Param("grantIds") Collection<Long> grantIds);

    @Delete("DELETE FROM iam_s1_row_condition WHERE grant_id = #{grantId}")
    int deleteByGrantId(@Param("grantId") Long grantId);
}
