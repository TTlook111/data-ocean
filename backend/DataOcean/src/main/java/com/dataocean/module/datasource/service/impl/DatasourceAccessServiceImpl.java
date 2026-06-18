package com.dataocean.module.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.DatasourceAccess;
import com.dataocean.module.datasource.entity.dto.DatasourceAccessGrantDTO;
import com.dataocean.module.datasource.entity.vo.DatasourceAccessVO;
import com.dataocean.module.datasource.entity.vo.DatasourcePermissionDecisionVO;
import com.dataocean.module.datasource.entity.vo.DatasourceSimpleVO;
import com.dataocean.module.datasource.mapper.DatasourceAccessMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.service.DatasourceAccessService;
import com.dataocean.module.permission.event.PermissionChangedEvent;
import com.dataocean.module.user.entity.SysDepartment;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.DepartmentMapper;
import com.dataocean.module.user.mapper.RoleMapper;
import com.dataocean.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatasourceAccessServiceImpl implements DatasourceAccessService {

    private static final String SUBJECT_USER = "USER";
    private static final String SUBJECT_ROLE = "ROLE";
    private static final String SUBJECT_DEPARTMENT = "DEPARTMENT";
    private static final String EFFECT_ALLOW = "ALLOW";
    private static final String EFFECT_DENY = "DENY";

    private final DatasourceMapper datasourceMapper;
    private final DatasourceAccessMapper accessMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final DepartmentMapper departmentMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    @Override
    public int grantAccess(Long datasourceId, DatasourceAccessGrantDTO request) {
        requireDatasource(datasourceId);
        validateUsers(request.getUserIds());
        Long grantedBy = UserContext.currentUserId();
        int granted = 0;
        for (Long userId : Set.copyOf(request.getUserIds())) {
            DatasourceAccess access = accessMapper.selectOne(new LambdaQueryWrapper<DatasourceAccess>()
                    .eq(DatasourceAccess::getDatasourceId, datasourceId)
                    .eq(DatasourceAccess::getSubjectType, SUBJECT_USER)
                    .eq(DatasourceAccess::getSubjectId, userId));
            if (access == null) {
                access = new DatasourceAccess();
                access.setDatasourceId(datasourceId);
                access.setSubjectType(SUBJECT_USER);
                access.setSubjectId(userId);
                access.setGrantedBy(grantedBy);
                access.setExpiresAt(request.getExpiresAt());
                access.setCanQuery(true);
                access.setCanExport(false);
                access.setCanViewSql(true);
                access.setAccessEffect(EFFECT_ALLOW);
                try {
                    accessMapper.insert(access);
                    granted++;
                } catch (DuplicateKeyException ignored) {
                    updateExisting(datasourceId, userId, request.getExpiresAt(), grantedBy);
                }
            } else {
                access.setExpiresAt(request.getExpiresAt());
                access.setGrantedBy(grantedBy);
                accessMapper.updateById(access);
                granted++;
            }
        }
        log.info("Datasource access granted datasourceId={} granted={}", datasourceId, granted);
        eventPublisher.publishEvent(new PermissionChangedEvent(this, null, datasourceId));
        return granted;
    }

    @Transactional
    @Override
    public void revokeAccess(Long datasourceId, Long userId) {
        requireDatasource(datasourceId);
        accessMapper.delete(new LambdaQueryWrapper<DatasourceAccess>()
                .eq(DatasourceAccess::getDatasourceId, datasourceId)
                .eq(DatasourceAccess::getSubjectType, SUBJECT_USER)
                .eq(DatasourceAccess::getSubjectId, userId));
        eventPublisher.publishEvent(new PermissionChangedEvent(this, userId, datasourceId));
        log.info("Datasource access revoked datasourceId={} userId={}", datasourceId, userId);
    }

    @Override
    public List<DatasourceAccessVO> listAccess(Long datasourceId) {
        requireDatasource(datasourceId);
        return accessMapper.selectAccessList(datasourceId);
    }

    @Override
    public List<DatasourceSimpleVO> listAccessibleDatasources() {
        if (hasAllPermission()) {
            return datasourceMapper.selectEnabledSimple();
        }
        Long userId = UserContext.currentUserId();
        List<Long> roleIds = roleMapper.selectByUserId(userId).stream()
                .map(SysRole::getId)
                .toList();
        Long deptId = userMapper.selectDepartmentIdByUserId(userId);
        List<Long> deptIds = collectDepartmentPath(deptId);
        List<DatasourceSimpleVO> enabledDatasources = datasourceMapper.selectEnabledSimple();
        if (enabledDatasources.isEmpty()) {
            return List.of();
        }
        List<Long> datasourceIds = enabledDatasources.stream().map(DatasourceSimpleVO::getId).toList();
        Map<Long, List<DatasourceAccess>> grantsByDatasource = accessMapper
                .selectValidGrantsForUserOnDatasources(datasourceIds, userId, roleIds, deptIds)
                .stream()
                .collect(Collectors.groupingBy(DatasourceAccess::getDatasourceId));
        return enabledDatasources.stream()
                .filter(item -> mergeDecision(userId, item.getId(), roleIds, deptId, deptIds,
                        grantsByDatasource.getOrDefault(item.getId(), List.of()), false).isCanQuery())
                .toList();
    }

    @Override
    public DatasourcePermissionDecisionVO calculateCurrentUserDecision(Long datasourceId) {
        return calculateDecision(UserContext.currentUserId(), datasourceId);
    }

    @Override
    public boolean checkAccess(Long datasourceId) {
        return calculateCurrentUserDecision(datasourceId).isCanQuery();
    }

    @Override
    public DatasourcePermissionDecisionVO calculateDecision(Long userId, Long datasourceId) {
        List<Long> roleIds = roleMapper.selectByUserId(userId).stream()
                .map(SysRole::getId)
                .toList();
        Long deptId = userMapper.selectDepartmentIdByUserId(userId);
        List<Long> deptIds = collectDepartmentPath(deptId);
        return calculateDecision(userId, datasourceId, roleIds, deptId, deptIds, hasAllPermission(userId));
    }

    private DatasourcePermissionDecisionVO calculateDecision(Long userId, Long datasourceId, List<Long> roleIds, Long deptId, List<Long> deptIds, boolean hasAllPermission) {
        DatasourcePermissionDecisionVO decision = new DatasourcePermissionDecisionVO();
        decision.setDatasourceId(datasourceId);
        decision.setUserId(userId);

        Datasource datasource = datasourceMapper.selectOne(new LambdaQueryWrapper<Datasource>()
                .eq(Datasource::getId, datasourceId)
                .eq(Datasource::getStatus, Datasource.STATUS_ENABLED)
                .eq(Datasource::getDeleted, 0L));
        if (datasource == null) {
            return decision;
        }

        List<DatasourceAccess> grants = accessMapper.selectValidGrantsForUser(datasourceId, userId, roleIds, deptIds);
        return mergeDecision(userId, datasourceId, roleIds, deptId, deptIds, grants, hasAllPermission);
    }

    private DatasourcePermissionDecisionVO mergeDecision(Long userId,
                                                        Long datasourceId,
                                                        List<Long> roleIds,
                                                        Long deptId,
                                                        List<Long> deptIds,
                                                        List<DatasourceAccess> grants,
                                                        boolean hasAllPermission) {
        DatasourcePermissionDecisionVO decision = new DatasourcePermissionDecisionVO();
        decision.setDatasourceId(datasourceId);
        decision.setUserId(userId);

        if (hasAllPermission) {
            decision.setCanQuery(true);
            decision.setCanExport(true);
            decision.setCanViewSql(true);
            decision.setDecisionSource("*");
            decision.setAccessEffect(EFFECT_ALLOW);
            return decision;
        }

        decision.setDepartmentId(deptId);
        decision.setRoleIds(new ArrayList<>(roleIds));

        applyDepartmentGrant(decision, grants, deptIds);
        applyRoleGrants(decision, grants, roleIds);
        applyUserGrant(decision, grants, userId);
        return decision;
    }

    private void applyDepartmentGrant(DatasourcePermissionDecisionVO decision, List<DatasourceAccess> grants, List<Long> deptIds) {
        if (deptIds.isEmpty()) return;
        for (Long deptId : deptIds) {
            DatasourceAccess grant = grants.stream()
                    .filter(item -> SUBJECT_DEPARTMENT.equals(item.getSubjectType()) && deptId.equals(item.getSubjectId()))
                    .findFirst()
                    .orElse(null);
            if (grant != null) {
                applyGrant(decision, grant);
                decision.setDecisionSource(SUBJECT_DEPARTMENT);
                decision.setEffectiveDepartmentId(deptId);
                return;
            }
        }
    }

    private void applyRoleGrants(DatasourcePermissionDecisionVO decision, List<DatasourceAccess> grants, List<Long> roleIds) {
        List<DatasourceAccess> roleGrants = grants.stream()
                .filter(grant -> SUBJECT_ROLE.equals(grant.getSubjectType()) && roleIds.contains(grant.getSubjectId()))
                .toList();
        if (roleGrants.isEmpty()) return;
        boolean hasAllowGrant = roleGrants.stream().anyMatch(grant -> !isDeny(grant));
        decision.setCanQuery(roleGrants.stream().anyMatch(grant -> !isDeny(grant) && Boolean.TRUE.equals(grant.getCanQuery())));
        decision.setCanExport(roleGrants.stream().anyMatch(grant -> !isDeny(grant) && Boolean.TRUE.equals(grant.getCanExport())));
        decision.setCanViewSql(roleGrants.stream().anyMatch(grant -> !isDeny(grant) && Boolean.TRUE.equals(grant.getCanViewSql())));
        decision.setDecisionSource(SUBJECT_ROLE);
        decision.setAccessEffect(hasAllowGrant ? EFFECT_ALLOW : EFFECT_DENY);
    }

    private void applyUserGrant(DatasourcePermissionDecisionVO decision, List<DatasourceAccess> grants, Long userId) {
        grants.stream()
                .filter(grant -> SUBJECT_USER.equals(grant.getSubjectType()) && userId.equals(grant.getSubjectId()))
                .findFirst()
                .ifPresent(grant -> {
                    applyGrant(decision, grant);
                    decision.setUserGrantId(grant.getId());
                    decision.setDecisionSource(SUBJECT_USER);
                });
    }

    private void applyGrant(DatasourcePermissionDecisionVO decision, DatasourceAccess grant) {
        if (isDeny(grant)) {
            decision.setCanQuery(false);
            decision.setCanExport(false);
            decision.setCanViewSql(false);
            decision.setAccessEffect(EFFECT_DENY);
            return;
        }
        decision.setCanQuery(Boolean.TRUE.equals(grant.getCanQuery()));
        decision.setCanExport(Boolean.TRUE.equals(grant.getCanExport()));
        decision.setCanViewSql(Boolean.TRUE.equals(grant.getCanViewSql()));
        decision.setAccessEffect(EFFECT_ALLOW);
    }

    private boolean isDeny(DatasourceAccess grant) {
        return EFFECT_DENY.equalsIgnoreCase(grant.getAccessEffect());
    }

    private List<Long> collectDepartmentPath(Long deptId) {
        if (deptId == null) {
            return List.of();
        }
        List<Long> deptIds = new ArrayList<>();
        Set<Long> visited = new java.util.HashSet<>();
        Long current = deptId;
        while (current != null && visited.add(current)) {
            SysDepartment department = departmentMapper.selectById(current);
            if (department == null || !Integer.valueOf(1).equals(department.getStatus())) {
                break;
            }
            deptIds.add(department.getId());
            current = department.getParentId();
        }
        return deptIds;
    }

    private boolean hasAllPermission() {
        return UserContext.currentPermissions().contains("*");
    }

    private boolean hasAllPermission(Long userId) {
        return userMapper.selectPermissionCodesByUserId(userId).contains("*");
    }

    private void updateExisting(Long datasourceId, Long userId, LocalDateTime expiresAt, Long grantedBy) {
        DatasourceAccess existing = accessMapper.selectOne(new LambdaQueryWrapper<DatasourceAccess>()
                .eq(DatasourceAccess::getDatasourceId, datasourceId)
                .eq(DatasourceAccess::getSubjectType, SUBJECT_USER)
                .eq(DatasourceAccess::getSubjectId, userId));
        if (existing != null) {
            existing.setExpiresAt(expiresAt);
            existing.setGrantedBy(grantedBy);
            existing.setAccessEffect(EFFECT_ALLOW);
            accessMapper.updateById(existing);
        }
    }

    private void requireDatasource(Long datasourceId) {
        Datasource datasource = datasourceMapper.selectOne(new LambdaQueryWrapper<Datasource>()
                .eq(Datasource::getId, datasourceId)
                .eq(Datasource::getDeleted, 0L));
        if (datasource == null) {
            throw new BusinessException("数据源不存在");
        }
    }

    private void validateUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            throw new BusinessException("授权用户不能为空");
        }
        List<SysUser> users = userMapper.selectByIds(userIds);
        if (users.size() != Set.copyOf(userIds).size()) {
            throw new BusinessException("存在不存在的授权用户");
        }
    }
}
