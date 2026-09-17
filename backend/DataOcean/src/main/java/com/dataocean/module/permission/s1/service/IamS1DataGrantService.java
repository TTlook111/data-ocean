package com.dataocean.module.permission.s1.service;

import com.dataocean.module.permission.s1.entity.dto.IamS1DataGrantSaveDTO;

import java.util.Collection;

/** IAM-SIMPLE-1 数据授权配置服务。 */
public interface IamS1DataGrantService {

    Long createGrant(Long operatorUserId, IamS1DataGrantSaveDTO request);

    void createGrants(Long operatorUserId, Collection<IamS1DataGrantSaveDTO> requests, String reason);

    void updateGrant(Long operatorUserId, Long grantId, IamS1DataGrantSaveDTO request);

    void revokeGrant(Long operatorUserId, Long grantId, String reason);
}
