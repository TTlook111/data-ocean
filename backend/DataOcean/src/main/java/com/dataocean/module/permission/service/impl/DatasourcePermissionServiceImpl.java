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

/**
 * 数据源权限服务实现类。
 * <p>
 * 负责数据源级别的访问授权管理，支持用户和角色两种主体类型。
 * 提供授权（grant）、撤销（revoke）、查询（listBySubject）等核心功能。
 * </p>
 * <p>
 * 授权模型：
 * <ul>
 *   <li>主体类型：USER（用户）或 ROLE（角色）</li>
 *   <li>授权效果：ALLOW（允许）或 DENY（拒绝）</li>
 *   <li>同一主体对同一数据源只能有一条授权记录</li>
 *   <li>授权变更会触发权限变更事件，用于清除相关缓存</li>
 * </ul>
 * </p>
 *
 * @author DataOcean
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DatasourcePermissionServiceImpl implements DatasourcePermissionService {

    /** 授权效果：允许访问 */
    private static final String EFFECT_ALLOW = "ALLOW";
    /** 授权效果：拒绝访问 */
    private static final String EFFECT_DENY = "DENY";

    /** 数据源访问授权 Mapper */
    private final DatasourceAccessMapper accessMapper;
    /** Spring 事件发布器，用于发布权限变更事件 */
    private final ApplicationEventPublisher eventPublisher;
    /** 权限校验支持类，提供通用校验逻辑 */
    private final PermissionValidationSupport validationSupport;
    /** 数据源访问服务，提供数据源访问相关操作 */
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
