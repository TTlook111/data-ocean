package com.dataocean.module.iam.service.impl;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.SecretKeyProvider;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.iam.entity.dto.AuthenticationInvalidatedDTO;
import com.dataocean.module.iam.entity.dto.AuthorizationChangedDTO;
import com.dataocean.module.iam.entity.dto.SubjectRefDTO;
import com.dataocean.module.iam.entity.po.OutboxPO;
import com.dataocean.module.iam.entity.vo.CommandStatusVO;
import com.dataocean.module.iam.entity.vo.EntityVersionRefVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.mapper.CommandMapper;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.OutboxMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 统一管理写操作的配置锁、命令防重、当前资格和可靠事件提交。
 */
@Service
@RequiredArgsConstructor
public class MutationServiceImpl implements MutationService {

    /**
     * 跨组织与权限变更共用的配置锁和安全版本存储。
     */
    private final GuardMapper guard;

    /**
     * 人员资料、生命周期与受控凭据的访问依赖。
     */
    private final UserMapper users;

    /**
     * 命令的受控登记、内容核对及提交状态存储。
     */
    private final CommandMapper commands;

    /**
     * 与业务同事务保存的可靠通知记录存储。
     */
    private final OutboxMapper outbox;

    /**
     * 当前操作者实际使用能力与显式授予资格的业务服务。
     */
    private final PermissionService permissions;

    /**
     * 与业务变化一致的审计写入和历史读取能力。
     */
    private final AuditService audits;

    /**
     * 统一的明确类型 JSON 编解码适配。
     */
    private final JsonCodec json;

    /**
     * 类型化提案规范摘要和带密钥命令指纹能力。
     */
    private final Fingerprints fingerprints;

    /**
     * 受控密钥读取、HMAC 及恒定时间比较能力。
     */
    private final SecretKeyProvider secrets;

    /**
     * 在可信身份、当前能力、命令防重和审计约束下原子执行业务动作。
     */
    @Transactional
    public MutationVO execute(
            String operation,
            String command,
            String target,
            Object payload,
            Integer expectedVersion,
            boolean authorizationChange,
            boolean allowInitialPassword,
            Supplier<MutationVO> action) {
        // 配置写使用排他锁，普通资料写使用共享放行锁；均在锁后重新核对可信身份和能力。
        if (authorizationChange) {
            guard.lock();
        } else {
            guard.shared();
        }
        var principal = UserContext.current();
        if (!authorizationChange) {
            // 普通资料编辑按用户编号顺序锁操作者与目标，避免相互编辑时的外键审计死锁。
            var locked = new TreeSet<Long>();
            locked.add(principal.userId());
            if (target.matches("[1-9][0-9]{0,18}")) {
                locked.add(Ids.parse(target));
            }
            locked.forEach(users::lock);
        }
        var actor = users.selectById(principal.userId());
        if (actor == null
                || !actor.getLifecycle().equals("ENABLED")
                || actor.getAuthenticationVersion() != principal.authenticationVersion()) {
            throw new BizIllegalException(ErrorCode.AUTH_EXPIRED, "登录已失效，请重新登录");
        }
        if (actor.isMustChangePassword() && !allowInitialPassword) {
            throw new BizIllegalException(ErrorCode.PASSWORD_CHANGE_REQUIRED, "请先完成初始改密");
        }
        if (!Set.of("account.contact", "account.password", "auth.password.initial")
                .contains(operation)) {
            permissions.require(operation);
        }
        String key = normalizeCommand(command);
        byte[] fingerprint = fingerprints.command(operation, target, payload);
        var existing = commands.find(Long.toString(principal.userId()), operation, key);
        if (existing != null) {
            if (!secrets.matches(existing.getPayloadHmac(), fingerprint)) {
                throw new BizIllegalException(ErrorCode.COMMAND_CONTENT_CONFLICT, "同一操作编号的内容不同，请核对原操作");
            }
            if (!existing.getState().equals("COMMITTED")) {
                throw new BizIllegalException(ErrorCode.COMMAND_IN_PROGRESS, "原操作仍在处理中，请查询其状态");
            }
            return json.read(existing.getResultRef(), MutationVO.class);
        }
        commands.insert(principal.userId(), operation, key, fingerprint);
        MutationVO result = action.get();
        result.setCommandId(key);
        boolean changed = expectedVersion == null || result.getVersion() != expectedVersion;
        if (changed) {
            permissions.ensureRecovery();
            if (authorizationChange) {
                guard.increment();
                var payloadEvent = new AuthorizationChangedDTO(
                        Long.toString(guard.selectById(1).getSecurityVersion()),
                        operation,
                        List.of(new SubjectRefDTO(
                                result.getTargetType().toUpperCase(Locale.ROOT), result.getTargetId())),
                        List.of(),
                        "ALL_OF_SUBJECTS");
                enqueue(
                        "iam.authorization.changed",
                        result.getTargetType(),
                        result.getTargetId(),
                        result.getVersion(),
                        key,
                        payloadEvent);
            }
            audits.assertRecorded(principal.userId(), key, operation, result.getTargetId());
        }
        commands.complete(principal.userId(), operation, key, json.write(result));
        return result;
    }

    /**
     * 按当前事务是否改变授权计算应记录的安全修订。
     */
    public long auditSecurityVersion(boolean authorizationChange) {
        return guard.selectById(1).getSecurityVersion() + (authorizationChange ? 1 : 0);
    }

    /**
     * 登记认证失效的可靠事件，权威版本校验不等待事件送达。
     */
    public void invalidateAuthentication(long id, String reason) {
        var user = users.selectById(id);
        enqueue(
                "iam.authentication.invalidated",
                "user",
                Long.toString(id),
                user.getVersion(),
                null,
                new AuthenticationInvalidatedDTO(Long.toString(id), user.getAuthenticationVersion(), reason));
    }

    /**
     * 将必要身份或授权事件与业务事实在同一事务登记。
     */
    private void enqueue(String type, String aggregate, String id, int version, String command, Object payload) {
        outbox.insert(new OutboxPO(
                UUID.randomUUID().toString(),
                type,
                1,
                aggregate,
                id,
                version,
                command,
                json.write(payload),
                "PENDING",
                0,
                null,
                null,
                null,
                null,
                null,
                null));
    }

    /**
     * 查询原主体本次命令的可披露提交事实，并重核当前动作资格。
     */
    public CommandStatusVO status(String command, String operation) {
        var found = commands.find(Long.toString(UserContext.getUser()), operation, normalizeCommand(command));
        if (found == null) {
            throw BizIllegalException.missing();
        }
        if (!Set.of("account.contact", "account.password", "auth.password.initial")
                .contains(operation)) {
            permissions.require(operation);
        }
        MutationVO result = found.getResultRef() == null ? null : json.read(found.getResultRef(), MutationVO.class);
        return new CommandStatusVO(
                found.getCommandId(),
                found.getOperationCode(),
                found.getState(),
                found.getResultCode(),
                result == null ? null : new EntityVersionRefVO(result.getTargetId(), result.getVersion()));
    }

    /**
     * 校验并规范唯一命令编号，拒绝非约定 UUID。
     */
    private String normalizeCommand(String value) {
        if (value == null) {
            throw BizIllegalException.invalid("缺少操作编号");
        }
        try {
            String key = UUID.fromString(value).toString();
            if (!key.equals(value.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException();
            }
            return key;
        } catch (IllegalArgumentException error) {
            throw BizIllegalException.invalid("操作编号须为 UUID");
        }
    }
}
