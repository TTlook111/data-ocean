package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.DatasourceHealthCheck;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DatasourceHealthCheckMapper extends BaseMapper<DatasourceHealthCheck> {
}
