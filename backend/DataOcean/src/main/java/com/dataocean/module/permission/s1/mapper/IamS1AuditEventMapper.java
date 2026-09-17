package com.dataocean.module.permission.s1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.permission.s1.entity.IamS1AuditEvent;
import org.apache.ibatis.annotations.Mapper;

/** IAM-SIMPLE-1 权限审计 Mapper。 */
@Mapper
public interface IamS1AuditEventMapper extends BaseMapper<IamS1AuditEvent> {
}
