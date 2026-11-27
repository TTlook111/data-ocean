package com.dataocean.module.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dataocean.infrastructure.DataOceanProperties;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.feedback.mapper.FeedbackMapper;
import com.dataocean.module.feedback.mapper.FeedbackQueryIssueMapper;
import com.dataocean.module.iam.entity.po.OutboxPO;
import com.dataocean.module.iam.mapper.OutboxMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper.Request;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** 自动事项只能来自白名单终态，重放使用同一原问及证据。 */
class FeedbackQueryIssueIntakeTest {
    /** 每个用例使用有界的查询和实际事务模板。 */
    private final QueryRequestMapper requests = mock(QueryRequestMapper.class);

    private final FeedbackQueryIssueMapper issues = mock(FeedbackQueryIssueMapper.class);
    private final FeedbackMapper feedback = mock(FeedbackMapper.class);
    private final OutboxMapper outbox = mock(OutboxMapper.class);
    private final Fingerprints fingerprints = mock(Fingerprints.class);
    private final DataOceanProperties properties = mock(DataOceanProperties.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final FeedbackQueryIssueIntake intake = new FeedbackQueryIssueIntake(
            requests,
            issues,
            feedback,
            outbox,
            new TransactionTemplate(manager),
            mock(JsonCodec.class),
            fingerprints,
            properties);

    /** 执行事务模板实际回调，隔离存储并不替换业务分支。 */
    @BeforeEach
    void transaction() {
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
    }

    /** 即使扫描结果异常包含撤权请求，也不写其关联或 Outbox。 */
    @Test
    void promotionAcceptsOnlyActualFailureStates() {
        var failure = request("TECHNICAL_FAILURE", "MODEL_CONFIGURATION_UNAVAILABLE");
        var denied = request("PERMISSION_DENIED", "QUERY_RUNTIME_FAILURE");
        String failureId = failure.id();
        String deniedId = denied.id();
        when(properties.isOutboxEnabled()).thenReturn(true);
        when(requests.pendingIssueRequests()).thenReturn(List.of(failureId, deniedId));
        when(requests.lock(failureId)).thenReturn(failure);
        when(requests.lock(deniedId)).thenReturn(denied);
        when(requests.markIssueEvent(eq(failureId), anyString())).thenReturn(1);

        intake.promote();

        verify(requests).markIssueEvent(eq(failureId), anyString());
        verify(requests, never()).markIssueEvent(eq(deniedId), anyString());
        verify(outbox).appendAt(any());
    }

    /** 确认生效知识缺失才使用同一授权上下文的开放事项。 */
    @Test
    void durableEvidenceAndReceiptPreventRepeatedIntake() {
        var request = request("KNOWLEDGE_INSUFFICIENT", "NO_PUBLISHED_KNOWLEDGE");
        String requestId = request.id();
        when(request.failureRuleSignature()).thenReturn("original-scope");
        var lease = claimFor(request);
        String event = lease.getEventId();
        when(fingerprints.command("feedback.query.noPublishedKnowledge", "1", List.of(7L, "original-scope")))
                .thenReturn(new byte[32]);
        when(issues.openGap(1, "0".repeat(64))).thenReturn(9L);
        when(issues.linkedIssue(requestId)).thenReturn(null, 9L);
        when(outbox.hasReceipt(event, "FEEDBACK_QUERY_ISSUE")).thenReturn(false, true);

        assertThat(intake.consume(lease)).isTrue();
        assertThat(intake.consume(lease)).isTrue();

        verify(issues).intake(1, FeedbackQueryIssueIntake.KNOWLEDGE_GAP, requestId, "KNOWLEDGE", "0".repeat(64));
        verify(issues).link(requestId, 9L);
        verify(outbox).receipt(event, "FEEDBACK_QUERY_ISSUE");
    }

    /**
     * 已核实的不同问数轮次引用同一事项，其他缺口和无指纹原问各自办理。
     */
    @Test
    void linksOnlyConfirmedRepeatedGaps() {
        var first = request("KNOWLEDGE_INSUFFICIENT", "INSUFFICIENT_KNOWLEDGE");
        var repeated = request("KNOWLEDGE_INSUFFICIENT", "INSUFFICIENT_KNOWLEDGE");
        var different = request("KNOWLEDGE_INSUFFICIENT", "INSUFFICIENT_KNOWLEDGE");
        var unknown = request("KNOWLEDGE_INSUFFICIENT", "INSUFFICIENT_KNOWLEDGE");
        var firstGap = "1".repeat(64);
        var differentGap = "2".repeat(64);
        when(first.failureGapKey()).thenReturn(firstGap);
        when(repeated.failureGapKey()).thenReturn(firstGap);
        when(different.failureGapKey()).thenReturn(differentGap);
        when(issues.linkedIssue(anyString())).thenReturn(null);
        when(issues.openGap(1, firstGap)).thenReturn(9L);
        when(issues.openGap(1, differentGap)).thenReturn(10L);
        when(issues.atRequest(FeedbackQueryIssueIntake.KNOWLEDGE_GAP, unknown.id()))
                .thenReturn(11L);

        for (var request : List.of(first, repeated, different, unknown)) {
            // 独立的可靠事件分别带入已经落盘的原问编号。
            assertThat(intake.consume(claimFor(request))).isTrue();
        }

        verify(issues).link(first.id(), 9L);
        verify(issues).link(repeated.id(), 9L);
        verify(issues).link(different.id(), 10L);
        verify(issues).link(unknown.id(), 11L);
    }

    /**
     * 模拟 Outbox 已持有有效租约，保留真实消费与回执逻辑。
     */
    private OutboxPO claimFor(Request request) {
        String requestId = request.id();
        String event = UUID.randomUUID().toString();
        when(request.failureEventId()).thenReturn(event);
        when(requests.issueEventRequest(event)).thenReturn(requestId);
        when(requests.lock(requestId)).thenReturn(request);
        var lease = mock(OutboxPO.class);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        when(lease.getEventId()).thenReturn(event);
        when(lease.getLeaseToken()).thenReturn("lease");
        when(lease.getDeliveryState()).thenReturn("CLAIMED");
        when(lease.getLeasedUntil()).thenReturn(now.plusSeconds(5));
        when(outbox.lockEvent(event)).thenReturn(lease);
        when(feedback.now()).thenReturn(now);
        return lease;
    }

    /** 原问 fixture 含最小真实状态，不复制模型及源端异常。 */
    private Request request(String state, String code) {
        var row = mock(Request.class);
        when(row.id()).thenReturn(UUID.randomUUID().toString());
        when(row.userId()).thenReturn(7L);
        when(row.sourceId()).thenReturn(1L);
        when(row.state()).thenReturn(state);
        when(row.failureCode()).thenReturn(code);
        when(row.updatedAt()).thenReturn(LocalDateTime.now(ZoneOffset.UTC));
        return row;
    }
}
