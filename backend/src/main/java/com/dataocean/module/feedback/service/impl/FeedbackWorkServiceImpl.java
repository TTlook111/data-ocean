package com.dataocean.module.feedback.service.impl;

import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.ASSIGN;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.CLOSE;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.IN_PROGRESS;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.PROCESS;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.SOURCE_DATA;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.SUBMITTED;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.TO_VERIFY;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.VERIFY;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.VIEW;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.active;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.assignable;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.canWork;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.optionalText;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.owner;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.ownsOpenWork;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.requireIssue;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.requiredText;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.feedback.entity.dto.AssignIssueDTO;
import com.dataocean.module.feedback.entity.dto.ChangeFeedbackTaskDTO;
import com.dataocean.module.feedback.entity.dto.CloseIssueDTO;
import com.dataocean.module.feedback.entity.dto.FeedbackProgressDTO;
import com.dataocean.module.feedback.entity.dto.ReviewIssueDTO;
import com.dataocean.module.feedback.entity.dto.SourceDataResultDTO;
import com.dataocean.module.feedback.entity.dto.SubmitIssueResultDTO;
import com.dataocean.module.feedback.entity.dto.SubmitKnowledgeResultDTO;
import com.dataocean.module.feedback.entity.dto.VerifyIssueDTO;
import com.dataocean.module.feedback.entity.vo.FeedbackClosurePreviewVO;
import com.dataocean.module.feedback.mapper.FeedbackLifecycleMapper;
import com.dataocean.module.feedback.mapper.FeedbackMapper;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Activity;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Issue;
import com.dataocean.module.feedback.mapper.FeedbackResolutionMapper;
import com.dataocean.module.feedback.mapper.FeedbackResolutionMapper.ResultRecord;
import com.dataocean.module.feedback.mapper.FeedbackResolutionMapper.Verification;
import com.dataocean.module.feedback.mapper.FeedbackTaskMapper;
import com.dataocean.module.feedback.mapper.FeedbackTaskMapper.Task;
import com.dataocean.module.feedback.service.FeedbackNoticeService;
import com.dataocean.module.feedback.service.FeedbackWorkService;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.query.entity.vo.QueryAcceptedVO;
import com.dataocean.module.query.service.QueryService;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 推进事项及任务责任、处理结果、复验与结案。
 */
@Service
@RequiredArgsConstructor
public class FeedbackWorkServiceImpl implements FeedbackWorkService {

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
     * 读取按权裁剪的原问题及实际复验事实。
     */
    private final QueryService queries;

    /**
     * 读取和持久化当前结果及复验。
     */
    private final FeedbackResolutionMapper resolutions;

    /**
     * 读取和交付站内回告。
     */
    private final FeedbackNoticeService notices;

    /**
     * 维护事项下独立任务及当前结果引用。
     */
    private final FeedbackTaskMapper tasks;

    /**
     * 原问补充、主从关系与完成轮次。
     */
    private final FeedbackLifecycleMapper lifecycle;

    /**
     * 在办理事务中统一锁定、去重和审计。
     */
    private final FeedbackIssueCommands mutations;

    /**
     * 共用复验、处理人资格和结案条件。
     */
    private final FeedbackVerificationPolicy verification;

    /**
     * 技术修复可登记的影响类别。
     */
    private static final Set<String> IMPACTS = Set.of("DISPLAY", "CONNECTION", "QUERY_SAFETY");

    /**
     * 主事项允许记录的初步分流方向。
     */
    private static final Set<String> DIRECTIONS =
            Set.of("UNDETERMINED", "KNOWLEDGE", "TECHNICAL", "SOURCE_DATA", "DEFINITION", "ACCESS");

    /**
     * 已接入实际处理与复验的独立任务方向。
     */
    private static final Set<String> TASK_DIRECTIONS = Set.of("KNOWLEDGE", "TECHNICAL", "SOURCE_DATA");

