package com.dataocean.module.knowledge.service.impl;

import com.dataocean.common.Ids;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.knowledge.entity.dto.KnowledgeVersionRestoreDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeVersionControlVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeVersionControlVO.Recovery;
import com.dataocean.module.knowledge.entity.vo.KnowledgeVersionControlVO.RunDetail;
import com.dataocean.module.knowledge.mapper.KnowledgeBuildMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;
import com.dataocean.module.knowledge.mapper.KnowledgeValidationMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeVersionPauseMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeVersionPauseMapper.Pause;
import com.dataocean.module.knowledge.service.KnowledgeRecoveryService;
import com.dataocean.module.knowledge.service.KnowledgeReleasePreflight;
import com.dataocean.module.knowledge.service.KnowledgeVersionService;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 编排正式版本的隔离恢复验证、历史和解除限制。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeRecoveryServiceImpl implements KnowledgeRecoveryService {

    /**
     * 记录和读取正式绑定的独立恢复验证。
     */
    private final KnowledgeValidationMapper runs;

    /**
     * 核对当期限制并原子变更恢复指针。
     */
    private final KnowledgeVersionPauseMapper pauses;

    /**
     * 按来源读取不可变的版本绑定。
     */
    private final KnowledgePublicationMapper bindings;

    /**
     * 核对当前版本使用与限制操作的实际资格。
     */
    private final KnowledgeVersionService versionUse;

    /**
     * 刷新恢复后影响的撤下事实。
     */
    private final KnowledgeWithdrawalProgress withdrawalProgress;

    /**
     * 核对实际存储代次与恢复证据。
     */
    private final KnowledgeIndexReadback indexes;

    /**
     * 读取恢复时使用的构建事实。
     */
    private final KnowledgeBuildMapper builds;

    /**
     * 在当前执行读写条件下核对来源。
     */
    private final GovernanceCatalog catalog;

    /**
     * 复用同一正式题单解析。
     */
    private final KnowledgeReleasePreflight preflight;

    /**
     * 沿用候选与恢复验证共用的执行事实及证据校验。
     */
    private final KnowledgeValidations validation;

    /**
     * 用现有命令回执原子执行业务写入。
     */
    private final MutationService commands;

    /**
     * 运行当前正式绑定的隔离验证，保留原发布和原暂停状态。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO startRecovery(long sourceId, long bindingId, long pauseId, String command) {
        return commands.execute(
                "releases.test",
                command,
                recoveryTarget(sourceId, bindingId, pauseId),
                Ids.text(pauseId),
                null,
                false,
                false,
                () -> {
                    // 先核对当前操作者权限，再锁定实际办理来源。
                    validation.requireReader(sourceId);
                    var source = catalog.source(sourceId, true);
                    var binding = versionUse.current(sourceId, bindingId);
                    var pause = validation.requirePause(binding, pauseId);
                    var build = validation.recoveryBuild(source, binding);
                    var tests =
                            preflight.requireRelatedTests(sourceId, binding.documentId(), binding.documentRevisionId());
                    if (pause.currentRunId() != null) {
                        // 同一有效运行保留；已失效的在途运行收敛后才能登记新输入。
                        var previous = validation.readRun(pause.currentRunId());
                        if (validation.running(previous)) {
                            // 复用当期运行的状态判断，避免并行占用同一暂停。
                            if (validation.remaining(previous) > 0
                                    && validation.recoveryIssue(source, previous) == null) {
                                // 仍有有效恢复任务时拒绝重新登记另一轮。
                                throw BizIllegalException.state("恢复验证正在运行");
                            }
                            validation.invalidate(previous, "INPUT_STALE");
                        }
                    }
                    String id = UUID.randomUUID().toString();
                    runs.create(
                            id,
                            binding.candidateId(),
                            build.id(),
                            UserContext.getUser(),
                            UserContext.current().authenticationVersion(),
                            "releases.test",
                            source.configurationId(),
                            pauseId,
                            bindingId);
                    if (pauses.link(pauseId, pause.currentRunId(), id) != 1) {
                        // 并发变更不能覆盖正在使用的暂停和验证指针。
                        throw BizIllegalException.conflict();
                    }
                    validation.addQuestions(source, id, tests);
                    validation.audit(
                            command, "releases.test", "knowledge_recovery_validation", id, binding.title(), null);
                    return new MutationVO(command, "knowledge_recovery_validation", id, 1);
                });
    }

    /**
     * 有权人员确认风险消除，再次核验当前版本、题单、证据和完整索引。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO restore(
            long sourceId, long bindingId, long pauseId, String command, KnowledgeVersionRestoreDTO input) {
        return commands.execute(
                "governance.allow",
                command,
                recoveryTarget(sourceId, bindingId, pauseId),
                input,
                null,
                false,
                false,
                () -> {
                    validation.requireReader(sourceId);
                    var source = catalog.source(sourceId, true);
                    var binding = versionUse.current(sourceId, bindingId);
                    var pause = validation.requirePause(binding, pauseId);
                    if (!input.getRunId().toString().equals(pause.currentRunId())) {
                        // 先以当前暂停确定证据归属，拒绝旧编号和任意其他运行。
                        throw BizIllegalException.state("请核对当前版本已通过的恢复验证");
                    }
                    var run = validation.readRun(pause.currentRunId());
                    if (!Objects.equals(run.recoveryBindingId(), bindingId) || !"RECOVERY_PASSED".equals(run.state())) {
                        // 提交必须采用此范围最近一轮真正通过的隔离验证。
                        throw BizIllegalException.state("请核对当前版本已通过的恢复验证");
                    }
                    var build = validation.verifyRecoveryRun(source, run);
                    var candidate = builds.candidate(sourceId, binding.candidateId());
                    indexes.verify(
                            build,
                            binding.documentRevisionId(),
                            binding.embeddingRevisionId(),
                            candidate.getFingerprint());
                    // 索引回读期间模型或用户的可用性仍可能变化。
                    validation.verifyRecoveryRun(source, run);
                    String reason = input.getReason().strip();
                    if (pauses.restore(pauseId, run.id(), reason, UserContext.getUser()) != 1) {
                        // 暂停或本轮验证变化时拒绝覆盖当前办理事实。
                        throw BizIllegalException.conflict();
                    }
                    withdrawalProgress.refresh(sourceId);
                    validation.audit(
                            command,
                            "governance.allow",
                            "knowledge_version_pause",
                            Ids.text(pauseId),
                            binding.title(),
                            reason);
                    return new MutationVO(command, "knowledge_version_pause", Ids.text(pauseId), 2);
                });
    }

    /**
     * 版本详情保留暂停范围与历史，旧依据不会显示成可恢复。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public KnowledgeVersionControlVO controls(long sourceId, long id) {
        versionUse.requireOperator(sourceId);
        var source = catalog.source(sourceId, false);
        var binding = readBinding(sourceId, id);
        var pause = Objects.equals(binding.activeBindingId(), id) && !versionUse.isWithdrawn(id)
                ? pauses.active(binding.scopeId())
                : null;
        var history =
                pauses.history(binding.scopeId()).stream().map(this::viewPause).toList();
        return new KnowledgeVersionControlVO(
                pause == null ? null : viewPause(pause),
                pause == null ? null : recoveryStatus(source, binding, pause),
                history);
    }

    /**
     * 验证页可重读旧轮的技术证据，但只允许办理当前有效的一轮。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RunDetail recoveryDetail(long sourceId, long id, String runId) {
        versionUse.requireOperator(sourceId);
        var source = catalog.source(sourceId, false);
        var binding = readBinding(sourceId, id);
        var run = validation.forTask(sourceId, binding.taskId(), runId);
        if (!Objects.equals(run.recoveryBindingId(), id)) {
            // 只返回实际用于此绑定的恢复验证。
            throw BizIllegalException.missing();
        }
        boolean current = validation.recoveryIssue(source, run) == null;
        var history = runs.recoveryHistory(run.recoveryPauseId(), id);
        return new RunDetail(
                Ids.text(binding.taskId()),
                validation.task(sourceId, binding.taskId()).version(),
                Ids.text(run.recoveryPauseId()),
                current,
                validation.validationDetail(sourceId, run, history));
    }

    /**
     * 前端操作权限与业务状态各自核对，状态下发需如实标明失效原因。
     */
    private Recovery recoveryStatus(Source source, Binding binding, Pause pause) {
        String blocked = null;
        try {
            // 冻结的治理依据变化时先重新确认正文，不能用旧索引继续验证。
            validation.recoveryBuild(source, binding);
            preflight.requireRelatedTests(source.id(), binding.documentId(), binding.documentRevisionId());
        } catch (BizIllegalException unsuitable) {
            // 将当前不满足的已知业务依据作为阻断原因。
            blocked = unsuitable.getMessage();
        }
        var run = pause.currentRunId() == null ? null : runs.get(pause.currentRunId());
        if (run == null || !Objects.equals(run.recoveryBindingId(), binding.id())) {
            // 当前绑定没有自己的有效运行时按待验证展示。
            return new Recovery("UNVERIFIED", null, blocked, blocked == null);
        }
        String stale = blocked == null ? validation.recoveryIssue(source, run) : blocked;
        if (stale != null) {
            // 历史证据仍可回看，过期状态不能用于解除。
            return new Recovery("EXPIRED", run.id(), stale, blocked == null);
        }
        return new Recovery(run.state(), run.id(), null, !validation.running(run));
    }

    /**
     * 同一记录返回发起、解除及最近验证的可信留痕。
     */
    private KnowledgeVersionControlVO.Pause viewPause(Pause pause) {
        return new KnowledgeVersionControlVO.Pause(
                Ids.text(pause.id()),
                Ids.text(pause.bindingId()),
                pause.reason(),
                Ids.text(pause.pausedBy()),
                Times.instant(pause.pausedAt()),
                pause.currentRunId(),
                Ids.text(pause.runBindingId()),
                pause.restoreReason(),
                Ids.text(pause.restoredBy()),
                Times.instant(pause.restoredAt()));
    }

    /**
     * 隔离动作命中页面确切范围和同一次暂停。
     */
    private String recoveryTarget(long source, long binding, long pause) {
        return "knowledge-recovery:" + source + "/" + binding + "/" + pause;
    }

    /**
     * 按确切来源回读绑定，不接受其他数据源的绑定编号。
     */
    private Binding readBinding(long source, long id) {
        var binding = bindings.get(source, id);
        if (binding == null) {
            // 用统一资源缺失语义返回未知绑定。
            throw BizIllegalException.missing();
        }
        return binding;
    }
}
