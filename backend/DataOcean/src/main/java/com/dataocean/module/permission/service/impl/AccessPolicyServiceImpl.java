package com.dataocean.module.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.permission.entity.DatasourceAccessPolicy;
import com.dataocean.module.permission.entity.dto.AccessPolicyBatchDTO;
import com.dataocean.module.permission.entity.dto.AccessPolicyCreateDTO;
import com.dataocean.module.permission.entity.vo.AccessPolicyVO;
import com.dataocean.module.permission.enums.AccessType;
import com.dataocean.module.permission.enums.MaskStrategy;
import com.dataocean.module.permission.enums.SubjectType;
import com.dataocean.module.permission.mapper.DatasourceAccessPolicyMapper;
import com.dataocean.module.permission.service.AccessPolicyService;
import com.dataocean.module.permission.service.PermissionCalculator;
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
 * 行列级访问策略服务实现
 *
 * @author dataocean
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccessPolicyServiceImpl implements AccessPolicyService {

    private final DatasourceAccessPolicyMapper policyMapper;
    private final PermissionCalculator permissionCalculator;
    private final DatasourceMapper datasourceMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final DepartmentMapper departmentMapper;
    private final DbTableMetaMapper tableMetaMapper;
    private final DbColumnMetaMapper columnMetaMapper;

    @Transactional
    @Override
    public Long create(AccessPolicyCreateDTO dto) {
        validatePolicy(dto.getSubjectType(), dto.getAccessType(), dto.getMaskStrategy());
        validateDatasourceExists(dto.getDatasourceId());
        validateSubjectExists(dto.getSubjectType(), dto.getSubjectId());
        validateTableName(dto.getDatasourceId(), dto.getTableName());
        if (dto.getColumnName() != null && !dto.getColumnName().isBlank()) {
            validateColumnName(dto.getDatasourceId(), dto.getTableName(), dto.getColumnName());
        }
        if (dto.getRowFilterExpression() != null && !dto.getRowFilterExpression().isBlank()) {
            validateRowFilterExpression(dto.getRowFilterExpression());
        }

        DatasourceAccessPolicy policy = new DatasourceAccessPolicy();
        policy.setDatasourceId(dto.getDatasourceId());
        policy.setSubjectType(dto.getSubjectType());
        policy.setSubjectId(dto.getSubjectId());
        policy.setTableName(dto.getTableName());
        policy.setColumnName(dto.getColumnName());
        policy.setAccessType(dto.getAccessType());
        policy.setMaskStrategy(dto.getMaskStrategy());
        policy.setRowFilterExpression(dto.getRowFilterExpression());
        policy.setCreatedBy(UserContext.currentUserId());
        policyMapper.insert(policy);

        permissionCalculator.invalidate(dto.getSubjectId(), dto.getDatasourceId());
        log.info("策略创建成功 id={} datasourceId={} table={} column={}",
                policy.getId(), dto.getDatasourceId(), dto.getTableName(), dto.getColumnName());
        return policy.getId();
    }

    @Transactional
    @Override
    public int batchCreate(AccessPolicyBatchDTO dto) {
        validateSubjectType(dto.getSubjectType());
        validateDatasourceExists(dto.getDatasourceId());
        validateSubjectExists(dto.getSubjectType(), dto.getSubjectId());
        validateTableName(dto.getDatasourceId(), dto.getTableName());
        Long currentUserId = UserContext.currentUserId();
        int count = 0;

        for (AccessPolicyBatchDTO.PolicyItem item : dto.getPolicies()) {
            validateAccessType(item.getAccessType());
            if ("MASK".equals(item.getAccessType())) {
                if (item.getMaskStrategy() == null || item.getMaskStrategy().isBlank()) {
                    throw new BusinessException("脱敏策略不能为空");
                }
                validateMaskStrategy(item.getMaskStrategy());
            }

            DatasourceAccessPolicy policy = new DatasourceAccessPolicy();
            policy.setDatasourceId(dto.getDatasourceId());
            policy.setSubjectType(dto.getSubjectType());
            policy.setSubjectId(dto.getSubjectId());
            policy.setTableName(dto.getTableName());
            policy.setColumnName(item.getColumnName());
            policy.setAccessType(item.getAccessType());
            policy.setMaskStrategy(item.getMaskStrategy());
            policy.setRowFilterExpression(item.getRowFilterExpression());
            policy.setCreatedBy(currentUserId);
            policyMapper.insert(policy);
            count++;
        }

        log.info("批量策略创建成功 datasourceId={} table={} count={}",
                dto.getDatasourceId(), dto.getTableName(), count);
        permissionCalculator.invalidate(dto.getSubjectId(), dto.getDatasourceId());
        return count;
    }

    @Transactional
    @Override
    public void update(Long id, String accessType, String maskStrategy, String rowFilterExpression) {
        DatasourceAccessPolicy policy = policyMapper.selectById(id);
        if (policy == null) {
            throw new BusinessException("策略不存在");
        }
        if (accessType != null) {
            validateAccessType(accessType);
            policy.setAccessType(accessType);
        }
        // 确定最终的 accessType（可能是传入的新值，也可能是原值）
        String finalAccessType = accessType != null ? accessType : policy.getAccessType();
        if ("MASK".equals(finalAccessType)) {
            if (maskStrategy == null || maskStrategy.isBlank()) {
                throw new BusinessException("脱敏策略不能为空");
            }
            validateMaskStrategy(maskStrategy);
        }
        policy.setMaskStrategy(maskStrategy);
        policy.setRowFilterExpression(rowFilterExpression);
        policyMapper.updateById(policy);
        permissionCalculator.invalidate(policy.getSubjectId(), policy.getDatasourceId());
        log.info("策略更新成功 id={}", id);
    }

    @Transactional
    @Override
    public void delete(Long id) {
        DatasourceAccessPolicy policy = policyMapper.selectById(id);
        if (policy == null) {
            throw new BusinessException("策略不存在");
        }
        policyMapper.deleteById(id);
        permissionCalculator.invalidate(policy.getSubjectId(), policy.getDatasourceId());
        log.info("策略删除成功 id={} datasourceId={} table={}", id, policy.getDatasourceId(), policy.getTableName());
    }

    @Override
    public List<AccessPolicyVO> list(Long datasourceId, String subjectType, Long subjectId, String tableName) {
        return policyMapper.selectPolicies(datasourceId, subjectType, subjectId, tableName);
    }

    /**
     * 校验策略参数合法性
     */
    private void validatePolicy(String subjectType, String accessType, String maskStrategy) {
        validateSubjectType(subjectType);
        validateAccessType(accessType);
        if ("MASK".equals(accessType)) {
            if (maskStrategy == null || maskStrategy.isBlank()) {
                throw new BusinessException("脱敏策略不能为空");
            }
            validateMaskStrategy(maskStrategy);
        }
    }

    private void validateSubjectType(String subjectType) {
        try {
            SubjectType.valueOf(subjectType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的主体类型: " + subjectType);
        }
    }

    private void validateAccessType(String accessType) {
        try {
            AccessType.valueOf(accessType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的访问类型: " + accessType);
        }
    }

    private void validateMaskStrategy(String maskStrategy) {
        try {
            MaskStrategy.valueOf(maskStrategy);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的脱敏策略: " + maskStrategy);
        }
    }

    /**
     * 校验数据源存在
     */
    private void validateDatasourceExists(Long datasourceId) {
        Datasource ds = datasourceMapper.selectOne(new LambdaQueryWrapper<Datasource>()
                .eq(Datasource::getId, datasourceId)
                .eq(Datasource::getDeleted, 0L));
        if (ds == null) {
            throw new BusinessException("数据源不存在: " + datasourceId);
        }
    }

    /**
     * 校验授权主体存在
     */
    private void validateSubjectExists(String subjectType, Long subjectId) {
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

    /**
     * 校验表名在数据源元数据中存在（* 表示所有表，跳过校验）
     */
    private void validateTableName(Long datasourceId, String tableName) {
        if ("*".equals(tableName)) return;
        Long count = tableMetaMapper.selectCount(new LambdaQueryWrapper<DbTableMeta>()
                .eq(DbTableMeta::getDatasourceId, datasourceId)
                .eq(DbTableMeta::getTableName, tableName));
        if (count == 0) {
            throw new BusinessException("表名不存在: " + tableName);
        }
    }

    /**
     * 校验列名在数据源对应表的元数据中存在
     */
    private void validateColumnName(Long datasourceId, String tableName, String columnName) {
        Long count = columnMetaMapper.selectCount(new LambdaQueryWrapper<DbColumnMeta>()
                .eq(DbColumnMeta::getDatasourceId, datasourceId)
                .eq(DbColumnMeta::getTableName, tableName)
                .eq(DbColumnMeta::getColumnName, columnName));
        if (count == 0) {
            throw new BusinessException("列名不存在: " + tableName + "." + columnName);
        }
    }

    /**
     * 校验行级过滤表达式语法（基本检查：不为空且不含危险关键字）
     */
    private void validateRowFilterExpression(String expression) {
        String upper = expression.toUpperCase().trim();
        if (upper.contains("DROP ") || upper.contains("DELETE ") || upper.contains("INSERT ")
                || upper.contains("UPDATE ") || upper.contains("ALTER ") || upper.contains(";")) {
            throw new BusinessException("行级过滤表达式包含非法关键字");
        }
    }
}
