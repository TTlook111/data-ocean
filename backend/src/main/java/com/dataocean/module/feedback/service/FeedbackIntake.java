package com.dataocean.module.feedback.service;

import com.dataocean.infrastructure.DataOceanProperties;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.feedback.mapper.FeedbackMapper;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Event;
import com.dataocean.module.iam.entity.po.OutboxPO;
import com.dataocean.module.iam.mapper.OutboxMapper;
import com.dataocean.module.iam.service.OutboxLocalConsumer;
import com.dataocean.module.query.entity.vo.QueryAnswerRefVO;
import com.dataocean.module.query.mapper.QueryRequestMapper;

import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 从已提交评价可靠生成待分派事项，通道送达与业务关联各自留痕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackIntake implements OutboxLocalConsumer {

    /** 共用可靠事件中的评价类型。 */
    private static final String TYPE = "feedback.rating.recorded";

    /** 每条成功入队的原事件取得唯一的本地消费回执。 */
    private static final String CONSUMER = "FEEDBACK_ISSUE_INTAKE";

    /** 权威评价、事项和关联。 */
    private final FeedbackMapper feedback;

    /** 复用问数原轮次作为同一回答的串行边界。 */
    private final QueryRequestMapper requests;

    /** 共用派发、重试和消费回执。 */
    private final OutboxMapper outbox;

    /** 消费与补偿均使用短事务。 */
    private final TransactionTemplate transactions;

    /** 通道载荷仅携带动作。 */
    private final JsonCodec json;

    /** 与共用派发器使用同一开关。 */
    private final DataOceanProperties properties;

    /** 确切匹配评价事件类型。 */
    @Override
    public String eventType() {
        return TYPE;
    }

    /** 与原评价同事务写入，补偿时沿用同一事实。 */
    void enqueue(Event event) {
        var payload = json.write(Map.of("action", event.action()));
        outbox.appendAt(new OutboxPO(
                event.id(),
                TYPE,
                1,
                "feedback_rating",
                event.answerId() + ":" + event.answerRevision(),
                event.sequence(),
                event.commandId(),
                payload,
                null,
                0,
                null,
                null,
                null,
                event.occurredAt(),
                null,
                null));
    }

    /** 当前派发租约下创建或关联事项，失败由共用派发器重试。 */
    @Override
    public boolean consume(OutboxPO delivery) {
        var event = readEvent(delivery.getEventId());
        if (!"DISLIKE".equals(event.action())) {
            // 点赞和撤销只发布评价变更。
            return true;
        }
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var request = requests.lock(event.answerId());
            var lease = outbox.lockEvent(event.id());
            if (lease == null
                    || !"CLAIMED".equals(lease.getDeliveryState())
                    || !Objects.equals(lease.getLeaseToken(), delivery.getLeaseToken())
                    || !lease.getLeasedUntil().isAfter(feedback.now())) {
                // 旧租约不能提交或确认后继领取的副作用。
                return false;
            }
            var ref = new QueryAnswerRefVO(event.answerId(), event.answerRevision());
            if (request == null || request.userId() != event.userId()) {
                // 归属异常留待核对，不能生成错误事项。
                throw inconsistent();
            }
            var linked = feedback.linkedIssue(event.id());
            var issue = feedback.issueId(ref);
            boolean received = outbox.hasReceipt(event.id(), CONSUMER);
            if (linked != null) {
                // 吸收提交后重投，核实同一原事项后可补齐缺失回执。
                if (!linked.equals(issue)) {
                    throw inconsistent();
                }
            } else if (received) {
                // 回执先于实际关联出现时停止，保留可诊断记录。
                throw inconsistent();
            } else {
                if (issue == null) {
                    // 每个原回答版本只创建第一个待分派事项。
                    feedback.createIssue(request.sourceId(), ref);
                    issue = feedback.issueId(ref);
                } else {
                    // 新证据移动更新时间与事项版本，保持原状态。
                    feedback.touchIssue(issue);
                }
                feedback.link(event.id(), issue);
            }
            if (!received) {
                // 回执与事项及关联在同一事务提交。
                outbox.receipt(event.id(), CONSUMER);
            }
            return true;
        }));
    }

    /** 核对已投递状态和原事件，为未完成入队的记录补投。 */
    @Scheduled(fixedDelay = 15000)
    public void reconcile() {
        if (!properties.isOutboxEnabled()) {
            // 与共用派发器保持同一运行边界。
            return;
        }
        try {
            var backlog = feedback.backlog();
            if (backlog.pending() > 0) {
                // 诊断只记录队列规模及最早受理时刻。
                log.warn("反馈等待入队，pending={}，oldestUtc={}", backlog.pending(), backlog.oldest());
            }
            for (var id : feedback.reconcileCandidates()) {
                // 单条异常不阻断同一批次其他入队。
                reconcile(id);
            }
        } catch (DataAccessException unavailable) {
            log.warn("反馈队列对账暂不可用，errorType={}", unavailable.getClass().getSimpleName());
        }
    }

    /** 重新派发已有原事件，保护当前持有的租约。 */
    private void reconcile(String id) {
        try {
            var event = readEvent(id);
            transactions.executeWithoutResult(status -> {
                requests.lock(event.answerId());
                var dispatched = outbox.lockEvent(id);
                var linked = feedback.linkedIssue(id);
                boolean received = outbox.hasReceipt(id, CONSUMER);
                if (dispatched == null && linked == null) {
                    // 原评价仍在时用原编号还原尚无关联的待派发记录。
                    enqueue(event);
                } else if (dispatched != null
                        && "DELIVERED".equals(dispatched.getDeliveryState())
                        && (linked == null || !received)) {
                    // 仅重新领取已投递且业务事实仍不完整的事件。
                    if (linked == null && received) {
                        throw inconsistent();
                    }
                    outbox.requeue(id);
                }
            });
        } catch (DataAccessException unavailable) {
            log.warn(
                    "反馈入队待核对，eventId={}，errorType={}",
                    id,
                    unavailable.getClass().getSimpleName());
        }
    }

    /** 事件内容以 MySQL 原评价为准。 */
    private Event readEvent(String id) {
        var event = feedback.event(id);
        if (event == null) {
            // 缺失权威事件时交由既有重试和诊断处理。
            throw inconsistent();
        }
        return event;
    }

    /** 诊断不带用户原文或数据库异常内容。 */
    private DataIntegrityViolationException inconsistent() {
        return new DataIntegrityViolationException("反馈事件与原回答或入队记录不一致");
    }
}
