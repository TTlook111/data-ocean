package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.dto.IamS1DataAuthorizationRequestDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1TableRequestDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot;
import com.dataocean.module.permission.s1.enums.IamS1ColumnUsage;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1DataAuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1EffectivePermissionService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实际权限预览实现：只做“补齐协议字段 + 授权判定”，权限结论完全来自统一 Resolver。
 */
@Service
@RequiredArgsConstructor
public class IamS1EffectivePermissionServiceImpl implements IamS1EffectivePermissionService {

    private final IamS1DataAuthorizationResolver dataAuthorizationResolver;
    private final IamS1AuthorizationResolver authorizationResolver;
    private final IamS1AdminGuard adminGuard;

    @Override
    public IamS1DataAuthorizationSnapshot preview(Long callerUserId,
                                                   IamS1DataAuthorizationRequestDTO request) {
        if (callerUserId == null) {
            throw new BusinessException(401, "未登录，无法预览实际权限");
        }
        if (request == null || request.getDatasourceId() == null) {
            throw new BusinessException("请先选择数据源");
        }
        Long targetUserId = request.getUserId() == null ? callerUserId : request.getUserId();
        if (!callerUserId.equals(targetUserId)) {
            // 查看他人实际权限需要“查看用户实际权限”功能，并且必须负责目标数据源。
            adminGuard.requireDatasourceFunction(callerUserId, "security:effective:view",
                    request.getDatasourceId());
        } else if (!authorizationResolver.hasGlobalFunction(callerUserId, "query:use")
                && !authorizationResolver.isSystemAdmin(callerUserId)) {
            throw new BusinessException(403, "没有“使用问数”权限，无法查看本人数据权限");
        }
        String protocolVersion = request.getProtocolVersion();
        if (protocolVersion != null && !protocolVersion.isBlank()
                && !IamS1Constants.PROTOCOL_VERSION.equals(protocolVersion)) {
            throw new BusinessException("预览请求不是 IAM-SIMPLE-1 协议");
        }
        request.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
        request.setUserId(targetUserId);
        request.setCalculatedAt(LocalDateTime.now());
        request.setTables(normalizeTables(request.getTables()));
        if (request.getTables().isEmpty()) {
            throw new BusinessException("请至少选择一张表和字段，才能预览实际权限");
        }
        return dataAuthorizationResolver.preview(request);
    }

    /**
     * 预览场景下管理员只选择“表和字段”，因此按与问数入口一致的默认使用位置补齐
     * （PROJECTION/FILTER/JOIN，见 {@link IamS1UsageDefaults}）：
     * 这样预览与“本次查询会用到这些字段”的真实语义一致，避免出现预览通过、查询被拒的假象。
     */
    private List<IamS1TableRequestDTO> normalizeTables(List<IamS1TableRequestDTO> tables) {
        List<IamS1TableRequestDTO> normalized = new ArrayList<>();
        if (tables == null) {
            return normalized;
        }
        for (IamS1TableRequestDTO table : tables) {
            if (table == null || table.getTableName() == null || table.getTableName().isBlank()) {
                continue;
            }
            Set<String> columns = new LinkedHashSet<>();
            if (table.getReferencedColumns() != null) {
                for (String column : table.getReferencedColumns()) {
                    if (column != null && !column.isBlank()) {
                        columns.add(column.trim());
                    }
                }
            }
            if (columns.isEmpty()) {
                throw new BusinessException("请至少选择一个字段，空字段不表示全部字段：" + table.getTableName());
            }
            IamS1TableRequestDTO rebuilt = new IamS1TableRequestDTO(table.getTableName().trim(), columns);
            Map<String, Set<IamS1ColumnUsage>> usages = new LinkedHashMap<>();
            for (String column : columns) {
                Set<IamS1ColumnUsage> declared = table.getColumnUsages() == null
                        ? null : table.getColumnUsages().get(column);
            usages.put(column, declared == null || declared.isEmpty()
                    ? com.dataocean.module.permission.s1.support.IamS1UsageDefaults.defaults() : declared);
            }
            rebuilt.setColumnUsages(usages);
            normalized.add(rebuilt);
        }
        return normalized;
    }
}