    /**
     * 在事项锁内分派主负责人，独立任务的责任和处理结果仍由各任务维护。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO assign(String id, String command, AssignIssueDTO input) {
        var normalized = new AssignIssueDTO(
                input.getExpectedVersion(),
                input.getAssigneeId(),
                input.getDirection(),
                requiredText(input.getReason(), "请填写分派依据"));
        long assignee = Ids.parse(normalized.getAssigneeId());
        if (!DIRECTIONS.contains(normalized.getDirection())) {
            // 仅记录正式分流允许的方向。
            throw BizIllegalException.invalid("请选择有效的初步方向");
        }
        return mutations.change(id, command, ASSIGN, input.getExpectedVersion(), normalized, row -> {
            // 在锁内检查当前阶段和候选人的资格。
            if (!assignable(row)) {
                // 结案或并入其他事项后不再单独分派。
                throw BizIllegalException.state("当前状态不能分派");
            }
            verification.requireCandidate(assignee);
            var work = tasks.list(row.id(), true);
            String status = taskStatus(work);
            if (feedback.assign(row.id(), row.version(), assignee, normalized.getDirection(), status) != 1) {
                // 分派与版本更新必须同时成功。
                throw BizIllegalException.conflict();
            }
            // 单独分流到源数据时直接建任务，避免再分派一次相同责任。
            if (work.isEmpty() && SOURCE_DATA.equals(normalized.getDirection())) {
                // 没有独立任务时为源数据责任建立首条任务。
                tasks.insert(new Task(
                        row.id(),
                        row.version() + 1,
                        SOURCE_DATA,
                        assignee,
                        null,
                        normalized.getReason(),
                        IN_PROGRESS,
                        false,
                        null,
                        null,
                        null,
                        false));
            }
            // 主负责人变化会撤销原复验，已交付的独立任务仍保留结果。
            if (!work.isEmpty()) {
                // 转派主负责人后旧复验不可作为结案依据。
                tasks.invalidate(row.id());
            }
            feedback.appendActivity(new Activity(
                    row.id(),
                    row.version() + 1,
                    UserContext.getUser(),
                    "ASSIGNED",
                    assignee,
                    normalized.getDirection(),
                    normalized.getReason(),
                    null,
                    null));
            return List.of(
                    new ChangeVO("status", "状态", row.status(), status),
                    new ChangeVO("assigneeId", "负责人编号", Ids.text(row.assigneeId()), Ids.text(assignee)),
                    new ChangeVO("direction", "初步方向", row.direction(), normalized.getDirection()));
        });
    }

    /**
     * 计算仍需办理的任务是否已全部提交，全部退出时保留说明结案入口。
     */
    private String taskStatus(List<Task> work) {
        return work.stream().noneMatch(task -> IN_PROGRESS.equals(task.status()))
                        && work.stream().anyMatch(task -> SUBMITTED.equals(task.status()))
                ? TO_VERIFY
                : IN_PROGRESS;
    }

