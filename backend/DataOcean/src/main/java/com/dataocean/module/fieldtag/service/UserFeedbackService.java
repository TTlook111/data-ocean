package com.dataocean.module.fieldtag.service;

import com.dataocean.module.fieldtag.entity.dto.FeedbackRequestDTO;
import com.dataocean.module.fieldtag.entity.vo.FeedbackVO;

/**
 * 用户反馈服务接口
 * <p>
 * 提供用户对查询结果中字段的反馈提交功能。
 * LIKE 类型直接触发可信度加分，DISLIKE 类型进入审核队列。
 * </p>
 */
public interface UserFeedbackService {

    /**
     * 提交用户反馈
     * <p>
     * LIKE：直接触发可信度 +10。
     * DISLIKE：检查 Redis 限频（同一用户同一字段每天最多 1 次），
     * 通过后创建反馈记录并进入审核队列。
     * </p>
     *
     * @param request 反馈请求参数
     * @return 反馈视图对象
     */
    FeedbackVO submitFeedback(FeedbackRequestDTO request);
}
