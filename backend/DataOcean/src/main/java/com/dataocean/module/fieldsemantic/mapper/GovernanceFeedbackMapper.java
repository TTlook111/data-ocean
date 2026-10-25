package com.dataocean.module.fieldsemantic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.fieldsemantic.entity.po.GovernanceFeedback;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GovernanceFeedbackMapper extends BaseMapper<GovernanceFeedback> {
    @org.apache.ibatis.annotations.Select("SELECT id FROM query_task WHERE task_id=#{taskId} FOR UPDATE")
    Long lockTask(@org.apache.ibatis.annotations.Param("taskId") String taskId);
}
