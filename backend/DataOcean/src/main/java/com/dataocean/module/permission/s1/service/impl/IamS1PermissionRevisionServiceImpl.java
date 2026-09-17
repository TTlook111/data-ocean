package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.entity.IamS1PermissionRevision;
import com.dataocean.module.permission.s1.mapper.IamS1PermissionRevisionMapper;
import com.dataocean.module.permission.s1.service.IamS1PermissionRevisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** IAM-SIMPLE-1 权限事实修订服务实现。 */
@Service
@RequiredArgsConstructor
public class IamS1PermissionRevisionServiceImpl implements IamS1PermissionRevisionService {

    private final IamS1PermissionRevisionMapper revisionMapper;

    @Override
    public Long record(String targetType, Long targetId, String changeType, Long operatorId, String reason) {
        if (targetType == null || targetType.isBlank() || changeType == null || changeType.isBlank()) {
            throw new BusinessException("权限修订缺少目标或变更类型");
        }
        IamS1PermissionRevision revision = new IamS1PermissionRevision();
        revision.setTargetType(targetType);
        revision.setTargetId(targetId);
        revision.setChangeType(changeType);
        revision.setOperatorId(operatorId);
        revision.setReason(reason);
        revisionMapper.insert(revision);
        if (revision.getRevisionNo() == null) {
            throw new BusinessException("权限修订写入失败");
        }
        return revision.getRevisionNo();
    }
}
