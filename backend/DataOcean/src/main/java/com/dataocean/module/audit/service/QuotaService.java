package com.dataocean.module.audit.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.audit.entity.QuotaPolicy;
import com.dataocean.module.audit.entity.vo.QuotaCheckVO;

/**
 * 配额服务接口
 * <p>
 * 管理查询配额策略，在查询前检查用户是否超出配额。
 * </p>
 */
public interface QuotaService {

    /**
     * 检查用户配额
     *
     * @param userId 用户ID
     * @return 配额检查结果（是否允许 + 剩余额度）
     */
    QuotaCheckVO checkQuota(Long userId);

    /**
     * 创建配额策略
     *
     * @param policy 策略实体
     * @return 创建后的策略
     */
    QuotaPolicy createPolicy(QuotaPolicy policy);

    /**
     * 更新配额策略
     *
     * @param id     策略ID
     * @param policy 更新内容
     * @return 更新后的策略
     */
    QuotaPolicy updatePolicy(Long id, QuotaPolicy policy);

    /**
     * 分页查询配额策略列表
     *
     * @param page     页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    Page<QuotaPolicy> listPolicies(int page, int pageSize);
}
