package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.DatasourceSecret;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据源凭证 Mapper 接口
 * <p>
 * 提供对 datasource_secret 表的基础 CRUD 操作，
 * 继承 MyBatis-Plus 的 BaseMapper 获得通用数据访问能力。
 * </p>
 *
 * @author dataocean
 */
@Mapper
public interface DatasourceSecretMapper extends BaseMapper<DatasourceSecret> {
}
