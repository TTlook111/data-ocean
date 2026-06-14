package com.dataocean.module.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.permission.entity.AccessApprovalRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据访问审批请求 Mapper 接口
 *
 * @author dataocean
 */
@Mapper
public interface AccessApprovalRequestMapper extends BaseMapper<AccessApprovalRequest> {
}
