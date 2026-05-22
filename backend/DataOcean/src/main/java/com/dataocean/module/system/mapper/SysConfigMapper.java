package com.dataocean.module.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.system.entity.SysConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统配置 Mapper 接口。
 * <p>
 * 继承 MyBatis-Plus 的 {@link BaseMapper}，提供对 sys_config 表的基础 CRUD 操作。
 * 如需自定义 SQL，可在对应的 XML 映射文件中扩展。
 * </p>
 *
 * @author dataocean
 */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {
}
