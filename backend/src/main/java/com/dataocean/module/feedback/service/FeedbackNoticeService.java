package com.dataocean.module.feedback.service;

import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.feedback.entity.FeedbackNoticeVO;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Issue;
import com.dataocean.module.feedback.mapper.FeedbackNoticeMapper;
import com.dataocean.module.feedback.mapper.FeedbackNoticeMapper.Notice;
import com.dataocean.module.iam.entity.po.OutboxPO;
import com.dataocean.module.iam.entity.po.UserPO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.OutboxMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.OutboxLocalConsumer;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.query.mapper.QueryRequestMapper;
import com.dataocean.module.query.service.QueryService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

/** 回告只发送给原提问人，站内送达复用已持久化的 Outbox。 */
@Service
@RequiredArgsConstructor
public class FeedbackNoticeService implements OutboxLocalConsumer {

    /** 事件和本地消费回执分别标识。 */
    private static final String EVENT = "feedback.completion.recorded";

    private static final String CONSUMER = "FEEDBACK_NOTICE";
    private static final String LIMITED = "该反馈已完成处理，详细结果受当前权限限制。";
    private static final String UNAVAILABLE = "接收账号当前不可用，本次仅记录回告状态。";

    /** 收件箱、操作与原复验均使用平台的权威状态。 */
    private final FeedbackNoticeMapper notices;

    private final QueryRequestMapper requests;
    private final UserMapper users;
    private final PermissionService permissions;
    private final AccessScopeService access;
    private final QueryService queries;
    private final GuardMapper guard;
    private final OutboxMapper outbox;
    private final JsonCodec json;
    private final TransactionTemplate transactions;

    /** 结案人只核对需要公开的模板与原接收人。 */
    public record Preview(int version, String recipientId, String recipientName, String template, String message) {}

    /** 根据当前真实原问和收件资格生成预览，正文永远来自模板。 */
    Preview preview(Issue issue, String template) {
        var request = requests.get(issue.requestId());
        if (request == null || request.sourceId() != issue.sourceId()) {
            throw BizIllegalException.state("无法核对原回答的接收人");
        }
        var user = users.selectById(request.userId());
        if (!active(user)) {
            return new Preview(
                    issue.version(),
                    Ids.text(request.userId()),
                    user == null ? "原提问人" : user.getDisplayName(),
                    "SUPPRESSED",
                    UNAVAILABLE);
        }
        var eligibility = eligibility(request.userId(), issue.sourceId(), issue.requestId());
        return new Preview(
                issue.version(),
                Ids.text(request.userId()),
                user.getDisplayName(),
                eligibility.allowed() ? template + (eligibility.canAsk() ? "_ASK" : "") : "RESTRICTED",
                eligibility.allowed() ? message(template, eligibility.canAsk()) : LIMITED);
    }

    /** 结案事务同时接收回告与 Outbox，用户已读不能改变结案状态。 */
    void enqueue(Issue issue, String previewRecipient, String template, String command, int version) {
        String eventId = UUID.randomUUID().toString();
        long actor = Ids.parse(previewRecipient);
        notices.append(new Notice(
                eventId,
                issue.id(),
                1,
                actor,
                issue.requestId(),
                issue.sourceId(),
                template,
                "QUEUED",
                null,
                null,
                null));
        var payload = json.write(Map.of(
                "issueId",
                Ids.text(issue.id()),
                "completionRound",
                1,
                "recipientUserIds",
                List.of(previewRecipient),
                "conclusionRevision",
                version));
        outbox.appendAt(new OutboxPO(
                eventId,
                EVENT,
                1,
                "feedback_issue",
                Ids.text(issue.id()),
                version,
                command,
                payload,
                null,
                0,
                null,
                null,
                null,
                LocalDateTime.now(ZoneOffset.UTC),
                null,
                null));
    }

    /** 处理同一结案事实的既有派发和重试。 */
    @Override
    public String eventType() {
        return EVENT;
    }