    /**
     * 添加或转派治理、技术或源数据任务，并以主事项版本防止与其他动作互相覆盖。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO assignTask(String id, Integer taskId, String command, AssignIssueDTO input) {
        long assignee = Ids.parse(input.getAssigneeId());
        String direction = input.getDirection();
        String reason = requiredText(input.getReason(), "请填写任务范围或转派依据");
        if (!TASK_DIRECTIONS.contains(direction)) {
            // 任务只允许已接通结果与复验的办理方向。
            throw BizIllegalException.invalid("请选择治理、技术或源数据任务");
        }
        var normalized = new AssignIssueDTO(input.getExpectedVersion(), Ids.text(assignee), direction, reason);
        String action = taskId == null ? "TASK_ADDED" : "TASK_ASSIGNED";
        return mutations.change(
                id,
                command,
                ASSIGN,
                "feedback.task.assign",
                input.getExpectedVersion(),
                List.of("task-assignment", taskId == null ? 0 : taskId, normalized),
                row -> {
                    // 已确定主负责人后，才能将问题拆成独立任务。
                    if (row.assigneeId() == null || !assignable(row)) {
                        // 先存在可办理的主事项责任，才能分派其独立任务。
                        throw BizIllegalException.state("请先确认主事项负责人和当前办理状态");
                    }
                    verification.requireCandidate(assignee);
                    var work = tasks.list(row.id(), true);
                    int target;
                    if (taskId == null) {
                        // 首次拆分用初始事项版本标识原任务，带入核查和处理结果后重做复验。
                        if (work.isEmpty()
                                && TASK_DIRECTIONS.contains(row.direction())
                                && !SOURCE_DATA.equals(row.direction())) {
                            // 拆分时先迁入已有单任务的责任、结果和核查。
                            tasks.insert(new Task(
                                    row.id(),
                                    1,
                                    row.direction(),
                                    row.assigneeId(),
                                    null,
                                    null,
                                    TO_VERIFY.equals(row.status()) ? SUBMITTED : IN_PROGRESS,
                                    feedback.hasCurrentReview(row.id(), row.assigneeId()),
                                    row.resultVersion(),
                                    null,
                                    null,
                                    false));
                        }
                        target = row.version() + 1;
                        tasks.insert(new Task(
                                row.id(),
                                target,
                                direction,
                                assignee,
                                null,
                                reason,
                                IN_PROGRESS,
                                false,
                                null,
                                null,
                                null,
                                false));
                    } else {
                        // 已有任务只变更具体负责人及当期办理资格。
                        var task = requireTask(work, taskId);
                        // 转派已退出的任务保留退出状态，方便更换失效负责人后再恢复。
                        if (!direction.equals(task.direction()) || assignee == task.assigneeId()) {
                            // 转派保持任务方向且必须实际换人。
                            throw BizIllegalException.state("请选择同一任务下不同的负责人");
                        }
                        target = task.taskId();
                        tasks.update(
                                task, assignee, active(task) ? IN_PROGRESS : task.status(), false, null, null, false);
                    }
                    tasks.invalidate(row.id());
                    return recordTask(
                            row,
                            target,
                            action,
                            assignee,
                            direction,
                            reason,
                            null,
                            taskStatus(tasks.list(row.id(), true)));
                });
    }

    /**
     * 退出时核对剩余影响，恢复时重新核查，两个动作都保留原有处理历史。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO changeTaskStatus(String id, int taskId, String command, ChangeFeedbackTaskDTO input) {
        String status = input.getStatus();
        boolean restore = IN_PROGRESS.equals(status);
        String reason = requiredText(input.getReason(), "请填写办理依据");
        String impact = restore ? null : requiredText(input.getRemainingImpact(), "请说明剩余影响及安排");
        if (!restore && (!Set.of("NO_CHANGE", "CANCELLED").contains(status) || !input.isRemainingImpactCovered())) {
            // 退出前确认剩余影响已覆盖并使用有效的退出类型。
            throw BizIllegalException.invalid("请确认剩余影响均已覆盖后办理退出");
        }
        var normalized = new ChangeFeedbackTaskDTO(input.getExpectedVersion(), status, reason, impact, !restore);
        guard.shared();
        String permission = permissions.has(ASSIGN) ? ASSIGN : PROCESS;
        return mutations.change(
                id,
                command,
                permission,
                "feedback.task.disposition",
                input.getExpectedVersion(),
                List.of("task-disposition", taskId, normalized),
                row -> {
                    // 主负责人或协调者可办，其他任务负责人只能处理自己获派的工作。
                    if (!ASSIGN.equals(permission) && !Objects.equals(row.assigneeId(), UserContext.getUser())) {
                        // 普通处理员只能调整本人负责主事项的任务。
                        throw BizIllegalException.denied();
                    }
                    if (!assignable(row)) {
                        // 当前主事项必须仍在办理。
                        throw BizIllegalException.state("当前事项不能调整处理任务");
                    }
                    var task = requireTask(tasks.list(row.id(), true), taskId);
                    if (restore == active(task)) {
                        // 只允许在在办与已退出两类状态间切换。
                        throw BizIllegalException.state("任务状态已变化，请重新读取");
                    }
                    // 仍需外部修正的任务保留在办，确认等待已经结束后才可退出。
                    if (task.waitingExternal()) {
                        // 继续等待的任务要先更新跟进结论。
                        throw BizIllegalException.state("仍在等待修复生效，请先记录最新跟进结论");
                    }
                    if (restore) {
                        // 恢复办理前复核原负责人的权限与账号状态。
                        verification.requireCandidate(task.assigneeId());
                    }
                    tasks.update(
                            task, task.assigneeId(), status, false, restore ? null : task.resultVersion(), null, false);
                    tasks.invalidate(row.id());
                    return recordTask(
                            row,
                            taskId,
                            restore ? "TASK_RESTORED" : "TASK_" + status,
                            task.assigneeId(),
                            task.direction(),
                            reason,
                            impact,
                            taskStatus(tasks.list(row.id(), true)));
                });
    }

    /**
     * 任务状态、事项阶段和业务活动在同一事务内推进。
     */
    private List<ChangeVO> recordTask(
            Issue row,
            int taskId,
            String action,
            long assignee,
            String direction,
            String note,
            String impact,
            String status) {
        int next = row.version() + 1;
        if (tasks.advance(row.id(), row.version(), status) != 1) {
            // 主事项版本冲突时回滚任务和台账更新。
            throw BizIllegalException.conflict();
        }
        tasks.appendActivity(
                new Activity(row.id(), next, UserContext.getUser(), action, assignee, direction, note, impact, null),
                taskId);
        return List.of(
                new ChangeVO("taskId", "任务编号", null, Integer.toString(taskId)),
                new ChangeVO("status", "状态", row.status(), status));
    }

