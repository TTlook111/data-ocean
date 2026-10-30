package com.dataocean.module.permission.s1.service;

import com.dataocean.module.permission.s1.entity.dto.IamS1FieldProtectionSaveDTO;

/** IAM-SIMPLE-1 字段保护配置服务。 */
public interface IamS1FieldProtectionService {

    Long saveProtection(Long operatorUserId, IamS1FieldProtectionSaveDTO request);

    void revokeProtection(Long operatorUserId, Long protectionId, String reason);
}
