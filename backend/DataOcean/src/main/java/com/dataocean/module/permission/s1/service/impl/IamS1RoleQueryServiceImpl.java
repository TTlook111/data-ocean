package com.dataocean.module.permission.s1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1ResponsibleDatasourceFact;
import com.dataocean.module.permission.s1.entity.IamS1Role;
import com.dataocean.module.permission.s1.entity.IamS1UserRole;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1RoleVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1UserRoleBindingVO;
import com.dataocean.module.permission.s1.mapper.IamS1CapabilityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1SubjectQueryMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserRoleMapper;
import com.dataocean.module.permission.s1.service.IamS1RoleQueryService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.dataocean.module.permission.s1.support.IamS1Labels;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** IAM-SIMPLE-1 角色与用户角色绑定只读查询实现。 */
@Service
@RequiredArgsConstructor
public class IamS1RoleQueryServiceImpl implements IamS1RoleQueryService {

    private final IamS1RoleMapper roleMapper;
    private final IamS1UserRoleMapper userRoleMapper;
    private final IamS1CapabilityMapper capabilityMapper;
    private final IamS1SubjectQueryMapper subjectQueryMapper;
    private final IamS1AdminGuard adminGuard;

    @Override
    public List<IamS1RoleVO> listRoles(Long operatorUserId, String keyword) {
        adminGuard.requireGlobalFunction(operatorUserId, "organization:role:view");
        LambdaQueryWrapper<IamS1Role> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String search = keyword.trim();
            wrapper.and(inner -> inner.like(IamS1Role::getRoleName, search)
                    .or().like(IamS1Role::getRoleCode, search));
        }
        wrapper.orderByAsc(IamS1Role::getId);
        List<IamS1RoleVO> result = new ArrayList<>();
        for (IamS1Role role : roleMapper.selectList(wrapper)) {
            result.add(toRoleVO(role));
        }
        return result;
    }

    @Override
    public IamS1RoleVO getRole(Long operatorUserId, Long roleId) {
        adminGuard.requireGlobalFunction(operatorUserId, "organization:role:view");
        return toRoleVO(requireRole(roleId));
    }

    @Override
    public List<IamS1UserRoleBindingVO> listMembers(Long operatorUserId, Long roleId) {
        adminGuard.requireGlobalFunction(operatorUserId, "organization:role:view");
        IamS1Role role = requireRole(roleId);
        List<IamS1UserRole> bindings = userRoleMapper.selectList(new LambdaQueryWrapper<IamS1UserRole>()
                .eq(IamS1UserRole::getRoleId, roleId)
                .orderByAsc(IamS1UserRole::getId));
        List<String> functionCodes = roleMapper.selectFunctionCodesByRoleId(roleId);
        List<IamS1UserRoleBindingVO> result = new ArrayList<>();
        for (IamS1UserRole binding : bindings) {
            result.add(toBindingVO(binding, role, functionCodes));
        }
        return result;
    }

    @Override
    public List<IamS1UserRoleBindingVO> listUserRoles(Long operatorUserId, Long targetUserId) {
        if (targetUserId == null) {
            throw new BusinessException("目标账号不能为空");
        }
        adminGuard.requireGlobalFunction(operatorUserId, "organization:role:view");
        List<IamS1UserRole> bindings = userRoleMapper.selectList(new LambdaQueryWrapper<IamS1UserRole>()
                .eq(IamS1UserRole::getUserId, targetUserId)
                .orderByAsc(IamS1UserRole::getId));
        List<IamS1UserRoleBindingVO> result = new ArrayList<>();
        Map<Long, IamS1Role> roleCache = new LinkedHashMap<>();
        for (IamS1UserRole binding : bindings) {
            IamS1Role role = roleCache.computeIfAbsent(binding.getRoleId(), roleMapper::selectById);
            if (role == null) {
                continue;
            }
            result.add(toBindingVO(binding, role, roleMapper.selectFunctionCodesByRoleId(role.getId())));
        }
        return result;
    }

    @Override
    public List<IamS1DatasourceRefVO> responsibleDatasourcesOfBinding(Long operatorUserId, Long userRoleId) {
        adminGuard.requireGlobalFunction(operatorUserId, "organization:role:view");
        if (userRoleId == null) {
            throw new BusinessException("用户角色绑定 ID 不能为空");
        }
        List<IamS1DatasourceRefVO> result = new ArrayList<>();
        for (IamS1ResponsibleDatasourceFact fact
                : capabilityMapper.selectResponsibleDatasourcesByBinding(userRoleId)) {
            result.add(new IamS1DatasourceRefVO(fact.getDatasourceId(), fact.getDatasourceName(),
                    Integer.valueOf(IamS1Constants.ENABLED).equals(fact.getDatasourceStatus())));
        }
        return result;
    }

    private IamS1RoleVO toRoleVO(IamS1Role role) {
        List<String> functionCodes = roleMapper.selectFunctionCodesByRoleId(role.getId());
        List<String> functionNames = new ArrayList<>();
        for (String code : functionCodes) {
            var definition = com.dataocean.module.permission.s1.catalog.IamS1FunctionCatalog.find(code);
            if (definition != null) {
                functionNames.add(definition.name());
            }
        }
        Long memberCount = userRoleMapper.selectCount(new LambdaQueryWrapper<IamS1UserRole>()
                .eq(IamS1UserRole::getRoleId, role.getId())
                .eq(IamS1UserRole::getStatus, IamS1Constants.ENABLED));
        return new IamS1RoleVO(role.getId(), role.getRoleCode(), role.getRoleName(), role.getDescription(),
                Integer.valueOf(IamS1Constants.ENABLED).equals(role.getStatus()),
                Integer.valueOf(IamS1Constants.ENABLED).equals(role.getProtectedRole()),
                Integer.valueOf(IamS1Constants.ENABLED).equals(role.getBuiltIn()),
                functionCodes, functionNames, IamS1Labels.capabilitySummary(functionCodes),
                memberCount == null ? 0L : memberCount, role.getCreatedAt());
    }

    private IamS1UserRoleBindingVO toBindingVO(IamS1UserRole binding, IamS1Role role, List<String> functionCodes) {
        List<IamS1DatasourceRefVO> datasources = new ArrayList<>();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (IamS1ResponsibleDatasourceFact fact
                : capabilityMapper.selectResponsibleDatasourcesByBinding(binding.getId())) {
            datasources.add(new IamS1DatasourceRefVO(fact.getDatasourceId(), fact.getDatasourceName(),
                    Integer.valueOf(IamS1Constants.ENABLED).equals(fact.getDatasourceStatus())));
            names.add(fact.getDatasourceName());
        }
        String datasourceSummary = names.isEmpty()
                ? "未选择负责数据源，无法进入数据源相关后台工作区"
                : "负责数据源：" + String.join("、", names);
        return new IamS1UserRoleBindingVO(binding.getId(), binding.getUserId(), role.getId(),
                role.getRoleCode(), role.getRoleName(),
                Integer.valueOf(IamS1Constants.ENABLED).equals(role.getStatus()),
                Integer.valueOf(IamS1Constants.ENABLED).equals(binding.getStatus()),
                Integer.valueOf(IamS1Constants.ENABLED).equals(role.getProtectedRole()),
                functionCodes.stream()
                        .map(com.dataocean.module.permission.s1.catalog.IamS1FunctionCatalog::find)
                        .filter(definition -> definition != null)
                        .map(com.dataocean.module.permission.s1.catalog.IamS1FunctionCatalog.Definition::name)
                        .toList(),
                IamS1Labels.capabilitySummary(functionCodes), datasources, datasourceSummary);
    }

    /** 用户显示名：优先真实姓名，缺省回退账号。 */
    public String displayUserName(Long userId) {
        Map<String, Object> brief = subjectQueryMapper.selectUserBrief(userId);
        if (brief == null) {
            return null;
        }
        Object realName = brief.get("real_name");
        Object username = brief.get("username");
        return realName == null || String.valueOf(realName).isBlank()
                ? String.valueOf(username) : String.valueOf(realName);
    }

    private IamS1Role requireRole(Long roleId) {
        if (roleId == null) {
            throw new BusinessException("S1 角色 ID 不能为空");
        }
        IamS1Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("S1 角色不存在");
        }
        return role;
    }
}
