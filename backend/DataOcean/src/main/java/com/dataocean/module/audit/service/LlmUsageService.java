package com.dataocean.module.audit.service;

import com.dataocean.module.audit.entity.vo.LlmUsageStatsVO;

import java.math.BigDecimal;

/**
 * LLM 使用量服务接口
 * <p>
 * 记录和查询 LLM API 调用的 Token 消耗和费用。
 * </p>
 */
public interface LlmUsageService {

    /**
     * 记录单次 LLM 调用
     *
     * @param queryTaskId      查询任务ID
     * @param provider         提供商
     * @param model            模型名称
     * @param promptTokens     Prompt Token 数
     * @param completionTokens Completion Token 数
     * @param costAmount       费用（元）
     */
    void recordUsage(Long queryTaskId, String provider, String model,
                     int promptTokens, int completionTokens, BigDecimal costAmount);

    /**
     * 查询用户当日使用量
     *
     * @param userId 用户ID
     * @return 当日查询次数
     */
    int getDailyQueryCount(Long userId);

    /**
     * 查询月度使用统计
     *
     * @param days 统计天数
     * @return 使用统计
     */
    LlmUsageStatsVO getUsageStats(int days);
}
