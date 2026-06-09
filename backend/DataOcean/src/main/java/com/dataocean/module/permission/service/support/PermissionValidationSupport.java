package com.dataocean.module.permission.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.permission.enums.AccessType;
import com.dataocean.module.permission.enums.MaskStrategy;
import com.dataocean.module.permission.enums.SubjectType;
import com.dataocean.module.user.entity.SysDepartment;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.DepartmentMapper;
import com.dataocean.module.user.mapper.RoleMapper;
import com.dataocean.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 权限校验支持类。
 * <p>
 * 封装权限模块共用的校验逻辑，避免在 DatasourcePermissionServiceImpl 和
 * AccessPolicyServiceImpl 中重复相同的代码。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class PermissionValidationSupport {

    private final DatasourceMapper datasourceMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final DepartmentMapper departmentMapper;

    /**
     * 校验主体类型是否合法
     *
     * @param subjectType 主体类型字符串
     * @throws BusinessException 主体类型不合法时抛出
     */
    public void validateSubjectType(String subjectType) {
        try {
            SubjectType.valueOf(subjectType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的主体类型: " + subjectType);
        }
    }

    /**
     * 校验访问类型是否合法
     *
     * @param accessType 访问类型字符串
     * @throws BusinessException 访问类型不合法时抛出
     */
    public void validateAccessType(String accessType) {
        try {
            AccessType.valueOf(accessType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的访问类型: " + accessType);
        }
    }

    /**
     * 校验脱敏策略是否合法
     *
     * @param maskStrategy 脱敏策略字符串
     * @throws BusinessException 脱敏策略不合法时抛出
     */
    public void validateMaskStrategy(String maskStrategy) {
        try {
            MaskStrategy.valueOf(maskStrategy);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的脱敏策略: " + maskStrategy);
        }
    }

    /**
     * 校验数据源是否存在且未删除
     *
     * @param datasourceId 数据源 ID
     * @throws BusinessException 数据源不存在时抛出
     */
    public void validateDatasourceExists(Long datasourceId) {
        Datasource ds = datasourceMapper.selectOne(new LambdaQueryWrapper<Datasource>()
                .eq(Datasource::getId, datasourceId)
                .eq(Datasource::getDeleted, 0L));
        if (ds == null) {
            throw new BusinessException("数据源不存在: " + datasourceId);
        }
    }

    /**
     * 校验授权主体（用户/角色/部门）是否存在
     *
     * @param subjectType 主体类型
     * @param subjectId   主体 ID
     * @throws BusinessException 主体不存在时抛出
     */
    public void validateSubjectExists(String subjectType, Long subjectId) {
        switch (SubjectType.valueOf(subjectType)) {
            case USER -> {
                SysUser user = userMapper.selectById(subjectId);
                if (user == null) throw new BusinessException("用户不存在: " + subjectId);
            }
            case ROLE -> {
                SysRole role = roleMapper.selectById(subjectId);
                if (role == null) throw new BusinessException("角色不存在: " + subjectId);
            }
            case DEPARTMENT -> {
                SysDepartment dept = departmentMapper.selectById(subjectId);
                if (dept == null) throw new BusinessException("部门不存在: " + subjectId);
            }
        }
    }
}
