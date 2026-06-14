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
import com.dataocean.module.permission.entity.PermissionChangeLog;
import com.dataocean.module.permission.entity.dto.AccessPolicyBatchDTO;
import com.dataocean.module.permission.entity.dto.AccessPolicyCreateDTO;
import com.dataocean.module.permission.entity.vo.AccessPolicyVO;
import com.dataocean.module.permission.enums.AccessType;
import com.dataocean.module.permission.enums.MaskStrategy;
import com.dataocean.module.permission.enums.SubjectType;
import com.dataocean.module.permission.event.PermissionChangedEvent;
import com.dataocean.module.permission.mapper.DatasourceAccessPolicyMapper;
import com.dataocean.module.permission.mapper.PermissionChangeLogMapper;
import com.dataocean.module.permission.service.AccessPolicyService;
import com.dataocean.module.user.entity.SysDepartment;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.DepartmentMapper;
import com.dataocean.module.user.mapper.RoleMapper;
import com.dataocean.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final PermissionChangeLogMapper changeLogMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final DatasourceMapper datasourceMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final DepartmentMapper departmentMapper;
    private final DbTableMetaMapper tableMetaMapper;
    private final DbColumnMetaMapper columnMetaMapper;
    private final com.dataocean.module.metadata.service.SchemaSnapshotService schemaSnapshotService;
    private final com.dataocean.module.permission.service.support.PermissionValidationSupport validationSupport;

    @Transactional
    @Override
    public Long create(AccessPolicyCreateDTO dto) {
        validatePolicy(dto.getSubjectType(), dto.getAccessType(), dto.getMaskStrategy(), dto.getColumnName());
        validationSupport.validateDatasourceExists(dto.getDatasourceId());
        validationSupport.validateSubjectExists(dto.getSubjectType(), dto.getSubjectId());
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

        logChange(PermissionChangeLog.TYPE_CREATE, PermissionChangeLog.TARGET_POLICY,
                policy.getId(), dto.getSubjectType(), dto.getSubjectId(),
                dto.getDatasourceId(), null, policy);

        eventPublisher.publishEvent(new PermissionChangedEvent(this, dto.getSubjectId(), dto.getDatasourceId()));
        log.info("策略创建成功 id={} datasourceId={} table={} column={}",
                policy.getId(), dto.getDatasourceId(), dto.getTableName(), dto.getColumnName());
        return policy.getId();
    }

    @Transactional
    @Override
    public int batchCreate(AccessPolicyBatchDTO dto) {
        validationSupport.validateSubjectType(dto.getSubjectType());
        validationSupport.validateDatasourceExists(dto.getDatasourceId());
        validationSupport.validateSubjectExists(dto.getSubjectType(), dto.getSubjectId());
        validateTableName(dto.getDatasourceId(), dto.getTableName());
        Long currentUserId = UserContext.currentUserId();
        int count = 0;

        for (AccessPolicyBatchDTO.PolicyItem item : dto.getPolicies()) {
            validationSupport.validateAccessType(item.getAccessType());
            if ("MASK".equals(item.getAccessType())) {
                if (item.getColumnName() == null || item.getColumnName().isBlank()) {
                    throw new BusinessException("脱敏策略必须指定列名，不支持表级脱敏");
                }
                if (item.getMaskStrategy() == null || item.getMaskStrategy().isBlank()) {
                    throw new BusinessException("脱敏策略不能为空");
                }
                validationSupport.validateMaskStrategy(item.getMaskStrategy());
            }
            if (item.getColumnName() != null && !item.getColumnName().isBlank()) {
                validateColumnName(dto.getDatasourceId(), dto.getTableName(), item.getColumnName());
            }
            if (item.getRowFilterExpression() != null && !item.getRowFilterExpression().isBlank()) {
                validateRowFilterExpression(item.getRowFilterExpression());
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
        eventPublisher.publishEvent(new PermissionChangedEvent(this, dto.getSubjectId(), dto.getDatasourceId()));
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
            validationSupport.validateAccessType(accessType);
            policy.setAccessType(accessType);
        }
        // 确定最终的 accessType（可能是传入的新值，也可能是原值）
        String finalAccessType = accessType != null ? accessType : policy.getAccessType();
        if ("MASK".equals(finalAccessType)) {
            // MASK 必须是列级策略，禁止把表级策略改成 MASK
            if (policy.getColumnName() == null || policy.getColumnName().isBlank()) {
                throw new BusinessException("脱敏策略必须指定列名，不能将表级策略改为脱敏");
            }
            if (maskStrategy == null || maskStrategy.isBlank()) {
                throw new BusinessException("脱敏策略不能为空");
            }
            validationSupport.validateMaskStrategy(maskStrategy);
        }
        if (rowFilterExpression != null && !rowFilterExpression.isBlank()) {
            validateRowFilterExpression(rowFilterExpression);
        }
        DatasourceAccessPolicy oldPolicy = policyMapper.selectById(id);
        policy.setMaskStrategy(maskStrategy);
        policy.setRowFilterExpression(rowFilterExpression);
        policyMapper.updateById(policy);

        logChange(PermissionChangeLog.TYPE_UPDATE, PermissionChangeLog.TARGET_POLICY,
                id, policy.getSubjectType(), policy.getSubjectId(),
                policy.getDatasourceId(), oldPolicy, policy);

        eventPublisher.publishEvent(new PermissionChangedEvent(this, policy.getSubjectId(), policy.getDatasourceId()));
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

        logChange(PermissionChangeLog.TYPE_DELETE, PermissionChangeLog.TARGET_POLICY,
                id, policy.getSubjectType(), policy.getSubjectId(),
                policy.getDatasourceId(), policy, null);

        eventPublisher.publishEvent(new PermissionChangedEvent(this, policy.getSubjectId(), policy.getDatasourceId()));
        log.info("策略删除成功 id={} datasourceId={} table={}", id, policy.getDatasourceId(), policy.getTableName());
    }

    @Override
    public List<AccessPolicyVO> list(Long datasourceId, String subjectType, Long subjectId, String tableName) {
        return policyMapper.selectPolicies(datasourceId, subjectType, subjectId, tableName);
    }

    /**
     * 校验策略参数合法性
     */
    private void validatePolicy(String subjectType, String accessType, String maskStrategy, String columnName) {
        validationSupport.validateSubjectType(subjectType);
        validationSupport.validateAccessType(accessType);
        if ("MASK".equals(accessType)) {
            if (columnName == null || columnName.isBlank()) {
                throw new BusinessException("脱敏策略必须指定列名，不支持表级脱敏");
            }
            if (maskStrategy == null || maskStrategy.isBlank()) {
                throw new BusinessException("脱敏策略不能为空");
            }
            validationSupport.validateMaskStrategy(maskStrategy);
        }
    }

    /**
     * 校验表名在数据源当前发布快照的元数据中存在（* 表示所有表，跳过校验）
     */
    private void validateTableName(Long datasourceId, String tableName) {
        if ("*".equals(tableName)) return;
        LambdaQueryWrapper<DbTableMeta> query = new LambdaQueryWrapper<DbTableMeta>()
                .eq(DbTableMeta::getDatasourceId, datasourceId)
                .eq(DbTableMeta::getTableName, tableName);
        // 限定到当前发布快照
        var snapshot = schemaSnapshotService.getPublishedSnapshot(datasourceId);
        if (snapshot != null) {
            query.eq(DbTableMeta::getSnapshotId, snapshot.getId());
        }
        Long count = tableMetaMapper.selectCount(query);
        if (count == 0) {
            throw new BusinessException("表名不存在: " + tableName);
        }
    }

    /**
     * 校验列名在数据源当前发布快照对应表的元数据中存在
     */
    private void validateColumnName(Long datasourceId, String tableName, String columnName) {
        LambdaQueryWrapper<DbColumnMeta> query = new LambdaQueryWrapper<DbColumnMeta>()
                .eq(DbColumnMeta::getDatasourceId, datasourceId)
                .eq(DbColumnMeta::getTableName, tableName)
                .eq(DbColumnMeta::getColumnName, columnName);
        var snapshot = schemaSnapshotService.getPublishedSnapshot(datasourceId);
        if (snapshot != null) {
            query.eq(DbColumnMeta::getSnapshotId, snapshot.getId());
        }
        Long count = columnMetaMapper.selectCount(query);
        if (count == 0) {
            throw new BusinessException("列名不存在: " + tableName + "." + columnName);
        }
    }

    /**
     * 校验行级过滤表达式安全性。
     * <p>
     * 采用严格黑名单 + 结构检查，防止 SQL 注入：
     * 1. 禁止注释语法（--、/*、#）
     * 2. 禁止分号（多语句执行）
     * 3. 禁止子查询（SELECT 关键字）
     * 4. 禁止危险关键字（UNION、INTO、OUTFILE、LOAD_FILE 等）
     * 5. 禁止括号嵌套超过 2 层
     * 6. 长度限制 500 字符
     * </p>
     */
    private void validateRowFilterExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new BusinessException("行级过滤表达式不能为空");
        }
        if (expression.length() > 500) {
            throw new BusinessException("行级过滤表达式长度不能超过 500 字符");
        }

        String upper = expression.toUpperCase().trim();

        // 禁止注释语法
        if (upper.contains("--") || upper.contains("/*") || upper.contains("*/") || upper.contains("#")) {
            throw new BusinessException("行级过滤表达式不允许包含注释语法");
        }

        // 禁止分号（多语句）
        if (expression.contains(";")) {
            throw new BusinessException("行级过滤表达式不允许包含分号");
        }

        // 禁止子查询和危险关键字（使用单词边界匹配，避免误伤列名）
        String[] forbidden = {
                "SELECT", "DROP", "DELETE", "INSERT", "UPDATE", "ALTER", "CREATE",
                "UNION", "INTO", "OUTFILE", "DUMPFILE", "LOAD_FILE", "EXEC", "EXECUTE",
                "CALL", "SET", "GRANT", "REVOKE", "TRUNCATE", "RENAME",
                "INFORMATION_SCHEMA", "SLEEP", "BENCHMARK", "SYSTEM"
        };
        for (String keyword : forbidden) {
            // 使用正则匹配完整单词，避免列名包含关键字时误报
            if (upper.matches(".*\\b" + keyword + "\\b.*")) {
                throw new BusinessException("行级过滤表达式包含非法关键字：" + keyword);
            }
        }

        // 禁止括号嵌套超过 2 层（允许 IN (1,2,3) 但禁止子查询结构）
        int depth = 0;
        int maxDepth = 0;
        for (char c : expression.toCharArray()) {
            if (c == '(') {
                depth++;
                maxDepth = Math.max(maxDepth, depth);
            } else if (c == ')') {
                depth--;
            }
            if (depth < 0) {
                throw new BusinessException("行级过滤表达式括号不匹配");
            }
        }
        if (depth != 0) {
            throw new BusinessException("行级过滤表达式括号不匹配");
        }
        if (maxDepth > 2) {
            throw new BusinessException("行级过滤表达式括号嵌套不能超过 2 层");
        }
    }

    /**
     * 记录权限变更审计日志
     */
    private void logChange(String changeType, String targetType, Long targetId,
                            String subjectType, Long subjectId, Long datasourceId,
                            Object oldValue, Object newValue) {
        try {
            PermissionChangeLog logEntry = new PermissionChangeLog();
            logEntry.setChangeType(changeType);
            logEntry.setTargetType(targetType);
            logEntry.setTargetId(targetId);
            logEntry.setSubjectType(subjectType);
            logEntry.setSubjectId(subjectId);
            logEntry.setDatasourceId(datasourceId);
            logEntry.setOperatorId(UserContext.currentUserId());
            if (oldValue != null) {
                logEntry.setOldValue(com.fasterxml.jackson.databind.ObjectMapper.class
                        .getDeclaredConstructor().newInstance().writeValueAsString(oldValue));
            }
            if (newValue != null) {
                logEntry.setNewValue(com.fasterxml.jackson.databind.ObjectMapper.class
                        .getDeclaredConstructor().newInstance().writeValueAsString(newValue));
            }
            changeLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.warn("权限变更审计日志记录失败: {}", e.getMessage());
        }
    }
}
