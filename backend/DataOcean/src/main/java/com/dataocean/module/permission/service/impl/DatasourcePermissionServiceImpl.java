package com.dataocean.module.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.datasource.entity.DatasourceAccess;
import com.dataocean.module.datasource.mapper.DatasourceAccessMapper;
import com.dataocean.module.datasource.service.DatasourceAccessService;
import com.dataocean.module.permission.entity.dto.DatasourcePermissionGrantDTO;
import com.dataocean.module.permission.entity.vo.DatasourcePermissionVO;
import com.dataocean.module.permission.event.PermissionChangedEvent;
import com.dataocean.module.permission.service.DatasourcePermissionService;
import com.dataocean.module.permission.service.support.PermissionValidationSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatasourcePermissionServiceImpl implements DatasourcePermissionService {

    private static final String EFFECT_ALLOW = "ALLOW";
    private static final String EFFECT_DENY = "DENY";

    private final DatasourceAccessMapper accessMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final PermissionValidationSupport validationSupport;
    private final DatasourceAccessService datasourceAccessService;

    @Transactional
    @Override
    public Long grant(DatasourcePermissionGrantDTO dto) {
        validationSupport.validateSubjectType(dto.getSubjectType());
        validationSupport.validateDatasourceExists(dto.getDatasourceId());
        validationSupport.validateSubjectExists(dto.getSubjectType(), dto.getSubjectId());

        DatasourceAccess existing = accessMapper.selectOne(new LambdaQueryWrapper<DatasourceAccess>()
                .eq(DatasourceAccess::getDatasourceId, dto.getDatasourceId())
                .eq(DatasourceAccess::getSubjectType, dto.getSubjectType())
                .eq(DatasourceAccess::getSubjectId, dto.getSubjectId()));
        if (existing != null) {
            throw new BusinessException("该主体已有此数据源的授权记录");
        }

        DatasourceAccess access = new DatasourceAccess();
        access.setDatasourceId(dto.getDatasourceId());
        access.setSubjectType(dto.getSubjectType());
        access.setSubjectId(dto.getSubjectId());
        access.setCanQuery(dto.getCanQuery());
        access.setCanExport(dto.getCanExport());
        access.setCanViewSql(dto.getCanViewSql());
        access.setAccessEffect(normalizeEffect(dto.getAccessEffect()));
        access.setGrantedBy(UserContext.currentUserId());
        accessMapper.insert(access);

        eventPublisher.publishEvent(new PermissionChangedEvent(this, dto.getSubjectId(), dto.getDatasourceId()));

        log.info("Datasource permission granted datasourceId={} subjectType={} subjectId={}",
                dto.getDatasourceId(), dto.getSubjectType(), dto.getSubjectId());
        return access.getId();
    }

    @Transactional
    @Override
    public void update(Long id, Boolean canQuery, Boolean canExport, Boolean canViewSql, String accessEffect) {
        DatasourceAccess access = accessMapper.selectById(id);
        if (access == null) {
            throw new BusinessException("授权记录不存在");
        }
        if (canQuery != null) access.setCanQuery(canQuery);
        if (canExport != null) access.setCanExport(canExport);
        if (canViewSql != null) access.setCanViewSql(canViewSql);
        if (accessEffect != null) access.setAccessEffect(normalizeEffect(accessEffect));
        accessMapper.updateById(access);
        eventPublisher.publishEvent(new PermissionChangedEvent(this, access.getSubjectId(), access.getDatasourceId()));
        log.info("Datasource permission updated id={}", id);
    }

    @Transactional
    @Override
    public void revoke(Long id) {
        DatasourceAccess access = accessMapper.selectById(id);
        if (access == null) {
            throw new BusinessException("授权记录不存在");
        }
        accessMapper.deleteById(id);
        eventPublisher.publishEvent(new PermissionChangedEvent(this, access.getSubjectId(), access.getDatasourceId()));
        log.info("Datasource permission revoked id={} datasourceId={} subjectType={} subjectId={}",
                id, access.getDatasourceId(), access.getSubjectType(), access.getSubjectId());
    }

    @Override
    public List<DatasourcePermissionVO> listByDatasource(Long datasourceId, String subjectType) {
        return accessMapper.selectPermissionList(datasourceId, subjectType);
    }

    @Override
    public boolean checkUserAccess(Long userId, Long datasourceId) {
        return datasourceAccessService.calculateDecision(userId, datasourceId).isCanQuery();
    }

    private String normalizeEffect(String accessEffect) {
        if (accessEffect == null || accessEffect.isBlank()) {
            return EFFECT_ALLOW;
        }
        String normalized = accessEffect.trim().toUpperCase();
        if (!EFFECT_ALLOW.equals(normalized) && !EFFECT_DENY.equals(normalized)) {
            throw new BusinessException("accessEffect only supports ALLOW/DENY");
        }
        return normalized;
    }
}