    /**
     * 当前负责人记录主事项或自己负责的任务核查，旧版本不能覆盖新分派。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO review(String id, Integer taskId, String command, ReviewIssueDTO input) {
        var normalized = new ReviewIssueDTO(
                input.getExpectedVersion(),
                requiredText(input.getBasis(), "请填写核查依据"),
                input.getRootCause() == null || input.getRootCause().isBlank()
                        ? null
                        : input.getRootCause().strip());
        return mutations.change(
                id, command, PROCESS, input.getExpectedVersion(), taskInput(taskId, normalized), row -> {
                    // 核查按事项或任务对应的当前负责人写入。
                    long actor = UserContext.getUser();
                    // 独立任务的核查由该任务负责人记录，不代替主事项核查。
                    if (taskId != null) {
                        // 独立任务在自己的记录中标记核查。
                        var task = requireTask(tasks.list(row.id(), true), taskId);
                        if (task.assigneeId() != actor
                                || !IN_PROGRESS.equals(row.status())
                                || !IN_PROGRESS.equals(task.status())) {
                            // 任务尚在办理且负责人保持一致才可核查。
                            throw BizIllegalException.denied();
                        }
                        tasks.update(
                                task,
                                actor,
                                task.status(),
                                true,
                                task.resultVersion(),
                                task.verificationVersion(),
                                task.waitingExternal());
                        return recordTask(
                                row,
                                taskId,
                                "TASK_REVIEWED",
                                actor,
                                task.direction(),
                                normalized.getBasis(),
                                normalized.getRootCause(),
                                row.status());
                    }
                    if (!owner(row, actor)) {
                        // 单事项核查由当前主负责人办理。
                        throw BizIllegalException.denied();
                    }
                    if (feedback.review(row.id(), row.version(), actor) != 1) {
                        // 未能推进事项版本时回滚核查。
                        throw BizIllegalException.conflict();
                    }
                    feedback.appendActivity(new Activity(
                            row.id(),
                            row.version() + 1,
                            actor,
                            "REVIEWED",
                            null,
                            null,
                            normalized.getBasis(),
                            normalized.getRootCause(),
                            null));
                    // 核查自由文本只存在有单独权限裁剪的事项记录中。
                    return List.of(new ChangeVO("review", "核查记录", null, "已记录"));
                });
    }

    /**
     * 当前技术负责人确认修复已生效后为对应的处理范围提交结果。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO submitResult(String id, Integer taskId, String command, SubmitIssueResultDTO input) {
        var impact = requiredText(input.getImpact(), "请选择修复影响范围");
        if (!IMPACTS.contains(impact) || !input.isEffectiveConfirmed()) {
            // 技术结果需要明确影响类别，并确认已生效。
            throw BizIllegalException.invalid("请选择影响范围并确认修复已生效");
        }
        boolean safety = "QUERY_SAFETY".equals(impact);
        if (safety && !Boolean.TRUE.equals(input.getRegressionPassed())) {
            // 查询或安全变更还要有相关场景的实际回归依据。
            throw BizIllegalException.invalid("查询与安全变更须确认相关场景测试已通过");
        }
        var normalized = new SubmitIssueResultDTO(
                input.getExpectedVersion(),
                impact,
                requiredText(input.getSummary(), "请填写修正说明"),
                requiredText(input.getCheckMethod(), "请填写复查方式与结果"),
                optionalText(input.getReference()),
                true,
                safety ? requiredText(input.getRegression(), "请填写相关场景测试结果") : null,
                safety);
        return mutations.change(
                id,
                command,
                PROCESS,
                "feedback.result.submit",
                input.getExpectedVersion(),
                taskInput(taskId, normalized),
                row -> {
                    // 在事项锁内核对技术负责人和本期核查。
                    long actor = UserContext.getUser();
                    var task = requireTechnicalTask(row, taskId, actor);
                    int next = row.version() + 1;
                    resolutions.appendResult(new ResultRecord(
                            row.id(),
                            next,
                            actor,
                            impact,
                            normalized.getSummary(),
                            normalized.getCheckMethod(),
                            normalized.getReference(),
                            normalized.getRegression(),
                            null,
                            null,
                            null));
                    return acceptResult(row, task, next, actor);
                });
    }

    /**
     * 治理负责人为当前处理范围提交已核实生效的知识版本及覆盖依据。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO submitKnowledge(String id, Integer taskId, String command, SubmitKnowledgeResultDTO input) {
        long binding = Ids.parse(input.getBindingId());
        var normalized = new SubmitKnowledgeResultDTO(
                input.getExpectedVersion(),
                Ids.text(binding),
                requiredText(input.getSummary(), "请填写补齐的知识"),
                requiredText(input.getCheckMethod(), "请填写原问题的覆盖依据"));
        return mutations.change(
                id,
                command,
                PROCESS,
                "feedback.knowledge.submit",
                input.getExpectedVersion(),
                taskInput(taskId, normalized),
                row -> {
                    // 发布结果归当前实际治理任务和负责人。
                    long actor = UserContext.getUser();
                    var task = resultTask(row, taskId, true);
                    if (!verification.canSubmitKnowledge(row, task, actor)) {
                        // 结果提交依赖原问和正式发布的当前权限。
                        throw BizIllegalException.denied();
                    }
                    verification.publishedBinding(row.sourceId(), binding, true);
                    if (lifecycle.usedBinding(row.id(), binding)) {
                        // 避免把原问题已经用过的发布登记为新修复。
                        throw BizIllegalException.state("该发布仍是关联原问题所质疑的版本");
                    }
                    int next = row.version() + 1;
                    resolutions.appendResult(new ResultRecord(
                            row.id(),
                            next,
                            actor,
                            "KNOWLEDGE",
                            normalized.getSummary(),
                            normalized.getCheckMethod(),
                            null,
                            null,
                            null,
                            binding,
                            null));
                    return acceptResult(row, task, next, actor);
                });
    }

    /**
     * 保存实际跟进。若当期修正重新进入跟进，则撤回当前结果和复验引用。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO followUpSourceData(String id, int taskId, String command, FeedbackProgressDTO input) {
        return followUp(id, taskId, command, input, SOURCE_DATA);
    }

    /**
     * 技术修复可保持在办，也可从待验证撤回到跟进。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO followUpTechnical(String id, Integer taskId, String command, FeedbackProgressDTO input) {
        return followUp(id, taskId, command, input, "TECHNICAL");
    }

    /**
     * 两类修正的跟进使用相同的撤回、等候和历史规则。
     */
    private MutationVO followUp(
            String id, Integer taskId, String command, FeedbackProgressDTO input, String direction) {
        boolean source = SOURCE_DATA.equals(direction);
        String kind = source ? "source" : "technical";
        var normalized = new FeedbackProgressDTO(
                input.getExpectedVersion(),
                input.isWaitingExternal(),
                requiredText(input.getWorkDone(), "请填写已做工作"),
                requiredText(input.getNextStep(), "请填写下一步安排"));
        return mutations.change(
                id,
                command,
                PROCESS,
                "feedback." + kind + ".followup",
                input.getExpectedVersion(),
                List.of(kind + "-followup", taskId == null ? 0 : taskId, normalized),
                row -> {
                    // 跟进记录先按实际任务方向核对当前责任和本期核查。
                    long actor = UserContext.getUser();
                    var task =
                            source ? requireSourceTask(row, taskId, actor) : requireTechnicalTask(row, taskId, actor);
                    int target = task == null ? 1 : task.taskId();
                    if (task == null) {
                        // 单技术事项沿用已有核查和负责人，首次跟进后用同一任务状态继续办理。
                        tasks.insert(new Task(
                                row.id(),
                                target,
                                direction,
                                actor,
                                null,
                                null,
                                IN_PROGRESS,
                                true,
                                null,
                                null,
                                null,
                                normalized.isWaitingExternal()));
                    } else {
                        // 撤回当前结果和复验引用，原修正与验收仍可在历史中回看。
                        tasks.update(task, actor, IN_PROGRESS, true, null, null, normalized.isWaitingExternal());
                    }
                    tasks.invalidate(row.id());
                    return recordTask(
                            row,
                            target,
                            (source ? "SOURCE_" : "TECHNICAL_")
                                    + (normalized.isWaitingExternal() ? "WAITING" : "FOLLOW_UP"),
                            actor,
                            direction,
                            normalized.getWorkDone(),
                            normalized.getNextStep(),
                            IN_PROGRESS);
                });
    }

