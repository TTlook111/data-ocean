package com.dataocean.module.query.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.query.entity.QueryModelCall;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QueryModelCallMapper extends BaseMapper<QueryModelCall> {
    @Select("SELECT * FROM query_model_call WHERE task_id = #{taskId} AND call_id = #{callId} FOR UPDATE")
    QueryModelCall selectForUpdate(@Param("taskId") String taskId, @Param("callId") String callId);
}
