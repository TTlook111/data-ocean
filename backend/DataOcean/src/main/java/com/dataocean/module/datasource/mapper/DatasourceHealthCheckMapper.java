package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.DatasourceHealthCheck;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据源健康检查记录 Mapper 接口
 * <p>
 * 提供对 datasource_health_check 表的基础 CRUD 操作，
 * 继承 MyBatis-Plus 的 BaseMapper 获得通用数据访问能力。
 * </p>
 *
 * @author dataocean
 */
@Mapper
public interface DatasourceHealthCheckMapper extends BaseMapper<DatasourceHealthCheck> {
}