    /**
     * 保存源系统实际修正与生效事实，此操作只写入平台处理台账。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO submitSourceData(String id, int taskId, String command, SourceDataResultDTO input) {
        if (!input.isEffectiveConfirmed()) {
            // 未同步生效仍按跟进保存，不推进到验证阶段。
            throw BizIllegalException.invalid("请确认源系统修正及必要同步已经生效");
        }
        var normalized = new SourceDataResultDTO(
                input.getExpectedVersion(),
                requiredText(input.getRepairer(), "请填写实际修正人或团队"),
                requiredText(input.getSummary(), "请填写修正的数据范围与内容"),
                requiredText(input.getCheckMethod(), "请填写只读复查方式与实际结果"),
                optionalText(input.getReference()),
                true);
        return mutations.change(
                id,
                command,
                PROCESS,
                "feedback.source.result",
                input.getExpectedVersion(),
                List.of("source-result", taskId, normalized),
                row -> {
                    // 记录人来自当前会话，实际修正人是独立的业务事实。
                    long actor = UserContext.getUser();
                    var task = requireSourceTask(row, taskId, actor);
                    int next = row.version() + 1;
                    resolutions.appendResult(new ResultRecord(
                            row.id(),
                            next,
                            actor,
                            SOURCE_DATA,
                            normalized.getSummary(),
                            normalized.getCheckMethod(),
                            normalized.getReference(),
                            null,
                            null,
                            null,
                            normalized.getRepairer()));
                    return acceptResult(row, task, next, actor);
                });
    }

    /**
     * 跟进和提交源数据结果由当前已核查的任务负责人办理。
     */
    private Task requireSourceTask(Issue row, int taskId, long actor) {
        var task = requireTask(tasks.list(row.id(), true), taskId);
        if (!SOURCE_DATA.equals(task.direction()) || !canWork(row, task, actor) || !task.reviewed()) {
            // 仅允许已核查的源数据任务负责人记录源系统事实。
            throw BizIllegalException.denied();
        }
        return task;
    }

