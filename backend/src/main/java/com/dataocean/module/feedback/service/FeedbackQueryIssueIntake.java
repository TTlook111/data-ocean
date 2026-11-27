package com.dataocean.module.feedback.service;

import com.dataocean.common.Ids;
import com.dataocean.infrastructure.DataOceanProperties;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.feedback.mapper.FeedbackMapper;
import com.dataocean.module.feedback.mapper.FeedbackQueryIssueMapper;
import com.dataocean.module.iam.entity.po.OutboxPO;
import com.dataocean.module.iam.mapper.OutboxMapper;
import com.dataocean.module.iam.service.OutboxLocalConsumer;
import com.dataocean.module.query.mapper.QueryRequestMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper.Request;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 从已提交的原问失败可靠接入事项，终态与后台派发互不覆盖。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackQueryIssueIntake implements OutboxLocalConsumer {

    /** 对外来源名称对应两条不同的自动业务路径。 */
    public static final String KNOWLEDGE_GAP = "QUERY_KNOWLEDGE_GAP";

    public static final String TECHNICAL_FAILURE = "QUERY_TECHNICAL_FAILURE";
    private static final String TYPE = "feedback.query.failure.recorded";
    private static final String CONSUMER = "FEEDBACK_QUERY_ISSUE";

    /** 原问及其待派发标记与后台证据台账保持明确引用。 */
    private final QueryRequestMapper requests;

    private final FeedbackQueryIssueMapper issues;
    private final FeedbackMapper feedback;
    /** 可靠通道继续使用已部署的本地消费回执和 Redis Streams。 */
    private final OutboxMapper outbox;

    private final TransactionTemplate transactions;
    private final JsonCodec json;
    private final Fingerprints fingerprints;
    private final DataOceanProperties properties;

    /** 只消费问数失败这一固定类型。 */
    @Override
    public String eventType() {
        return TYPE;
    }

    /** 从终态标记分批推进 Outbox，短时依赖故障保留待派发事实。 */
    @Scheduled(fixedDelay = 1200)
    void promote() {
        if (!properties.isOutboxEnabled()) {
            return;
        }
        try {
            for (var id : requests.pendingIssueRequests()) {
                // 单个请求的异常不阻断同批其他线索。
                try {
                    transactions.executeWithoutResult(status -> enqueue(requests.lock(id)));
                } catch (DataAccessException unavailable) {
                    log.warn(
                            "问数失败线索待派发，requestId={}，errorType={}",
                            id,
                            unavailable.getClass().getSimpleName());
                }
            }
        } catch (DataAccessException unavailable) {
            log.warn("问数失败线索扫描暂不可用，errorType={}", unavailable.getClass().getSimpleName());
        }
    }

    /** 标记和可靠事件原子提交，恢复沿用同一稳定事件编号。 */
    private void enqueue(Request request) {
        String origin = origin(request);
        if (origin == null || request.failureEventId() != null) {
            return;
        }
        String event = UUID.nameUUIDFromBytes((TYPE + ":" + request.id()).getBytes(StandardCharsets.UTF_8))
                .toString();
        if (requests.markIssueEvent(request.id(), event) != 1) {
            throw inconsistent();
        }
        var payload = json.write(Map.of("requestId", request.id(), "origin", origin));
        outbox.appendAt(new OutboxPO(
                event,
                TYPE,
                1,
                "query_request",
                request.id(),
                Math.max(1, request.runVersion()),
                request.commandId(),
                payload,
                null,
                0,
                null,
                null,
                null,
                request.updatedAt(),
                null,
                null));
    }

    /** 在确切租约下创建事项和证据，重放不会增加事项或推进旧版本。 */
    @Override
    public boolean consume(OutboxPO delivery) {
        var id = requests.issueEventRequest(delivery.getEventId());
        if (id == null) {
            throw inconsistent();
        }
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var request = requests.lock(id);
            var lease = outbox.lockEvent(delivery.getEventId());
            if (lease == null
                    || !"CLAIMED".equals(lease.getDeliveryState())
                    || !Objects.equals(lease.getLeaseToken(), delivery.getLeaseToken())
                    || !lease.getLeasedUntil().isAfter(feedback.now())) {
                return false;
            }
            String origin = origin(request);
            if (origin == null || !delivery.getEventId().equals(request.failureEventId())) {
                throw inconsistent();
            }
            Long linked = issues.linkedIssue(request.id());
            boolean received = outbox.hasReceipt(delivery.getEventId(), CONSUMER);
            if (linked == null) {
                if (received) {
                    throw inconsistent();
                }
                String gap = gapKey(request);
                issues.intake(
                        request.sourceId(),
                        origin,
                        request.id(),
                        KNOWLEDGE_GAP.equals(origin) ? "KNOWLEDGE" : "TECHNICAL",
                        gap);
                Long item =
                        gap == null ? issues.atRequest(origin, request.id()) : issues.openGap(request.sourceId(), gap);
                if (item == null) {
                    throw inconsistent();
                }
                issues.link(request.id(), item);
            }
            if (!received) {
                outbox.receipt(delivery.getEventId(), CONSUMER);
            }
            return true;
        }));
    }

    /**
     * 优先使用问数已核实的缺口，完全无生效知识的开放事项继续沿用原约定。
     */
    private String gapKey(Request request) {
        if (request.failureGapKey() != null) {
            // 指纹已包含来源、原提问人、原输出范围及实际缺口身份。
            return request.failureGapKey();
        }
        return "NO_PUBLISHED_KNOWLEDGE".equals(request.failureCode()) && request.failureRuleSignature() != null
                ? HexFormat.of()
                        .formatHex(fingerprints.command(
                                "feedback.query.noPublishedKnowledge",
                                Ids.text(request.sourceId()),
                                List.of(request.userId(), request.failureRuleSignature())))
                : null;
    }

    /** 只接受本版已落盘的两类实际终态，取消与撤权不会生成事项。 */
    private String origin(Request request) {
        if (request == null || request.failureCode() == null) {
            return null;
        }
        return switch (request.state()) {
            case "KNOWLEDGE_INSUFFICIENT" -> KNOWLEDGE_GAP;
            case "TECHNICAL_FAILURE" -> TECHNICAL_FAILURE;
            default -> null;
        };
    }

    /** 只描述持久事实矛盾，外部错误正文不进入日志或事项。 */
    private DataIntegrityViolationException inconsistent() {
        return new DataIntegrityViolationException("问数失败事件与事项关联不一致");
    }
}
