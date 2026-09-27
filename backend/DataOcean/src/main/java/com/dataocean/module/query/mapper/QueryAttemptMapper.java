package com.dataocean.module.query.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.query.entity.QueryAttempt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QueryAttemptMapper extends BaseMapper<QueryAttempt> {
    @Select("SELECT * FROM query_attempt WHERE task_id = #{taskId} AND attempt_id = #{attemptId} FOR UPDATE")
    QueryAttempt selectForUpdate(@Param("taskId") String taskId, @Param("attemptId") String attemptId);
}
