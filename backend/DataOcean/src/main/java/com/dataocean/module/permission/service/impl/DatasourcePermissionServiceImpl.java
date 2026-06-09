package com.dataocean.module.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.DatasourceAccess;
import com.dataocean.module.datasource.mapper.DatasourceAccessMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.permission.entity.dto.DatasourcePermissionGrantDTO;
import com.dataocean.module.permission.entity.vo.DatasourcePermissionVO;
import com.dataocean.module.permission.enums.SubjectType;
import com.dataocean.module.permission.service.DatasourcePermissionService;
import com.dataocean.module.permission.service.PermissionCalculator;
import com.dataocean.module.permission.service.support.PermissionValidationSupport;
import com.dataocean.module.user.entity.SysDepartment;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.DepartmentMapper;
import com.dataocean.module.user.mapper.RoleMapper;
import com.dataocean.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 数据源权限管理服务实现
 *
 * @author dataocean
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DatasourcePermissionServiceImpl implements DatasourcePermissionService {

    private final DatasourceAccessMapper accessMapper;
    private final DatasourceMapper datasourceMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final DepartmentMapper departmentMapper;
    private final PermissionCalculator permissionCalculator;
    private final PermissionValidationSupport validationSupport;

    @Transactional
    @Override
    public Long grant(DatasourcePermissionGrantDTO dto) {
        // 校验主体类型合法性
        validationSupport.validateSubjectType(dto.getSubjectType());
        // 校验数据源存在
        validationSupport.validateDatasourceExists(dto.getDatasourceId());
        // 校验主体存在
        validationSupport.validateSubjectExists(dto.getSubjectType(), dto.getSubjectId());

        // 检查是否已存在相同授权
        DatasourceAccess existing = accessMapper.selectOne(new LambdaQueryWrapper<DatasourceAccess>()
                .eq(DatasourceAccess::getDatasourceId, dto.getDatasourceId())
                .eq(DatasourceAccess::getSubjectType, dto.getSubjectType())
                .eq(DatasourceAccess::getSubjectId, dto.getSubjectId()));
        if (existing != null) {
            throw new BusinessException("该主体已有此数据源的授权记录");
        }

        // 创建授权记录
        DatasourceAccess access = new DatasourceAccess();
        access.setDatasourceId(dto.getDatasourceId());
        access.setSubjectType(dto.getSubjectType());
        access.setSubjectId(dto.getSubjectId());
        access.setCanQuery(dto.getCanQuery());
        access.setCanExport(dto.getCanExport());
        access.setCanViewSql(dto.getCanViewSql());
        access.setGrantedBy(UserContext.currentUserId());
        accessMapper.insert(access);

        // 主动清除权限缓存
        permissionCalculator.invalidate(dto.getSubjectId(), dto.getDatasourceId());

        log.info("数据源授权成功 datasourceId={} subjectType={} subjectId={}",
                dto.getDatasourceId(), dto.getSubjectType(), dto.getSubjectId());
        return access.getId();
    }

    @Transactional
    @Override
    public void update(Long id, Boolean canQuery, Boolean canExport, Boolean canViewSql) {
        DatasourceAccess access = accessMapper.selectById(id);
        if (access == null) {
            throw new BusinessException("授权记录不存在");
        }
        if (canQuery != null) access.setCanQuery(canQuery);
        if (canExport != null) access.setCanExport(canExport);
        if (canViewSql != null) access.setCanViewSql(canViewSql);
        accessMapper.updateById(access);
        permissionCalculator.invalidate(access.getSubjectId(), access.getDatasourceId());
        log.info("数据源授权更新 id={}", id);
    }

    @Transactional
    @Override
    public void revoke(Long id) {
        DatasourceAccess access = accessMapper.selectById(id);
        if (access == null) {
            throw new BusinessException("授权记录不存在");
        }
        accessMapper.deleteById(id);
        permissionCalculator.invalidate(access.getSubjectId(), access.getDatasourceId());
        log.info("数据源授权撤销 id={} datasourceId={} subjectType={} subjectId={}",
                id, access.getDatasourceId(), access.getSubjectType(), access.getSubjectId());
    }

    @Override
    public List<DatasourcePermissionVO> listByDatasource(Long datasourceId, String subjectType) {
        return accessMapper.selectPermissionList(datasourceId, subjectType);
    }

    @Override
    public boolean checkUserAccess(Long userId, Long datasourceId) {
        // 超级管理员直接放行
        if (UserContext.currentPermissions().contains("*")) {
            return true;
        }

        // 1. 检查用户直接授权
        Long directCount = accessMapper.countValidAccess(datasourceId, "USER", userId);
        if (directCount > 0) return true;

        // 2. 检查用户角色授权
        List<Long> roleIds = roleMapper.selectByUserId(userId).stream()
                .map(r -> r.getId()).toList();
        for (Long roleId : roleIds) {
            Long roleCount = accessMapper.countValidAccess(datasourceId, "ROLE", roleId);
            if (roleCount > 0) return true;
        }

        // 3. 检查用户部门授权
        Long departmentId = userMapper.selectDepartmentIdByUserId(userId);
        if (departmentId != null) {
            Long deptCount = accessMapper.countValidAccess(datasourceId, "DEPARTMENT", departmentId);
            if (deptCount > 0) return true;
        }

        return false;
    }

}
