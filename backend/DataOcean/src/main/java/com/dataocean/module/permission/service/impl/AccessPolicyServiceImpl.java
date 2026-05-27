package com.dataocean.module.permission.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.entity.DatasourceAccessPolicy;
import com.dataocean.module.permission.entity.dto.AccessPolicyBatchDTO;
import com.dataocean.module.permission.entity.dto.AccessPolicyCreateDTO;
import com.dataocean.module.permission.entity.vo.AccessPolicyVO;
import com.dataocean.module.permission.enums.AccessType;
import com.dataocean.module.permission.enums.MaskStrategy;
import com.dataocean.module.permission.enums.SubjectType;
import com.dataocean.module.permission.mapper.DatasourceAccessPolicyMapper;
import com.dataocean.module.permission.service.AccessPolicyService;
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

    @Transactional
    @Override
    public Long create(AccessPolicyCreateDTO dto) {
        validatePolicy(dto.getSubjectType(), dto.getAccessType(), dto.getMaskStrategy());

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

        log.info("策略创建成功 id={} datasourceId={} table={} column={}",
                policy.getId(), dto.getDatasourceId(), dto.getTableName(), dto.getColumnName());
        return policy.getId();
    }

    @Transactional
    @Override
    public int batchCreate(AccessPolicyBatchDTO dto) {
        validateSubjectType(dto.getSubjectType());
        Long currentUserId = UserContext.currentUserId();
        int count = 0;

        for (AccessPolicyBatchDTO.PolicyItem item : dto.getPolicies()) {
            validateAccessType(item.getAccessType());
            if ("MASK".equals(item.getAccessType()) && item.getMaskStrategy() != null) {
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
        if (maskStrategy != null) {
            validateMaskStrategy(maskStrategy);
        }
        policy.setMaskStrategy(maskStrategy);
        policy.setRowFilterExpression(rowFilterExpression);
        policyMapper.updateById(policy);
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
}
