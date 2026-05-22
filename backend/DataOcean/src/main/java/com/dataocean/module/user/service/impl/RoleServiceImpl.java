package com.dataocean.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.mapper.RoleMapper;
import com.dataocean.module.user.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 角色管理业务实现类。
 * <p>
 * 实现 {@link RoleService} 接口，提供角色查询的具体逻辑。
 * </p>
 *
 * @author DataOcean
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoleServiceImpl implements RoleService {

    private final RoleMapper roleMapper;

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：通过 LambdaQueryWrapper 筛选 status=1 的角色，按 ID 升序返回。
     * </p>
     */
    @Override
    public List<SysRole> listEnabledRoles() {
        log.debug("查询启用角色列表");
        // 构建查询条件：仅查询启用状态的角色，按 ID 升序排列
        List<SysRole> roles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getStatus, 1)
                .orderByAsc(SysRole::getId));
        log.debug("启用角色列表查询完成 count={}", roles.size());
        return roles;
    }
}
