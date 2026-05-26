package com.dataocean.module.audit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.audit.entity.QuotaPolicy;
import com.dataocean.module.audit.entity.vo.QuotaCheckVO;
import com.dataocean.module.audit.mapper.QuotaPolicyMapper;
import com.dataocean.module.audit.service.LlmUsageService;
import com.dataocean.module.audit.service.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuotaServiceImpl implements QuotaService {

    private final QuotaPolicyMapper quotaPolicyMapper;
    private final LlmUsageService llmUsageService;

    @Override
    public QuotaCheckVO checkQuota(Long userId) {
        QuotaCheckVO result = new QuotaCheckVO();
        QuotaPolicy policy = quotaPolicyMapper.selectOne(
                new LambdaQueryWrapper<QuotaPolicy>()
                        .eq(QuotaPolicy::getSubjectType, QuotaPolicy.TYPE_USER)
                        .eq(QuotaPolicy::getSubjectId, userId)
                        .eq(QuotaPolicy::getEnabled, true)
        );
        if (policy == null || policy.getDailyQueryLimit() == null) {
            result.setAllowed(true);
            result.setReason(null);
            return result;
        }
        int usedToday = llmUsageService.getDailyQueryCount(userId);
        result.setUsedToday(usedToday);
        result.setDailyLimit(policy.getDailyQueryLimit());
        result.setRemaining(Math.max(0, policy.getDailyQueryLimit() - usedToday));
        if (usedToday >= policy.getDailyQueryLimit()) {
            result.setAllowed(false);
            result.setReason("已超出每日查询配额（" + policy.getDailyQueryLimit() + " 次/天）");
        } else {
            result.setAllowed(true);
        }
        return result;
    }

    @Override
    public QuotaPolicy createPolicy(QuotaPolicy policy) {
        policy.setCreatedAt(LocalDateTime.now());
        policy.setUpdatedAt(LocalDateTime.now());
        quotaPolicyMapper.insert(policy);
        return policy;
    }

    @Override
    public QuotaPolicy updatePolicy(Long id, QuotaPolicy policy) {
        QuotaPolicy existing = quotaPolicyMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "配额策略不存在");
        }
        existing.setDailyQueryLimit(policy.getDailyQueryLimit());
        existing.setMonthlyCostLimit(policy.getMonthlyCostLimit());
        existing.setEnabled(policy.getEnabled());
        existing.setUpdatedAt(LocalDateTime.now());
        quotaPolicyMapper.updateById(existing);
        return existing;
    }

    @Override
    public Page<QuotaPolicy> listPolicies(int page, int pageSize) {
        return quotaPolicyMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<QuotaPolicy>().orderByDesc(QuotaPolicy::getCreatedAt)
        );
    }
}