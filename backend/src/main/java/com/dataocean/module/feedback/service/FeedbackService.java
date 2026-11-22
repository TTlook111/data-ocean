package com.dataocean.module.feedback.service;

import com.dataocean.common.ErrorVO.FieldErrorVO;
import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.exception.FieldValidationException;
import com.dataocean.infrastructure.DataOceanProperties;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.SecretKeyProvider;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.feedback.entity.FeedbackProtocol.Rating;
import com.dataocean.module.feedback.entity.FeedbackProtocol.RatingCommand;
import com.dataocean.module.feedback.entity.FeedbackProtocol.Receipt;
import com.dataocean.module.feedback.mapper.FeedbackMapper;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Event;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.mapper.CommandMapper;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.query.service.QueryService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 评价写入、并发版本和可见状态都依照原问数回答。
 */
@Service
@RequiredArgsConstructor
public class FeedbackService {

    /** 原命令及审计共用的操作编号。 */
    private static final String OPERATION = "feedback.rating.submit";

    /** 与浏览器输入采用相同的首尾空白识别。 */
    private static final Pattern OUTER_SPACES = Pattern.compile("(?U)^[\\s\\uFEFF]+|[\\s\\uFEFF]+$");

    /** 已确认的评价操作集合。 */
    private static final Set<String> ACTIONS = Set.of("LIKE", "DISLIKE", "REVOKE");

    /** 答复与当前用户资格由问数模块核验。 */
    private final QueryService query;

    /** 评价和队列使用各自权威台账。 */
    private final FeedbackMapper feedback;

    /** 复用统一命令表。 */
    private final CommandMapper commands;

    /** 固定本次安全修订和后台功能。 */
    private final GuardMapper guard;

    /** 将可靠派发与评价一同提交。 */
    private final FeedbackIntake intake;

    /** 写入最小成功审计。 */
    private final AuditService audit;

    /** 命令指纹覆盖规范化参数。 */
    private final Fingerprints fingerprints;

    /** 使用受控密钥的恒定时间比较。 */
    private final SecretKeyProvider secrets;

    /** 按明确回执类型编解码。 */
    private final JsonCodec json;

    /** 本机明确设置的原因保留配置。 */
    private final DataOceanProperties properties;

    /** 原提问人只读取本人当前评价及关联事项。 */
    @Transactional
    public Rating rating(String id, int revision) {
        var answer = query.ratingAnswer(id, revision, false);
        var ref = answer.ref();
        var current = feedback.current(answer.userId(), ref);
        var issue = feedback.issueId(ref);
        String queue = issue != null ? "QUEUED" : feedback.received(answer.userId(), ref) ? "RECEIVED" : "NONE";
        return new Rating(
                ref.answerId(),
                ref.answerRevision(),
                current == null ? "NONE" : current.rating(),
                current == null ? null : current.reason(),
                current == null ? null : current.version(),
                current == null ? null : instant(current.updatedAt()),
                queue,
                Ids.text(issue));
    }

    /** 当前核权后在原回答锁下原子追加、推进摘要和登记待派发。 */
    @Transactional
    public Receipt submit(String id, int revision, String key, RatingCommand input) {
        var answer = query.ratingAnswer(id, revision, true);
        var ref = answer.ref();
        var command = commandId(key);
        var normalized = normalize(input);
        long user = answer.userId();
        var fingerprint = fingerprints.command(OPERATION, ref.answerId() + ":" + revision, normalized);
        var known = commands.find(Ids.text(user), OPERATION, command);
        if (known != null) {
            // 回放先确认同一内容，原版本之后变化也能查回旧回执。
            if (!secrets.matches(known.getPayloadHmac(), fingerprint)) {
                throw new BizIllegalException(ErrorCode.COMMAND_CONTENT_CONFLICT, "同一操作编号的内容不同");
            }
            return committed(known.getState(), known.getResultRef());
        }
        if (!retentionConfigured()) {
            // 启用采集前由本机配置提供已批准的原因期限。
            throw new BizIllegalException(ErrorCode.DEPENDENCY_UNAVAILABLE, "评价暂不可提交");
        }
        var current = feedback.current(user, ref);
        Integer version = current == null ? null : current.version();
        if (!Objects.equals(normalized.expectedVersion(), version)) {
            // 使用旧表单不能覆盖已经提交的修改。
            throw BizIllegalException.conflict();
        }
        String previous = current == null ? "NONE" : current.rating();
        String next = "REVOKE".equals(normalized.action()) ? "NONE" : normalized.action();
        if (next.equals(previous) && Objects.equals(normalized.reason(), current == null ? null : current.reason())) {
            // 相同评价只通过原命令回放，新的无变化命令不记为事件。
            throw BizIllegalException.state("当前评价没有变化");
        }
        commands.insert(user, OPERATION, command, fingerprint);
        var event = new Event(
                UUID.randomUUID().toString(),
                user,
                ref.answerId(),
                ref.answerRevision(),
                version == null ? 1 : version + 1,
                current == null ? null : current.eventId(),
                normalized.action(),
                normalized.reason(),
                command,
                feedback.now());
        feedback.append(event);
        if (current == null) {
            // 还没有摘要时建立第一条评价版本。
            feedback.insertCurrent(event, next);
        } else {
            // 改赞或撤销仍保留原点踩和事项。
            feedback.updateCurrent(event, next);
        }
        audit.record(AuditWriteDTO.builder()
                .actionCode(OPERATION)
                .targetType("feedback_rating")
                .targetId(event.id())
                .targetName("问数评价")
                .commandId(command)
                .securityVersion(guard.selectById(1).getSecurityVersion())
                .changes(List.of(new ChangeVO("rating", "评价", previous, next)))
                .build());
        intake.enqueue(event);
        var receipt = new Receipt(
                command,
                event.id(),
                ref.answerId(),
                ref.answerRevision(),
                event.sequence(),
                instant(event.occurredAt()));
        commands.complete(user, OPERATION, command, json.write(receipt));
        return receipt;
    }

