package com.dataocean.module.fieldtag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.fieldtag.entity.FeedbackReview;
import com.dataocean.module.fieldtag.entity.FieldConfidenceEvent;
import com.dataocean.module.fieldtag.entity.UserFeedback;
import com.dataocean.module.fieldtag.entity.vo.FeedbackVO;
import com.dataocean.module.fieldtag.mapper.FeedbackReviewMapper;
import com.dataocean.module.fieldtag.mapper.UserFeedbackMapper;
import com.dataocean.module.fieldtag.service.ConfidenceCalculator;
import com.dataocean.module.fieldtag.service.FeedbackReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 反馈审核服务实现类
 * <p>
 * 管理员审核负向反馈：通过后触发可信度扣分，驳回则不调整。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeedbackReviewServiceImpl implements FeedbackReviewService {

    private final FeedbackReviewMapper reviewMapper;
    private final UserFeedbackMapper feedbackMapper;
    private final ConfidenceCalculator confidenceCalculator;

    /**
     * {@inheritDoc}
     */
    @Override
    public Page<FeedbackVO> listPendingReviews(int page, int pageSize) {
        // 查询待审核的审核记录
        Page<FeedbackReview> reviewPage = reviewMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<FeedbackReview>()
                        .eq(FeedbackReview::getReviewStatus, FeedbackReview.STATUS_PENDING)
                        .orderByAsc(FeedbackReview::getId)
        );
        // 转换为 FeedbackVO
        Page<FeedbackVO> resultPage = new Page<>(page, pageSize, reviewPage.getTotal());
        List<FeedbackVO> voList = reviewPage.getRecords().stream()
                .map(review -> {
                    UserFeedback feedback = feedbackMapper.selectById(review.getFeedbackId());
                    if (feedback == null) {
                        return null;
                    }
                    FeedbackVO vo = toVO(feedback);
                    vo.setReviewStatus(review.getReviewStatus());
                    return vo;
                })
                .filter(vo -> vo != null)
                .collect(Collectors.toList());
        resultPage.setRecords(voList);
        return resultPage;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveFeedback(Long feedbackId, String reviewComment) {
        // 查找审核记录
        FeedbackReview review = getReviewByFeedbackId(feedbackId);
        if (!FeedbackReview.STATUS_PENDING.equals(review.getReviewStatus())) {
            throw new BusinessException("该反馈已审核，不可重复操作");
        }
        // 更新审核状态
        review.setReviewStatus(FeedbackReview.STATUS_APPROVED);
        review.setReviewerId(UserContext.currentUserId());
        review.setReviewComment(reviewComment);
        review.setHandledAt(LocalDateTime.now());
        reviewMapper.updateById(review);
        // 触发可信度扣分
        UserFeedback feedback = feedbackMapper.selectById(feedbackId);
        if (feedback != null) {
            confidenceCalculator.adjustScore(
                    feedback.getColumnMetaId(),
                    FieldConfidenceEvent.TYPE_USER_DISLIKE_CONFIRMED,
                    UserContext.currentUserId(),
                    feedback.getQueryTaskId()
            );
        }
        log.info("审核通过 feedbackId={} reviewerId={}", feedbackId, UserContext.currentUserId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectFeedback(Long feedbackId, String reviewComment) {
        // 查找审核记录
        FeedbackReview review = getReviewByFeedbackId(feedbackId);
        if (!FeedbackReview.STATUS_PENDING.equals(review.getReviewStatus())) {
            throw new BusinessException("该反馈已审核，不可重复操作");
        }
        // 更新审核状态
        review.setReviewStatus(FeedbackReview.STATUS_REJECTED);
        review.setReviewerId(UserContext.currentUserId());
        review.setReviewComment(reviewComment);
        review.setHandledAt(LocalDateTime.now());
        reviewMapper.updateById(review);
        log.info("审核驳回 feedbackId={} reviewerId={}", feedbackId, UserContext.currentUserId());
    }

    /**
     * 根据反馈ID查找审核记录
     */
    private FeedbackReview getReviewByFeedbackId(Long feedbackId) {
        FeedbackReview review = reviewMapper.selectOne(
                new LambdaQueryWrapper<FeedbackReview>()
                        .eq(FeedbackReview::getFeedbackId, feedbackId)
        );
        if (review == null) {
            throw new BusinessException("审核记录不存在，feedbackId=" + feedbackId);
        }
        return review;
    }

    /**
     * 实体转视图对象
     */
    private FeedbackVO toVO(UserFeedback feedback) {
        FeedbackVO vo = new FeedbackVO();
        vo.setId(feedback.getId());
        vo.setQueryTaskId(feedback.getQueryTaskId());
        vo.setColumnMetaId(feedback.getColumnMetaId());
        vo.setUserId(feedback.getUserId());
        vo.setFeedbackType(feedback.getFeedbackType());
        vo.setReasonCode(feedback.getReasonCode());
        vo.setComment(feedback.getComment());
        vo.setCreatedAt(feedback.getCreatedAt());
        return vo;
    }
}
