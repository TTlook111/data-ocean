package com.dataocean.module.fieldtag.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.fieldtag.entity.vo.FeedbackVO;

/**
 * 反馈审核服务接口
 * <p>
 * 提供管理员对负向反馈的审核功能：列表查看、通过、驳回。
 * 审核通过后触发可信度扣分。
 * </p>
 */
public interface FeedbackReviewService {

    /**
     * 分页查询待审核反馈列表
     *
     * @param page     页码（从 1 开始）
     * @param pageSize 每页大小
     * @return 分页反馈列表
     */
    Page<FeedbackVO> listPendingReviews(int page, int pageSize);

    /**
     * 审核通过
     * <p>
     * 确认负向反馈有效，触发可信度 -15。
     * </p>
     *
     * @param feedbackId    反馈ID
     * @param reviewComment 审核意见
     */
    void approveFeedback(Long feedbackId, String reviewComment);

    /**
     * 审核驳回
     * <p>
     * 驳回负向反馈，不调整可信度。
     * </p>
     *
     * @param feedbackId    反馈ID
     * @param reviewComment 审核意见
     */
    void rejectFeedback(Long feedbackId, String reviewComment);
}