    /**
     * 技术结果与跟进都由已核查的当前责任人保存，待验证期间仍可修订。
     */
    private Task requireTechnicalTask(Issue row, Integer taskId, long actor) {
        var task = resultTask(row, taskId, true);
        if (!(task == null ? ownsOpenWork(row, actor) : canWork(row, task, actor))) {
            // 转派后旧责任人无权继续写入。
            throw BizIllegalException.denied();
        }
        String direction = task == null ? row.direction() : task.direction();
        boolean reviewed = task == null ? feedback.hasCurrentReview(row.id(), actor) : task.reviewed();
        if (!"TECHNICAL".equals(direction) || !reviewed) {
            // 结果与跟进使用同一项当期核查作为入口。
            throw BizIllegalException.state("请先完成当前技术事项的核查记录");
        }
        return task;
    }

    /**
     * 两类办理结果使用同一版本和审计规则，主事项阶段由当期任务结果决定。
     */
    private List<ChangeVO> acceptResult(Issue row, Task task, int next, long actor) {
        // 新版本改变复验范围，其他任务已登记的复验也要重新确认。
        if (task != null) {
            // 独立任务提交后仍需满足其他必要任务的处理义务。
            tasks.update(task, actor, SUBMITTED, true, next, null, false);
            tasks.invalidate(row.id());
            return recordTask(
                    row,
                    task.taskId(),
                    "RESULT_SUBMITTED",
                    actor,
                    task.direction(),
                    "已提交处理结果",
                    null,
                    taskStatus(tasks.list(row.id(), true)));
        }
        if (resolutions.submit(row.id(), row.version(), next) != 1) {
            // 单事项结果引用和处理阶段需原子推进。
            throw BizIllegalException.conflict();
        }
        feedback.appendActivity(
                new Activity(row.id(), next, actor, "RESULT_SUBMITTED", null, null, "已提交处理结果", null, null));
        return List.of(new ChangeVO("status", "状态", row.status(), TO_VERIFY));
    }

