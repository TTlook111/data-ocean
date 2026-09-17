package com.dataocean.module.permission.s1.service;

import com.dataocean.module.permission.s1.entity.vo.IamS1BootstrapResult;

/** IAM-SIMPLE-1 启动式首个系统管理员初始化服务。 */
public interface IamS1BootstrapService {

    IamS1BootstrapResult bootstrap(Long targetUserId, String executionId);
}