    /** 站内副作用和消费回执一致，丢失 Redis 回执时不会重复展示。 */
    @Override
    public boolean consume(OutboxPO event) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            guard.shared();
            var lease = outbox.lockEvent(event.getEventId());
            if (lease == null
                    || !"CLAIMED".equals(lease.getDeliveryState())
                    || !Objects.equals(lease.getLeaseToken(), event.getLeaseToken())
                    || !lease.getLeasedUntil().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
                return false;
            }
            var notice = notices.lock(event.getEventId());
            if (notice == null) {
                throw new DataIntegrityViolationException("站内回告缺少持久记录");
            }
            if (outbox.hasReceipt(event.getEventId(), CONSUMER)) {
                return true;
            }
            if ("QUEUED".equals(notice.state())) {
                var recipient = users.selectById(notice.recipientId());
                var next = !active(recipient)
                        ? "SUPPRESSED"
                        : eligibility(notice.recipientId(), notice.sourceId(), notice.answerId())
                                        .allowed()
                                ? "DELIVERED"
                                : "RESTRICTED";
                notices.deliver(notice.id(), next);
            }
            outbox.receipt(event.getEventId(), CONSUMER);
            return true;
        }));
    }

    /** 本人站内回告，权限变化会裁剪链接和模板。 */
    @Transactional
    public PageVO<FeedbackNoticeVO> inbox(PageQuery page) {
        guard.shared();
        return PageVO.from(notices.list(page.toFixedOrderMpPage(), UserContext.getUser()), this::view);
    }

    /** 受理后首次查看才记录已读，重复点击返回同一事实。 */
    @Transactional
    public FeedbackNoticeVO read(String id) {
        guard.shared();
        long actor = UserContext.getUser();
        notices.read(id, actor);
        var notice = notices.own(id, actor);
        if (notice == null) {
            throw BizIllegalException.missing();
        }
        return view(notice);
    }

    /** 只在已有本人授权的回答中增加已交付的回告卡。 */
    FeedbackNoticeVO answer(long recipient, String id) {
        var notice = notices.forAnswer(recipient, id);
        return notice == null ? null : view(notice);
    }

    /** 后台有权关闭的角色查看当前回告实际投递和阅读状态。 */
    Notice status(long issue) {
        return notices.latest(issue);
    }

    /** 停用和待改密账号不发送通知。 */
    private boolean active(UserPO user) {
        return user != null && "ENABLED".equals(user.getLifecycle()) && !user.isMustChangePassword();
    }

    /** 原问的业务授权不是收件箱资格，失权者仍有通用反馈状态。 */
    private Eligibility eligibility(long recipient, long source, String answer) {
        var codes = permissions.effective(recipient);
        var scope = codes.contains("query.view")
                ? access.findSource(recipient, source).orElse(null)
                : null;
        return new Eligibility(
                scope != null && queries.canOpenFeedbackAnswer(answer, recipient, scope), codes.contains("query.ask"));
    }

    /** 送达与查看双重核权，已有受限回告仍按送达时披露口径呈现。 */
    private FeedbackNoticeVO view(Notice notice) {
        var eligibility = eligibility(notice.recipientId(), notice.sourceId(), notice.answerId());
        boolean allowed = "DELIVERED".equals(notice.state()) && eligibility.allowed();
        return new FeedbackNoticeVO(
                notice.id(),
                allowed ? message(notice.template(), eligibility.canAsk()) : LIMITED,
                allowed ? Ids.text(notice.sourceId()) : null,
                allowed ? notice.answerId() : null,
                instant(notice.sentAt()),
                instant(notice.readAt()));
    }

    /** 产品内模板只来自固定枚举，内部结论和自由文本不参与。 */
    private String message(String template, boolean canAsk) {
        return switch (template) {
            case "RESOLVED" -> "你提交的反馈已完成处理并通过复验。" + (canAsk ? "可以重新查询原问题。" : "");
            case "DEFINITION_CONFIRMED" -> "本次反馈已按现行口径核查并结案。";
            case "NOT_REPRODUCED" -> "本次核查未能复现反馈的问题，已保留反馈记录。";
            case "CANCELLED" -> "本次反馈已按核查依据取消处理。";
            case "OTHER" -> "你提交的反馈已完成核查并说明结案。";
            default -> LIMITED;
        };
    }

    /** 数据库时间按平台 UTC 解释。 */
    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    /** 区分当前可看原问和具备重新查询资格。 */
    private record Eligibility(boolean allowed, boolean canAsk) {}
}
