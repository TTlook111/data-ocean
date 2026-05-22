package com.dataocean.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.user.entity.SysDepartment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 部门数据访问层接口。
 * <p>
 * 对应数据库表 sys_department，继承 MyBatis-Plus BaseMapper 提供基础 CRUD 能力。
 * 部门采用 parent_id 自关联实现树形结构。
 * </p>
 *
 * @author DataOcean
 */
@Mapper
public interface DepartmentMapper extends BaseMapper<SysDepartment> {
}
