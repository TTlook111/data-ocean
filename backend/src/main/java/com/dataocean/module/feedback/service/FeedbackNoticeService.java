package com.dataocean.module.feedback.service;

import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.feedback.entity.vo.FeedbackClosurePreviewVO;
import com.dataocean.module.feedback.entity.vo.FeedbackClosurePreviewVO.Recipient;
import com.dataocean.module.feedback.entity.vo.FeedbackLifecycleVO.NoticeRound;
import com.dataocean.module.feedback.entity.vo.FeedbackNoticeVO;
import com.dataocean.module.feedback.mapper.FeedbackLifecycleMapper;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /**
     * 按结案时的主从关系取所有原问。
     */
    private final FeedbackLifecycleMapper lifecycle;

    /**
     * 根据当前真实原问和收件资格生成各人的预览。
     */
    public FeedbackClosurePreviewVO preview(Issue issue, String template) {
        var affected = affected(issue);
        var recipients = affected.entrySet().stream()
                .map(entry -> previewRecipient(
                        issue.sourceId(),
                        entry.getKey(),
                        entry.getValue().stream().toList(),
                        template))
                .toList();
        var first = recipients.get(0);
        boolean grouped =
                affected.values().stream().mapToInt(java.util.Set::size).sum() > 1;
        String confirmation = grouped
                ? UUID.nameUUIDFromBytes(json.write(recipients).getBytes(StandardCharsets.UTF_8))
                        .toString()
                : null;
        return new FeedbackClosurePreviewVO(
                issue.version(),
                first.getRecipientId(),
                first.getRecipientName(),
                first.getTemplate(),
                first.getMessage(),
                grouped ? recipients : List.of(),
                confirmation);
    }

    /**
     * 主事项原问排在首位，合并及自动缺口关联的问数按原提问人分组。
     */
    private Map<Long, LinkedHashSet<String>> affected(Issue issue) {
        var request = requests.get(issue.requestId());
        if (request == null || request.sourceId() != issue.sourceId()) {
            throw BizIllegalException.state("无法核对原回答的接收人");
        }
        var group = new LinkedHashMap<Long, LinkedHashSet<String>>();
        group.put(request.userId(), new LinkedHashSet<>(List.of(request.id())));
        for (var origin : lifecycle.origins(issue.id())) {
            // 去重维度使用提问人与原问，保留该接收人的全部定位。
            group.computeIfAbsent(origin.recipientId(), ignored -> new LinkedHashSet<>())
                    .add(origin.requestId());
        }
        return group;
    }

    /**
     * 各人只按自己的账号和业务权限预览可公开通知。
     */
    private Recipient previewRecipient(long source, long recipient, List<String> requestIds, String template) {
        var user = users.selectById(recipient);
        if (!active(user)) {
            return new Recipient(
                    Ids.text(recipient), user == null ? "原提问人" : user.getDisplayName(), "SUPPRESSED", UNAVAILABLE);
        }
        var eligibility = eligibility(recipient, source, requestIds);
        return new Recipient(
                Ids.text(recipient),
                user.getDisplayName(),
                eligibility.allowed() ? template + (eligibility.canAsk() ? "_ASK" : "") : "RESTRICTED",
                eligibility.allowed() ? message(template, eligibility.canAsk()) : LIMITED);
    }

    /**
     * 一次结案为每人及当前完成轮次持久化一条送达记录。
     */
    public void enqueue(Issue issue, String template, String command, int version) {
        affected(issue).forEach((recipient, requestIds) -> {
            // 各接收人的派发、失败、已读及定位独立。
            enqueue(issue, recipient, List.copyOf(requestIds), template, command, version);
        });
    }

    /**
     * 单次派发事件和单个接收人的待投递状态同事务保存。
     */
    private void enqueue(
            Issue issue, long recipient, List<String> requestIds, String template, String command, int version) {
        String eventId = UUID.randomUUID().toString();
        notices.append(new Notice(
                eventId,
                issue.id(),
                issue.completionRound(),
                recipient,
                requestIds.get(0),
                issue.sourceId(),
                template,
                "QUEUED",
                null,
                null,
                null));
        for (String request : requestIds) {
            // 该回告只引用目标接收人自己的原问题。
            notices.linkRequest(eventId, request);
        }
        var payload = json.write(Map.of(
                "issueId",
                Ids.text(issue.id()),
                "completionRound",
                issue.completionRound(),
                "recipientUserIds",
                List.of(Ids.text(recipient)),
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
                        : eligibility(notice.recipientId(), notice.sourceId(), requestIds(notice))
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
    public Notice status(long issue) {
        return notices.latest(issue);
    }

    /**
     * 回看同一主事项各轮的投递事实。
     */
    public List<NoticeRound> rounds(long issue) {
        return notices.rounds(issue);
    }

    /** 停用和待改密账号不发送通知。 */
    private boolean active(UserPO user) {
        return user != null && "ENABLED".equals(user.getLifecycle()) && !user.isMustChangePassword();
    }

    /**
     * 无权读取原问仍可收到通用模板，每条定位继续核对本人及来源权限。
     */
    private Eligibility eligibility(long recipient, long source, List<String> requestIds) {
        var codes = permissions.effective(recipient);
        var scope = codes.contains("query.view")
                ? access.findSource(recipient, source).orElse(null)
                : null;
        return new Eligibility(
                scope == null
                        ? List.of()
                        : requestIds.stream()
                                .filter(request -> queries.canOpenFeedbackAnswer(request, recipient, scope))
                                .toList(),
                codes.contains("query.ask"));
    }

    /**
     * 顺序保留原先的首条定位，其他定位按快照逐条返回。
     */
    private List<String> requestIds(Notice notice) {
        var requestsForNotice = new LinkedHashSet<String>();
        requestsForNotice.add(notice.answerId());
        requestsForNotice.addAll(notices.requests(notice.id()));
        return List.copyOf(requestsForNotice);
    }

    /**
     * 送达和查看分别核权，通用模板不携带原问题编号。
     */
    private FeedbackNoticeVO view(Notice notice) {
        var eligibility = eligibility(notice.recipientId(), notice.sourceId(), requestIds(notice));
        boolean allowed = "DELIVERED".equals(notice.state()) && eligibility.allowed();
        return new FeedbackNoticeVO(
                notice.id(),
                allowed ? message(notice.template(), eligibility.canAsk()) : LIMITED,
                allowed ? Ids.text(notice.sourceId()) : null,
                allowed ? eligibility.requestIds().get(0) : null,
                instant(notice.sentAt()),
                instant(notice.readAt()),
                allowed ? eligibility.requestIds() : List.of());
    }

    /** 产品内模板只来自固定枚举，内部结论和自由文本不参与。 */
    private String message(String template, boolean canAsk) {
        return switch (template) {
            case "RESOLVED" -> "这次问数的问题已处理并通过复验。" + (canAsk ? "可以重新查询原问题。" : "");
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

    /**
     * 区分逐条可见的原问及当前重新查询资格。
     */
    private record Eligibility(List<String> requestIds, boolean canAsk) {
        /**
         * 至少一条原问可见时才使用该接收人的完整受控模板。
         */
        private boolean allowed() {
            return !requestIds.isEmpty();
        }
    }
}