    /**
     * 针对当前治理结果用原问题发起实际问数，复验人可在工作台查看结果。
     */
    @Override
    @Transactional
    public QueryAcceptedVO replayQuestion(String id, Integer taskId, String command) {
        guard.shared();
        permissions.require(VIEW);
        permissions.require(VERIFY);
        var row = requireIssue(feedback.issue(Ids.parse(id)));
        var task = resultTask(row, taskId, false);
        var result = verification.currentResult(row, task);
        if (row.pendingSupplement()
                || !TO_VERIFY.equals(row.status())
                || result == null
                || !verification.supportsReplay(result)
                || !verification.eligibleHandler(row, task)
                || verification.validVerification(row, task, verification.currentVerification(row, task), false)) {
            // 没有待核对结果或已有有效复验时无需重跑。
            throw BizIllegalException.state("当前处理结果不处于可问数复验状态");
        }
        var scope = verification.replayScope(UserContext.getUser(), row.sourceId());
        if (scope == null) {
            // 发起真实问数要求复验人具有当前原问和问数权限。
            throw BizIllegalException.denied();
        }
        // 只有治理修正需要先确认提交时关联的发布仍然生效。
        if (result.knowledgeBindingId() != null) {
            // 治理结果还需要确认指定发布仍可用。
            verification.publishedBinding(row.sourceId(), result.knowledgeBindingId(), false);
        }
        return queries.replayFeedback(row.requestId(), scope, command);
    }

    /**
     * 人工复验绑定当期处理结果和操作者的源权限，失败退回对应任务。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO verify(String id, Integer taskId, String command, VerifyIssueDTO input) {
        var decision = requiredText(input.getDecision(), "请选择复验结论");
        if (!Set.of("PASSED", "FAILED").contains(decision)) {
            // 只记录人工作出的两种明确复验结论。
            throw BizIllegalException.invalid("复验结论无效");
        }
        var normalized = new VerifyIssueDTO(
                input.getExpectedVersion(),
                decision,
                requiredText(input.getTestScope(), "请填写复验场景"),
                requiredText(input.getExpected(), "请填写期望结果"),
                requiredText(input.getActual(), "请填写实际结果"),
                requiredText(input.getDataBasis(), "请填写数据与复验依据"),
                optionalText(input.getRecheckRequestId()));
        return mutations.change(id, command, VERIFY, input.getExpectedVersion(), taskInput(taskId, normalized), row -> {
            // 复验绑定已核对的事项版本、当前结果和复验人。
            verification.requireCompleteEvidence(row);
            var task = resultTask(row, taskId, true);
            var result = verification.currentResult(row, task);
            if (!TO_VERIFY.equals(row.status())
                    || result == null
                    || !verification.eligibleHandler(row, task)
                    || (task != null && !SUBMITTED.equals(task.status()))) {
                // 负责人或对应结果变化后请先核对办理事实。
                throw BizIllegalException.state("当前处理结果或负责人资格已变化，请重新核查");
            }
            long verifier = UserContext.getUser();
            var context = verification.verificationContext(verifier, row.sourceId());
            if (context == null) {
                // 权限撤回后不使用历史源授权代替当前资格。
                throw BizIllegalException.denied();
            }
            boolean passed = "PASSED".equals(decision);
            if (result.knowledgeBindingId() != null
                    || (SOURCE_DATA.equals(result.impact()) && normalized.getRecheckRequestId() != null)) {
                // 对要求或主动附带实际问数的结果核对执行依据。
                if (!verification.matchesRecheck(
                        row, task, result, verifier, normalized.getRecheckRequestId(), passed, true)) {
                    // 实际问数须使用当期范围、授权和发布。
                    throw BizIllegalException.state("请核对当前修正范围、实际问数轮次和数据依据");
                }
            } else if (normalized.getRecheckRequestId() != null) {
                // 只登记人工复查的技术结果不使用问数引用。
                throw BizIllegalException.invalid("技术复验使用实际复查记录");
            }
            String nextStatus = passed ? TO_VERIFY : IN_PROGRESS;
            int next = row.version() + 1;
            resolutions.appendVerification(new Verification(
                    row.id(),
                    next,
                    result.version(),
                    verifier,
                    context,
                    decision,
                    normalized.getTestScope(),
                    normalized.getExpected(),
                    normalized.getActual(),
                    normalized.getDataBasis(),
                    null,
                    normalized.getRecheckRequestId()));
            // 复验不通过仅打回对应任务，事项等待该任务补正。
            if (task != null) {
                // 保留该任务自己的当期结果，失败只退回对应任务。
                tasks.update(
                        task,
                        task.assigneeId(),
                        passed ? SUBMITTED : IN_PROGRESS,
                        task.reviewed(),
                        result.version(),
                        next,
                        false);
                return recordTask(
                        row,
                        task.taskId(),
                        passed ? "VERIFIED" : "RECHECK_FAILED",
                        task.assigneeId(),
                        task.direction(),
                        "已登记复验",
                        null,
                        nextStatus);
            }
            if (resolutions.verify(row.id(), row.version(), next, nextStatus) != 1) {
                // 单事项的复验引用和阶段同时更新。
                throw BizIllegalException.conflict();
            }
            feedback.appendActivity(new Activity(
                    row.id(), next, verifier, passed ? "VERIFIED" : "RECHECK_FAILED", null, null, "已登记复验", null, null));
            return List.of(
                    new ChangeVO("verification", "复验结论", null, passed ? "通过" : "未通过"),
                    new ChangeVO("status", "状态", row.status(), nextStatus));
        });
    }

    /**
     * 保持原单任务命令的防重指纹，新增任务命令同时固定目标任务。
     */
    private Object taskInput(Integer taskId, Object input) {
        return taskId == null ? input : List.of(taskId, input);
    }

