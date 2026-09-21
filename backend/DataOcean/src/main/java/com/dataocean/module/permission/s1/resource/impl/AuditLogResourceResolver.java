package com.dataocean.module.permission.s1.resource.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.audit.entity.QueryAuditLog;
import com.dataocean.module.audit.mapper.QueryAuditLogMapper;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolver;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * AUDIT_LOG：查询审计记录归属于哪个数据源。
 *
 * <p>`query_audit_log.datasource_id` 是审计记录写入时的事实。记录不存在返回 404、
 * 归属缺失返回 409——不允许把「没有归属」当成「不限制数据源」。</p>
 *
 * <p>只读业务只读事实，不读取旧角色权限、旧数据授权或旧缓存。</p>
 */
@Component
@RequiredArgsConstructor
public class AuditLogResourceResolver implements IamS1ResourceResolver {

    private final QueryAuditLogMapper auditLogMapper;

    @Override
    public IamS1ResourceType supports() {
        return IamS1ResourceType.AUDIT_LOG;
    }

    @Override
    public IamS1ResolvedResource resolve(Object resourceId) {
        Long id = IamS1ResourceIds.asLong(resourceId);
        if (id == null) {
            throw new BusinessException(400, "缺少审计记录参数，无法判定负责范围");
        }
        QueryAuditLog auditLog = auditLogMapper.selectById(id);
        if (auditLog == null) {
            throw new BusinessException(404, "审计记录不存在");
        }
        if (auditLog.getDatasourceId() == null) {
            throw new BusinessException(409, "审计记录缺少数据源归属，无法判定负责范围");
        }
        return new IamS1ResolvedResource(IamS1ResourceType.AUDIT_LOG, id,
                auditLog.getDatasourceId(), null, null, null);
    }
}
