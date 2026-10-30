package com.dataocean.module.fieldsemantic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.fieldsemantic.entity.po.GovernanceTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GovernanceTaskMapper extends BaseMapper<GovernanceTask> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM governance_task WHERE id=#{id} FOR UPDATE")
    GovernanceTask lock(@org.apache.ibatis.annotations.Param("id") Long id);
}