    /** 网络超时后按原键查找，仍需通过当前回答核验。 */
    @Transactional
    public Receipt receipt(String id, int revision, String key) {
        var answer = query.ratingAnswer(id, revision, false);
        var original = commands.find(Ids.text(answer.userId()), OPERATION, commandId(key));
        if (original == null) {
            // 没有持久回执时，客户端可以用相同命令键核实或重试。
            throw BizIllegalException.missing();
        }
        var receipt = committed(original.getState(), original.getResultRef());
        if (!answer.ref().answerId().equals(receipt.answerId()) || revision != receipt.answerRevision()) {
            // 命令只可通过它原来所属的回答访问。
            throw BizIllegalException.missing();
        }
        return receipt;
    }

    /** 单独清理到期原因，已受理待入队记录保留到派发完成。 */
    @Scheduled(fixedDelay = 60000)
    public void purgeReasons() {
        if (retentionConfigured()) {
            // 清除正文后仍保留原事件和去重引用。
            feedback.purgeReasons(feedback.now().minus(properties.getFeedbackReasonRetention()));
        }
    }

    /** 明确的正期限表示已经完成本机保留配置。 */
    private boolean retentionConfigured() {
        var duration = properties.getFeedbackReasonRetention();
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    /** 防止未提交记录被解释为成功回执。 */
    private Receipt committed(String state, String result) {
        if (!"COMMITTED".equals(state) || result == null) {
            // 仅持久成功后返回原回执。
            throw new BizIllegalException(ErrorCode.COMMAND_IN_PROGRESS, "请核对原评价提交状态");
        }
        return json.read(result, Receipt.class);
    }

    /** 校验证据正文并产生命令防重所用的规范参数。 */
    private RatingCommand normalize(RatingCommand input) {
        if (input.action() == null
                || !ACTIONS.contains(input.action())
                || input.expectedVersion() != null && input.expectedVersion() <= 0) {
            // 新旧版本使用不同的输入约定。
            throw BizIllegalException.invalid("评价动作或版本不正确");
        }
        String reason = input.reason() == null
                ? null
                : OUTER_SPACES.matcher(input.reason()).replaceAll("");
        if ("DISLIKE".equals(input.action())
                ? reason == null || reason.isEmpty() || reason.length() > 500
                : reason != null) {
            // 后端独立校验必填原因并定位到具体字段。
            throw new FieldValidationException(List.of(new FieldErrorVO(
                    "reason",
                    "VALIDATION_FAILED",
                    "DISLIKE".equals(input.action()) ? "请填写 1–500 字的点踩原因" : "该评价不接受原因")));
        }
        return new RatingCommand(input.action(), reason, input.expectedVersion());
    }

    /** 原命令键必须是完整且规范化的 UUID。 */
    private String commandId(String value) {
        try {
            if (value != null && UUID.fromString(value).toString().equals(value)) {
                // 仅标准小写表示进入持久防重键。
                return value;
            }
        } catch (IllegalArgumentException invalid) {
            // 用户输入不写入日志。
        }
        throw BizIllegalException.invalid("操作编号须为 UUID");
    }

    /** 平台 DATETIME 按 UTC 对外返回。 */
    private Instant instant(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC);
    }
}
