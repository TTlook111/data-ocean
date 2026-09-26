package com.dataocean.module.query.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.query.entity.QueryTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 查询任务 Mapper
 */
@Mapper
public interface QueryTaskMapper extends BaseMapper<QueryTask> {
    @Select("SELECT * FROM query_task WHERE task_id = #{taskId} FOR UPDATE")
    QueryTask selectByTaskIdForUpdate(@Param("taskId") String taskId);
}
