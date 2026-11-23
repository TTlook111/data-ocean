package com.dataocean.module.feedback.service.impl;

import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.commandId;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.requireIssue;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.SecretKeyProvider;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.feedback.mapper.FeedbackMapper;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Issue;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.mapper.CommandMapper;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.PermissionService;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 在调用方事务中串行办理命令并原子写入回执和审计。
 */
@Component
@RequiredArgsConstructor
class FeedbackIssueCommands {

    /**
     * 事项队列与操作台账。
     */
    private final FeedbackMapper feedback;

    /**
     * 与权限、人员状态及授权变化协调。
     */
    private final GuardMapper guard;

    /**
     * 查询当前办理及证据权限。
     */
    private final PermissionService permissions;

    /**
     * 核对账号状态或串行同一账号的办理命令。
     */
    private final UserMapper users;

    /**
     * 记录和回查办理命令。
     */
    private final CommandMapper commands;

    /**
     * 生成请求或授权上下文的受控指纹。
     */
    private final Fingerprints fingerprints;

    /**
     * 核对命令指纹。
     */
    private final SecretKeyProvider secrets;

    /**
     * 编解码类型化命令回执。
     */
    private final JsonCodec json;

    /**
     * 记录脱敏业务审计。
     */
    private final AuditService audit;

    /**
     * 防重复提交、事项版本、业务记录和安全审计在同一个事务内完成。
     */
    MutationVO change(
            String id,
            String command,
            String action,
            int version,
            Object input,
            Function<Issue, List<ChangeVO>> apply) {
        return change(id, command, action, action, version, input, apply);
    }

    /**
     * 处理结果与核查共用处理权限，审计按实际业务动作区分。
     */
    MutationVO change(
            String id,
            String command,
            String action,
            String auditAction,
            int version,
            Object input,
            Function<Issue, List<ChangeVO>> apply) {
        return change(id, command, action, auditAction, version, input, null, (row, ignored) -> apply.apply(row));
    }

    /**
     * 先按编号锁定涉及的事项，再锁定操作者，避免事项更新的人员外键检查与其他办理命令形成死锁。
     */
    MutationVO change(
            String id,
            String command,
            String action,
            String auditAction,
            int version,
            Object input,
            Long peerId,
            BiFunction<Issue, Issue, List<ChangeVO>> apply) {
        guard.shared();
        permissions.require(action);
        var key = commandId(command);
        long actor = UserContext.getUser();
        long issueId = Ids.parse(id);
        Issue peer = peerId != null && peerId < issueId ? requireIssue(feedback.lockIssue(peerId)) : null;
        var row = requireIssue(feedback.lockIssue(issueId));
        if (peerId != null && peer == null) {
            // 关联编号较大时在主记录之后取锁。
            peer = requireIssue(feedback.lockIssue(peerId));
        }
        users.lock(actor);
        String target = Ids.text(row.id());
        var signature = fingerprints.command(action, target, input);
        var receipt = replay(actor, action, key, signature);
        if (receipt != null) {
            // 已提交命令先于当前事项版本核对，重放不推进事项。
            return receipt;
        }
        if (row.version() != version) {
            // 新命令必须基于调用者实际核对的版本。
            throw BizIllegalException.conflict();
        }
        commands.insert(actor, action, key, signature);
        var changes = apply.apply(row, peer);
        audit.record(AuditWriteDTO.builder()
                .actionCode(auditAction)
                .targetType("feedback_issue")
                .targetId(target)
                .targetName("反馈事项 " + target)
                .commandId(key)
                .securityVersion(guard.selectById(1).getSecurityVersion())
                .changes(changes)
                .build());
        var committed = new MutationVO(key, "feedback_issue", target, row.version() + 1);
        commands.complete(actor, action, key, json.write(committed));
        return committed;
    }
    /**
     * 原关联已经不可读取时，仍可按当前操作者、动作和完整提案回查提交事实。
     */
    MutationVO requireReplay(String id, String command, String action, Object input) {
        guard.shared();
        permissions.require(action);
        String key = commandId(command);
        String target = Ids.text(Ids.parse(id));
        var receipt = replay(UserContext.getUser(), action, key, fingerprints.command(action, target, input));
        if (receipt == null) {
            // 当前关联不存在且无原命令回执时，按资源缺失处理。
            throw BizIllegalException.missing();
        }
        return receipt;
    }

    /**
     * 只有当前操作者的同一命令、同一目标和同一提案能取回原回执。
     */
    private MutationVO replay(long actor, String action, String key, byte[] signature) {
        var known = commands.find(Ids.text(actor), action, key);
        if (known == null) {
            // 未登记的编号交给调用者继续核对实际业务条件。
            return null;
        }
        if (!secrets.matches(known.getPayloadHmac(), signature)) {
            // 拒绝将旧编号用于另一个目标或已修改的提案。
            throw new BizIllegalException(ErrorCode.COMMAND_CONTENT_CONFLICT, "同一操作编号的内容不同");
        }
        if (!"COMMITTED".equals(known.getState()) || known.getResultRef() == null) {
            // 未提交的命令暂时没有可返回的成功回执。
            throw new BizIllegalException(ErrorCode.COMMAND_IN_PROGRESS, "请核对原操作提交状态");
        }
        return json.read(known.getResultRef(), MutationVO.class);
    }
}