    /**
     * 事项拆分后结果动作必须指明任务，同时阻止借用其他事项的任务编号。
     */
    private Task resultTask(Issue row, Integer taskId, boolean lock) {
        var work = tasks.list(row.id(), lock);
        if (taskId != null) {
            // 明确指定任务时只从本事项的任务中查找。
            return requireTask(work, taskId);
        }
        if (!work.isEmpty()) {
            // 已拆分的事项不得回写旧单任务引用。
            throw BizIllegalException.state("请选择反馈处理任务");
        }
        return null;
    }

    /**
     * 任务编号在当前事项内解析。
     */
    private Task requireTask(List<Task> work, int taskId) {
        return work.stream()
                .filter(task -> task.taskId() == taskId)
                .findFirst()
                .orElseThrow(BizIllegalException::missing);
    }

    /**
     * 只读预览确认当前状态、原提问人和将提交的受控回告。
     */
    @Override
    @Transactional
    public FeedbackClosurePreviewVO closurePreview(String id, String outcome, String reason) {
        guard.shared();
        permissions.require(CLOSE);
        var row = requireIssue(feedback.issue(Ids.parse(id)));
        return notices.preview(row, verification.closureTemplate(row, outcome, reason, false));
    }

    /**
     * 结案和回告接收一同落盘，写事务在锁内核对当期复验。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO close(String id, String command, CloseIssueDTO input) {
        var normalized = new CloseIssueDTO(
                input.getExpectedVersion(),
                input.getOutcome(),
                optionalText(input.getReason()),
                requiredText(input.getConclusion(), "请填写结案结论"),
                requiredText(input.getBasis(), "请填写结案依据"),
                input.getRecipientId(),
                input.getConfirmedTemplate(),
                optionalText(input.getConfirmedRecipients()));
        return mutations.change(id, command, CLOSE, input.getExpectedVersion(), normalized, row -> {
            // 在同一锁内复核当期结果和先前核对的对外回告。
            String template = verification.closureTemplate(row, normalized.getOutcome(), normalized.getReason(), true);
            var preview = notices.preview(row, template);
            if (!preview.getRecipientId().equals(normalized.getRecipientId())
                    || !preview.getTemplate().equals(normalized.getConfirmedTemplate())
                    || !Objects.equals(preview.getConfirmation(), normalized.getConfirmedRecipients())) {
                // 任一接收人或模板变化都要求重新核对。
                throw BizIllegalException.conflict();
            }
            if (resolutions.close(row.id(), row.version(), normalized.getOutcome()) != 1) {
                // 只有当前版本能完成结案及后续回告。
                throw BizIllegalException.conflict();
            }
            feedback.appendActivity(new Activity(
                    row.id(),
                    row.version() + 1,
                    UserContext.getUser(),
                    "CLOSED",
                    null,
                    null,
                    normalized.getBasis(),
                    null,
                    normalized.getConclusion()));
            notices.enqueue(row, template, command, row.version() + 1);
            return List.of(new ChangeVO("status", "状态", row.status(), normalized.getOutcome()));
        });
    }
}
