package com.dataocean.module.fieldtag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.fieldtag.entity.FeedbackReview;
import com.dataocean.module.fieldtag.entity.FieldConfidenceEvent;
import com.dataocean.module.fieldtag.entity.UserFeedback;
import com.dataocean.module.fieldtag.entity.dto.FeedbackRequestDTO;
import com.dataocean.module.fieldtag.entity.vo.FeedbackVO;
import com.dataocean.module.fieldtag.mapper.FeedbackReviewMapper;
import com.dataocean.module.fieldtag.mapper.FieldConfidenceEventMapper;
import com.dataocean.module.fieldtag.mapper.UserFeedbackMapper;
import com.dataocean.module.fieldtag.service.ConfidenceCalculator;
import com.dataocean.module.fieldtag.service.UserFeedbackService;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 用户反馈服务实现类
 * <p>
 * 处理用户反馈提交逻辑：
 * - LIKE：直接触发可信度 +10
 * - DISLIKE：Redis 限频检查 → 创建反馈 → 进入审核队列 → 群体阈值检测
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserFeedbackServiceImpl implements UserFeedbackService {

    /** Redis 限频 key 前缀 */
    private static final String RATE_LIMIT_KEY_PREFIX = "feedback:neg:";
    /** 限频 TTL：24 小时 */
    private static final long RATE_LIMIT_TTL_HOURS = 24;
    /** 群体阈值：不同用户踩数达到此值触发自动降级 */
    private static final int GROUP_THRESHOLD_COUNT = 3;

    private final UserFeedbackMapper feedbackMapper;
    private final FeedbackReviewMapper reviewMapper;
    private final FieldConfidenceEventMapper eventMapper;
    private final DbColumnMetaMapper dbColumnMetaMapper;
    private final ConfidenceCalculator confidenceCalculator;
    private final StringRedisTemplate stringRedisTemplate;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FeedbackVO submitFeedback(FeedbackRequestDTO request) {
        Long userId = UserContext.currentUserId();
        // 校验反馈类型
        if (!UserFeedback.TYPE_LIKE.equals(request.getFeedbackType())
                && !UserFeedback.TYPE_DISLIKE.equals(request.getFeedbackType())) {
            throw new BusinessException("无效的反馈类型，仅支持 LIKE/DISLIKE");
        }
        if (dbColumnMetaMapper.selectById(request.getColumnMetaId()) == null) {
            throw new BusinessException("字段不存在");
        }
        Long sameFeedbackCount = feedbackMapper.selectCount(
                new LambdaQueryWrapper<UserFeedback>()
                        .eq(UserFeedback::getUserId, userId)
                        .eq(UserFeedback::getQueryTaskId, request.getQueryTaskId())
                        .eq(UserFeedback::getColumnMetaId, request.getColumnMetaId())
                        .eq(UserFeedback::getFeedbackType, request.getFeedbackType())
        );
        if (sameFeedbackCount > 0) {
            throw new BusinessException("该反馈已提交，请勿重复操作");
        }
        // 创建反馈记录
        UserFeedback feedback = new UserFeedback();
        feedback.setQueryTaskId(request.getQueryTaskId());
        feedback.setColumnMetaId(request.getColumnMetaId());
        feedback.setUserId(userId);
        feedback.setFeedbackType(request.getFeedbackType());
        feedback.setReasonCode(request.getReasonCode());
        feedback.setComment(request.getComment());
        feedback.setCreatedAt(LocalDateTime.now());

        if (UserFeedback.TYPE_LIKE.equals(request.getFeedbackType())) {
            // 点赞：直接触发可信度加分
            insertFeedback(feedback);
            confidenceCalculator.adjustScore(
                    request.getColumnMetaId(),
                    FieldConfidenceEvent.TYPE_USER_LIKE,
                    userId,
                    request.getQueryTaskId()
            );
            log.info("用户点赞 userId={} columnMetaId={}", userId, request.getColumnMetaId());
        } else {
            // 点踩：检查限频
            String rateLimitKey = RATE_LIMIT_KEY_PREFIX + userId + ":" + request.getColumnMetaId();
            Boolean exists = stringRedisTemplate.hasKey(rateLimitKey);
            if (Boolean.TRUE.equals(exists)) {
                throw new BusinessException("您今天已对该字段提交过负向反馈，请明天再试");
            }
            // 设置限频 key
            stringRedisTemplate.opsForValue().set(rateLimitKey, "1", RATE_LIMIT_TTL_HOURS, TimeUnit.HOURS);
            // 保存反馈
            insertFeedback(feedback);
            // 创建审核记录
            FeedbackReview review = new FeedbackReview();
            review.setFeedbackId(feedback.getId());
            review.setReviewStatus(FeedbackReview.STATUS_PENDING);
            reviewMapper.insert(review);
            // 群体阈值检测
            checkGroupThreshold(request.getColumnMetaId());
            log.info("用户点踩 userId={} columnMetaId={} 已进入审核队列", userId, request.getColumnMetaId());
        }
        return toVO(feedback);
    }

    private void insertFeedback(UserFeedback feedback) {
        try {
            feedbackMapper.insert(feedback);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该反馈已提交，请勿重复操作");
        }
    }

    /**
     * 群体阈值检测
     * <p>
     * 查询近 7 天内不同用户对该字段的 DISLIKE 数量，
     * 达到阈值且无已通过的审核记录时，自动触发可信度降级。
     * </p>
     */
    private void checkGroupThreshold(Long columnMetaId) {
        // 查询近 7 天内不同用户的 DISLIKE 数量
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        List<Object> distinctUsers = feedbackMapper.selectObjs(
                new LambdaQueryWrapper<UserFeedback>()
                        .select(UserFeedback::getUserId)
                        .eq(UserFeedback::getColumnMetaId, columnMetaId)
                        .eq(UserFeedback::getFeedbackType, UserFeedback.TYPE_DISLIKE)
                        .ge(UserFeedback::getCreatedAt, sevenDaysAgo)
                        .groupBy(UserFeedback::getUserId)
        );
        if (distinctUsers.size() < GROUP_THRESHOLD_COUNT) {
            return;
        }
        // 检查近 7 天内是否已触发过 GROUP_THRESHOLD 事件（避免重复触发）
        Long thresholdEventCount = eventMapper.selectCount(
                new LambdaQueryWrapper<FieldConfidenceEvent>()
                        .eq(FieldConfidenceEvent::getColumnMetaId, columnMetaId)
                        .eq(FieldConfidenceEvent::getEventType, FieldConfidenceEvent.TYPE_GROUP_THRESHOLD)
                        .ge(FieldConfidenceEvent::getCreatedAt, sevenDaysAgo)
        );
        if (thresholdEventCount > 0) {
            return;
        }
        // 查询近 7 天内该字段的 DISLIKE 反馈 ID 列表
        List<Object> recentFeedbackIds = feedbackMapper.selectObjs(
                new LambdaQueryWrapper<UserFeedback>()
                        .select(UserFeedback::getId)
                        .eq(UserFeedback::getColumnMetaId, columnMetaId)
                        .eq(UserFeedback::getFeedbackType, UserFeedback.TYPE_DISLIKE)
                        .ge(UserFeedback::getCreatedAt, sevenDaysAgo)
        );
        if (recentFeedbackIds.isEmpty()) {
            return;
        }
        // 检查是否已有 APPROVED 审核记录（已有人工介入则不自动降级）
        List<Long> feedbackIdList = recentFeedbackIds.stream()
                .map(obj -> ((Number) obj).longValue())
                .toList();
        Long approvedCount = reviewMapper.selectCount(
                new LambdaQueryWrapper<FeedbackReview>()
                        .eq(FeedbackReview::getReviewStatus, FeedbackReview.STATUS_APPROVED)
                        .in(FeedbackReview::getFeedbackId, feedbackIdList)
        );
        if (approvedCount > 0) {
            return;
        }
        // 触发群体阈值降级
        confidenceCalculator.adjustScore(
                columnMetaId,
                FieldConfidenceEvent.TYPE_GROUP_THRESHOLD,
                null,
                null
        );
        // 发布告警事件
        eventPublisher.publishEvent(new GroupThresholdAlertEvent(columnMetaId, distinctUsers.size()));
        log.warn("群体阈值触发 columnMetaId={} 不同用户踩数={}", columnMetaId, distinctUsers.size());
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

    /**
     * 群体阈值告警事件
     * <p>
     * 通过 Spring ApplicationEvent 发布，后续可对接通知模块。
     * </p>
     */
    public record GroupThresholdAlertEvent(Long columnMetaId, int dislikeUserCount) {
    }
}
