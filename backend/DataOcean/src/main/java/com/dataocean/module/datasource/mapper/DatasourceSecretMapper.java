package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.DatasourceSecret;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DatasourceSecretMapper extends BaseMapper<DatasourceSecret> {
}
