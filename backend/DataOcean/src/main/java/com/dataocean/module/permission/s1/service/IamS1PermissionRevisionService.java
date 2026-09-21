package com.dataocean.module.permission.s1.service;

/** IAM-SIMPLE-1 权限事实修订服务。 */
public interface IamS1PermissionRevisionService {

    Long record(String targetType, Long targetId, String changeType, Long operatorId, String reason);
}
