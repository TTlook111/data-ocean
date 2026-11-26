package com.dataocean.module.datasource.service.impl;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.CommonException;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.entity.dto.CredentialChangeDTO;
import com.dataocean.module.datasource.entity.dto.CredentialChangeProposalDTO;
import com.dataocean.module.datasource.entity.dto.SourceActionPreviewDTO;
import com.dataocean.module.datasource.entity.po.CredentialChangePO;
import com.dataocean.module.datasource.entity.po.DatasourcePO;
import com.dataocean.module.datasource.entity.vo.CredentialChangeVO;
import com.dataocean.module.datasource.mapper.CollectionMapper;
import com.dataocean.module.datasource.mapper.CredentialChangeMapper;
import com.dataocean.module.datasource.mapper.CredentialChangeRecordMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper.CredentialRow;
import com.dataocean.module.datasource.mapper.DatasourceRecordMapper;
import com.dataocean.module.datasource.service.CollectionRunner;
import com.dataocean.module.datasource.service.DatasourceEvents;
import com.dataocean.module.datasource.source.SourceSecretCodec;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ReviewService;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 在途换凭据需要先冻结实际旧执行，再决定新配置是否可生效。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CredentialMaintenance {

    /**
     * 受控来源配置和密文。
     */
    private final DatasourceMapper sources;

    /**
     * 来源当前版本及权限共享边界。
     */
    private final SourceManagement management;

    /**
     * 当前来源的固定配置切换。
     */
    private final DatasourceRecordMapper sourceRecords;

    /**
     * 替换接收和结果。
     */
    private final CredentialChangeMapper changes;

    /**
     * 写入凭据替换请求及其结论。
     */
    private final CredentialChangeRecordMapper changeRecords;

    /**
     * 活跃任务的可靠状态。
     */
    private final CollectionMapper tasks;

    /**
     * 取消未执行任务时写下真实终态。
     */
    private final CollectionRunner runner;

    /**
     * 新凭据的实际测试关联。
     */
    private final DatasourceServiceImpl connections;

    /**
     * 部署端加密策略。
     */
    private final SourceSecretCodec cipher;

    /**
     * 把审批原因和任务标识绑定到短时核对。
     */
    private final ReviewService reviews;

    /**
     * 业务写权限及当前用户状态。
     */
    private final PermissionService permission;

    /**
     * 复用平台命令防重约束。
     */
    private final MutationService commands;

    /**
     * 替换结果的系统审计。
     */
    private final AuditService audit;

    /**
     * 加锁后核对当前权限及安全修订。
     */
    private final GuardMapper guard;

    /**
     * 读取原操作者最新状态。
     */
    private final UserMapper users;

    /**
     * 同事务通知配置变化。
     */
    private final DatasourceEvents events;

    /**
     * 受控固定 JSON。
     */
    private final JsonCodec json;

    /**
     * 每次恢复提交的隔离事务。
     */
    private final TransactionTemplate tx;

    /**
     * 显示可能受影响的原账号和活跃任务，不把密码放进核对。
     */
    public ImpactPreviewVO review(long id, SourceActionPreviewDTO input) {
        DatasourcePO source = management.source(id, false);
        management.verifyVersion(source, input.getExpectedVersion());
        requireReady(source);
        var candidate = json.mapper().treeToValue(input.getProposal(), CredentialChangeProposalDTO.class);
        validate(candidate);
        var current = sources.revision(id, source.getCurrentConnectionRevisionId());
        var binding = new Binding(candidate, source.getActiveTaskId());
        return reviews.preview(
                "REPLACE_CREDENTIAL",
                Long.toString(id),
                source.getVersion(),
                binding,
                List.of(
                        new ChangeVO(
                                "account", "数据库只读账号", current.configuration().getAccount(), candidate.getAccount()),
                        new ChangeVO("activeTaskId", "当前采集", source.getActiveTaskId(), "确认旧执行停止后切换")),
                List.of(),
                List.of("更换期间暂停接收该数据源的新采集。"));
    }

    /**
     * 同事务接受候选秘密、占位及对原任务的停止意图。
     */
    public CredentialChangeVO replace(long id, String command, CredentialChangeDTO input) {
        var proposal = input.getProposal();
        validate(proposal);
        String key = UUID.fromString(command).toString();
        String digest = HexFormat.of().formatHex(connections.passwordDigest(input.getPassword()));
        commands.execute(
                "sources.edit",
                command,
                "credential:" + id,
                List.of(input.getExpectedVersion(), proposal, digest),
                null,
                false,
                false,
                () -> {
                    // 锁住源后旧任务和凭据替换不会同时开始下一次读取。
                    DatasourcePO source = management.source(id, true);
                    management.verifyVersion(source, input.getExpectedVersion());
                    requireReady(source);
                    var binding = new Binding(proposal, source.getActiveTaskId());
                    verifyReview(source, input.getReviewToken(), binding);
                    var revision = sources.revision(id, source.getCurrentConnectionRevisionId());
                    revision.configuration().setAccount(proposal.getAccount());
                    connections.verifySavedCheck(
                            proposal.getCheckId(),
                            source,
                            revision.configuration(),
                            revision.limits(),
                            input.getPassword());
                    String version = cipher.version();
                    long actor = UserContext.getUser();
                    long secret = sources.insertCredential(
                            id,
                            actor,
                            new CredentialRow(
                                    version, cipher.encrypt(input.getPassword(), connections.context(id), version)));
                    String changeId = UUID.randomUUID().toString();
                    var change = new CredentialChangePO();
                    change.setId(changeId);
                    change.setCommandId(key);
                    change.setSourceId(id);
                    change.setActorId(actor);
                    change.setBaseConnectionRevisionId(source.getCurrentConnectionRevisionId());
                    change.setCheckId(Ids.parse(proposal.getCheckId()));
                    change.setCandidateCredentialRevisionId(secret);
                    change.setExpectedSourceVersion(source.getVersion());
                    change.setCandidateAccount(proposal.getAccount());
                    change.setBlockingTaskId(source.getActiveTaskId());
                    changeRecords.accept(change, json.write(binding));
                    if (sourceRecords.holdCredentialChange(id, changeId) != 1) {
                        // 替换记录和来源占位必须同时成功接收。
                        throw BizIllegalException.conflict();
                    }
                    if (source.getActiveTaskId() != null) {
                        var original = tasks.task(source.getActiveTaskId(), true);
                        if (original.getCancelRequestedAt() == null
                                && !tasks.requestCancel(original.getId(), "数据源凭据替换")) {
                            // 进行中的任务必须已接收停止意图，凭据切换才可继续。
                            throw BizIllegalException.conflict();
                        }
                        if ("QUEUED".equals(original.getState())) {
                            runner.terminal(tasks.task(original.getId(), false), "CANCELLED", null, null);
                        }
                    }
                    management.record(source, "sources.edit", key, null, "credentialChange", "凭据替换", null, changeId);
                    return new MutationVO(key, "datasource", Long.toString(id), source.getVersion());
                });
        return view(changes.byCommand(id, UserContext.getUser(), key));
    }

    /**
     * 非原操作者查看切换记录需要实际编辑资格。
     */
    public CredentialChangeVO detail(long source, String change) {
        permission.require("sources.view");
        CredentialChangePO row = changes.change(source, UUID.fromString(change).toString());
        if (row == null || (row.getActorId() != UserContext.getUser() && !permission.has("sources.edit"))) {
            throw BizIllegalException.missing();
        }
        return view(row);
    }

    /**
     * 响应丢失按原命令和原操作者定位。
     */
    public CredentialChangeVO byCommand(long source, String command) {
        permission.require("sources.edit");
        CredentialChangePO row = changes.byCommand(
                source, UserContext.getUser(), UUID.fromString(command).toString());
        if (row == null) {
            throw BizIllegalException.missing();
        }
        return view(row);
    }

    /**
     * 已确认旧执行结束的操作可恢复，数据库事务保护最终切换。
     */
    @Scheduled(fixedDelayString = "${dataocean.datasource.rotation-delay-ms:1000}")
    public void tick() {
        for (CredentialChangePO change : changes.ready()) {
            try {
                // 一笔切换故障不阻止独立来源的操作。
                tx.executeWithoutResult(status -> complete(change));
            } catch (RuntimeException error) {
                log.error(
                        "凭据替换处理异常，sourceId={},changeId={},errorType={}",
                        change.getSourceId(),
                        change.getId(),
                        error.getClass().getSimpleName());
            }
        }
    }

    /**
     * 在旧连接已经核实结束的条件下，按新测试和最新权限决定结果。
     */
    private void complete(CredentialChangePO candidate) {
        guard.shared();
        DatasourcePO source = management.source(candidate.getSourceId(), true);
        if (!Objects.equals(source.getPendingChangeId(), candidate.getId()) || source.getActiveTaskId() != null) {
            return;
        }
        CredentialChangePO change = changes.change(candidate.getSourceId(), candidate.getId());
        var previous = sources.revision(source.getId(), source.getCurrentConnectionRevisionId());
        String formerAccount = previous.configuration().getAccount();
        Long applied = null;
        String error = null;
        String message = null;
        try {
            // 来源版本和原办理人的权限在切换前重核。
            var user = users.selectById(change.getActorId());
            var codes = permission.effective(change.getActorId());
            if (user == null
                    || !"ENABLED".equals(user.getLifecycle())
                    || !codes.containsAll(List.of("sources.view", "sources.edit"))
                    || !"ENABLED".equals(source.getLifecycle())
                    || source.getVersion() != change.getExpectedSourceVersion()
                    || !Objects.equals(source.getCurrentConnectionRevisionId(), change.getBaseConnectionRevisionId())) {
                throw BizIllegalException.state("当前数据源状态、权限或配置已变化");
            }
            var secret = changes.secret(change);
            String password =
                    cipher.decrypt(secret.ciphertext(), connections.context(source.getId()), secret.keyVersion());
            previous.configuration().setAccount(change.getCandidateAccount());
            connections.verifySavedCheck(
                    Long.toString(change.getCheckId()), source, previous.configuration(), previous.limits(), password);
        } catch (CommonException failure) {
            // 已停止后如果依据过期，保留原运行配置并记录替换失败。
            error = "CREDENTIAL_CHANGE_REJECTED";
            message = failure.getMessage();
        }
        if (error == null) {
            // 从写入修订起，任何失败都交给外层事务回滚。
            applied = sources.insertConfiguration(
                    source.getId(),
                    change.getActorId(),
                    previous.revision() + 1,
                    change.getCandidateCredentialRevisionId(),
                    previous.configuration(),
                    previous.limits(),
                    previous.scopeKey());
            byte[] nameKey = Normalizer.normalize(source.getName(), Normalizer.Form.NFKC)
                    .toLowerCase(Locale.ROOT)
                    .getBytes(StandardCharsets.UTF_8);
            if (sourceRecords.configure(source.getId(), applied, source.getName(), nameKey, false) != 1) {
                throw new IllegalStateException("凭据替换未更新预期来源");
            }
            events.replaced(source.getId(), source.getVersion() + 1, change.getCommandId());
            audit.system(
                    AuditWriteDTO.builder()
                            .actionCode("sources.credential.applied")
                            .targetType("datasource")
                            .targetId(Long.toString(source.getId()))
                            .targetName(source.getName())
                            .changes(List.of(
                                    new ChangeVO("account", "数据库只读账号", formerAccount, change.getCandidateAccount())))
                            .beforeRoles(List.of())
                            .afterRoles(List.of())
                            .securityVersion(guard.selectById(1).getSecurityVersion())
                            .commandId(change.getCommandId())
                            .build(),
                    "凭据替换");
        }
        // 原来源锁内登记替换结论和释放占位，后续调度读取已提交状态。
        if (changeRecords.finish(change.getId(), applied != null ? "APPLIED" : "FAILED", applied, error, message) != 1
                || sourceRecords.releaseCredentialChange(change.getSourceId(), change.getId()) != 1) {
            throw new IllegalStateException("凭据替换未更新预期结论或释放来源占位");
        }
    }

    /**
     * 当前活动任务必须被核对。如果原任务已结束则继续使用该任务的核对证据。
     */
    private void verifyReview(DatasourcePO source, String token, Binding binding) {
        try {
            reviews.verify("REPLACE_CREDENTIAL", Long.toString(source.getId()), source.getVersion(), binding, token);
        } catch (BizIllegalException error) {
            if (source.getActiveTaskId() != null) {
                throw error;
            }
            // 仅在当前无执行时接受对最新已结束任务的原核对。
            var prior = tasks.latest(source.getId());
            if (prior == null) {
                // 缺少原任务时维持原核对失败。
                throw error;
            }
            reviews.verify(
                    "REPLACE_CREDENTIAL",
                    Long.toString(source.getId()),
                    source.getVersion(),
                    new Binding(binding.proposal(), prior.getId()),
                    token);
        }
    }

    /**
     * 来源必须启用且未有另一项替换。
     */
    private void requireReady(DatasourcePO source) {
        if (source.getPendingChangeId() != null) {
            throw new CommonException(409506, 409, "已有凭据替换正在核实", null);
        }
        if (!"ENABLED".equals(source.getLifecycle())) {
            throw BizIllegalException.state("只有已启用的源可以更换运行凭据");
        }
    }

    /**
     * 非秘密提案的组合约束。
     */
    private void validate(CredentialChangeProposalDTO candidate) {
        if (!candidate.isCredentialProvided()
                || candidate.getAccount() == null
                || candidate.getAccount().isBlank()
                || candidate.getCheckId() == null) {
            throw BizIllegalException.invalid("请先测试拟替换的账号及密码");
        }
    }

    /**
     * 依据原任务实际进度展示当前操作的状态。
     */
    private CredentialChangeVO view(CredentialChangePO change) {
        var task = change.getBlockingTaskId() == null ? null : tasks.task(change.getBlockingTaskId(), false);
        DatasourcePO source = management.source(change.getSourceId(), false);
        var revision = sources.revision(source.getId(), source.getCurrentConnectionRevisionId());
        String status = task == null
                ? (change.getStopConfirmedAt() == null ? "NOT_STARTED" : "STOPPED")
                : task.getExecutionState();
        return new CredentialChangeVO(
                change.getId(),
                Long.toString(change.getSourceId()),
                change.getCommandId(),
                change.getState(),
                change.getBlockingTaskId(),
                status,
                instant(change.getAcceptedAt()),
                instant(change.getStopConfirmedAt()),
                instant(change.getCompletedAt()),
                revision.revision(),
                change.getFailureCode(),
                change.getFailureMessage());
    }

    /**
     * 转成实际 UTC 时刻。
     */
    private Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    /**
     * 仅用于服务端核对的实际提案和受影响任务。
     *
     * @param proposal 新账号和实际测试编号
     * @param taskId 当时受影响的任务
     */
    private record Binding(CredentialChangeProposalDTO proposal, String taskId) {}
}
