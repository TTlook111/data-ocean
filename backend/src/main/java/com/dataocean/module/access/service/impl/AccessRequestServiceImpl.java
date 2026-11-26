package com.dataocean.module.access.service.impl;

import static com.dataocean.module.access.service.impl.AccessConfigurationServiceImpl.pattern;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.entity.dto.AccessRequestDTO;
import com.dataocean.module.access.entity.dto.RequestActionDTO;
import com.dataocean.module.access.entity.po.AccessGrantPO;
import com.dataocean.module.access.entity.po.AccessRequestItemPO;
import com.dataocean.module.access.entity.po.AccessRequestPO;
import com.dataocean.module.access.entity.query.AccessListQuery;
import com.dataocean.module.access.entity.vo.AccessRequestVO;
import com.dataocean.module.access.entity.vo.AccessSourceOptionVO;
import com.dataocean.module.access.entity.vo.RequestActivityVO;
import com.dataocean.module.access.entity.vo.RequestApplicationVO;
import com.dataocean.module.access.entity.vo.RequestItemVO;
import com.dataocean.module.access.mapper.AccessGrantMapper;
import com.dataocean.module.access.mapper.AccessReadMapper;
import com.dataocean.module.access.mapper.AccessRequestItemMapper;
import com.dataocean.module.access.mapper.AccessRequestMapper;
import com.dataocean.module.access.service.AccessRequestService;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ReviewService;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 在同一权限配置锁内执行申请、分流、批准和来源生效。
 */
@Service
@RequiredArgsConstructor
public class AccessRequestServiceImpl implements AccessRequestService {

    /**
     * 申请聚合与版本。
     */
    private final AccessRequestMapper requests;

    /**
     * 保存实际子项状态与决定。
     */
    private final AccessRequestItemMapper items;

    /**
     * 个人来源由发布批准事实的同一事务保存。
     */
    private final AccessGrantMapper grants;

    /**
     * 队列、历史与数据库业务时间。
     */
    private final AccessReadMapper reads;

    /**
     * 公共规则和稳定引用核对。
     */
    private final AccessFacts facts;

    /**
     * 配置锁、审计与命令回执。
     */
    private final MutationService mutations;

    /**
     * 按当前能力控制队列与管理动作。
     */
    private final PermissionService permissions;

    /**
     * 核对签名与全平台授权变更版本绑定。
     */
    private final ReviewService reviews;

    /**
     * 保存约束明确的历史内容。
     */
    private final JsonCodec json;

    /**
     * 队列按数据库筛选和计数，详情维持相同的可见范围。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<AccessRequestVO> list(AccessListQuery query, boolean mine) {
        long actor = UserContext.getUser();
        String queue = mine ? "MINE" : Objects.requireNonNullElse(query.getQueue(), "PENDING");
        switch (queue) {
                // 各分组独立核对，避免用一个页面权限查阅全部申请。
            case "MINE" -> permissions.require("my-requests.view");
            case "ROUTING" -> permissions.require("access-requests.route");
            case "PENDING", "HANDLED" -> permissions.require("access-requests.review");
            default -> throw BizIllegalException.invalid("不支持的审批分组");
        }
        if (!mine && "MINE".equals(queue)) {
            // 本人视角由独立入口提供。
            throw BizIllegalException.denied();
        }
        return PageVO.from(
                reads.requests(
                        query.toFixedOrderMpPage(OrderItem.desc("r.id")),
                        actor,
                        queue,
                        Ids.nullable(query.getSourceId()),
                        query.getState(),
                        pattern(query.getKeyword()),
                        pattern(query.getApplicant()),
                        Ids.nullable(query.getRequestId()),
                        start(query.getDateFrom(), query.getTimeZone()),
                        stop(query.getDateTo(), query.getTimeZone())),
                request -> queueView(request, actor, queue, query));
    }

    /**
     * 回看时只展示当前身份能访问的子项。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AccessRequestVO detail(long id) {
        var request = requireRequest(id);
        long actor = UserContext.getUser();
        var visible = visible(request, actor, null);
        if (visible.isEmpty()) {
            // 无法查看单据时不披露申请是否存在。
            throw BizIllegalException.missing();
        }
        return view(request, visible);
    }

    /**
     * 历史结果使用当前处理者自己的办理版本，不混入新接手人的待办。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AccessRequestVO handledDetail(long id) {
        permissions.require("access-requests.review");
        var request = requireRequest(id);
        var result = queueView(request, UserContext.getUser(), "HANDLED", new AccessListQuery());
        if (result.getItems().isEmpty()) {
            // 不能用历史入口读取未曾办理的单据。
            throw BizIllegalException.missing();
        }
        return result;
    }

    /**
     * 流程历史只向本人提供，提交人和经办人以持久操作事实为准。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RequestActivityVO> activities(long id) {
        long actor = UserContext.getUser();
        checkOwner(requireRequest(id), actor);
        return reads.activities(id, actor).stream()
                .map(row -> new RequestActivityVO(
                        row.version(), row.action(), facts.person(row.actorId()), Times.instant(row.createdAt())))
                .toList();
    }

    /**
     * 本人草稿和补充沿用申请实体和版本。
     */
    @Override
    public MutationVO save(Long id, String command, AccessRequestDTO input) {
        String operation = "my-requests.apply";
        return mutations.execute(
                operation,
                command,
                id == null ? "new" : id.toString(),
                input,
                input.getExpectedVersion(),
                true,
                false,
                () -> {
                    // 新单和已有单据均从当前会话确定申请人。
                    long actor = UserContext.getUser();
                    var sourceIds = AccessFacts.ids(input.getSourceIds());
                    sourceIds.forEach(facts::enabledSource);
                    if (id == null) {
                        // 从无记录开始，只接受首次保存。
                        if (input.getExpectedVersion() != null) {
                            throw BizIllegalException.conflict();
                        }
                        var request = new AccessRequestPO();
                        request.setApplicantId(actor);
                        request.setVersion(1);
                        request.setJoint(false);
                        applyText(request, input);
                        if (!input.getDraft()) {
                            // 提交后才进入责任队列。
                            request.setSubmittedAt(reads.now());
                        }
                        requests.insert(request);
                        insertItems(request.getId(), sourceIds, input.getDraft() ? "DRAFT" : "PENDING");
                        return saveRevision(
                                request,
                                operation,
                                command,
                                input.getDraft() ? "DRAFT" : "SUBMIT",
                                null,
                                List.of(new ChangeVO("state", "申请状态", null, input.getDraft() ? "DRAFT" : "PENDING")));
                    }
                    var request = requireRequest(id);
                    checkOwner(request, actor);
                    if (!Objects.equals(request.getVersion(), input.getExpectedVersion())) {
                        // 本人使用最新版本更新草稿或重提。
                        throw BizIllegalException.conflict();
                    }
                    var before = facts.items(id);
                    boolean firstSubmission = request.getSubmittedAt() == null;
                    if (firstSubmission) {
                        // 仅尚无任何办理事实的草稿可以替换子项集合。
                        requireOpenDraft(before);
                        replaceItems(id, sourceIds, input.getDraft() ? "DRAFT" : "PENDING");
                        if (!input.getDraft()) {
                            // 保存首次提交时刻。
                            request.setSubmittedAt(reads.now());
                        }
                        applyText(request, input);
                    } else {
                        // 已提交后只可就补充要求重提，不自行扩大已核定的源。
                        var reset = supplementItems(request, before, input.getDraft());
                        for (var item : reset) {
                            // 单据历史与经办关系仍由之前的修订记录保留。
                            resetItem(item, item.getSourceId());
                            if (reset.size() != before.size() || item.getApplicationPurpose() != null) {
                                // 只改涉及补充的独立子项，保存其余待办或已生效子项的原始事实。
                                items.update(Wrappers.<AccessRequestItemPO>lambdaUpdate()
                                        .eq(AccessRequestItemPO::getId, item.getId())
                                        .set(
                                                AccessRequestItemPO::getApplicationPurpose,
                                                input.getPurpose().trim())
                                        .set(
                                                AccessRequestItemPO::getApplicationDataNeeded,
                                                input.getDataNeeded().trim())
                                        .set(
                                                AccessRequestItemPO::getApplicationSupplement,
                                                Objects.requireNonNullElse(input.getSupplement(), "")
                                                        .trim()));
                            }
                        }
                        if (reset.size() == before.size()) {
                            // 全组都重提才更新整单资料。
                            applyText(request, input);
                        }
                    }
                    advance(request);
                    return saveRevision(
                            request,
                            operation,
                            command,
                            input.getDraft() ? "DRAFT" : firstSubmission ? "SUBMIT" : "RESUBMIT",
                            null,
                            List.of(new ChangeVO("state", "申请状态", null, input.getDraft() ? "DRAFT" : "PENDING")));
                });
    }

    /**
     * 按当前账号、已保存子项与真实责任生成申请提交的只读核对。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ImpactPreviewVO previewSubmission(Long id, AccessRequestDTO input) {
        permissions.require("my-requests.apply");
        long actor = UserContext.getUser();
        List<Long> sourceIds;
        if (id == null) {
            // 新建草稿或申请不存在历史版本。
            if (input.getExpectedVersion() != null) {
                throw BizIllegalException.conflict();
            }
            sourceIds = AccessFacts.ids(input.getSourceIds());
            sourceIds.forEach(facts::enabledSource);
        } else {
            var request = requireRequest(id);
            checkOwner(request, actor);
            if (!Objects.equals(request.getVersion(), input.getExpectedVersion())) {
                // 原草稿或补充要求已经变化时要求回读。
                throw BizIllegalException.conflict();
            }
            var items = facts.items(id);
            if (request.getSubmittedAt() == null) {
                requireOpenDraft(items);
                sourceIds = AccessFacts.ids(input.getSourceIds());
                sourceIds.forEach(facts::enabledSource);
            } else {
                // 补充表单隐藏选源时，读取受影响子项的实际来源。
                sourceIds = supplementItems(request, items, input.getDraft()).stream()
                        .map(AccessRequestItemPO::getSourceId)
                        .toList();
            }
        }
        var changes = new ArrayList<ChangeVO>();
        var states = new LinkedHashSet<String>();
        for (Long source : sourceIds.isEmpty() ? Collections.<Long>singletonList(null) : sourceIds) {
            // 已知来源也可能没有现任接手人。
            String status = input.getDraft() ? "草稿" : facts.assignee(source, actor) == null ? "待分流" : "待审批";
            states.add(status);
            changes.add(new ChangeVO(
                    "source",
                    "数据源",
                    null,
                    sourceName(source) + (input.getDraft() || source == null ? "" : " · " + status)));
        }
        changes.add(new ChangeVO("state", "确认后状态", null, String.join("、", states)));
        return reviews.preview(
                "SUBMISSION",
                id == null ? "new" : id.toString(),
                input.getExpectedVersion(),
                input,
                changes,
                List.of(),
                List.of());
    }

    /**
     * 删除后的草稿不能进入编辑核对或重新提交。
     */
    private void requireOpenDraft(List<AccessRequestItemPO> items) {
        if (items.stream().noneMatch(item -> "DRAFT".equals(item.getState()))) {
            // 已删草稿只留存历史审计。
            throw BizIllegalException.state("草稿已删除");
        }
    }

    /**
     * 独立申请仅重提需补充的子项，联合申请整组重新核定。
     */
    private List<AccessRequestItemPO> supplementItems(
            AccessRequestPO request, List<AccessRequestItemPO> items, boolean draft) {
        if (draft || items.stream().noneMatch(item -> "NEEDS_SUPPLEMENT".equals(item.getState()))) {
            // 生效、关闭或没有补充要求的单据不能直接覆盖。
            throw BizIllegalException.state("当前申请没有待补充子项");
        }
        return items.stream()
                .filter(item -> request.getJoint() || "NEEDS_SUPPLEMENT".equals(item.getState()))
                .toList();
    }

    /**
     * 动作编码用于声明命令权限，资源资格在锁内验证。
     */
    @Override
    public MutationVO act(long id, String command, RequestActionDTO input) {
        String operation = operation(input.getAction());
        return mutations.execute(
                operation, command, Long.toString(id), input, input.getExpectedVersion(), true, false, () -> {
                    // 整单版本覆盖联合子项之间的批准竞争。
                    var request = requireRequest(id);
                    long actor = UserContext.getUser();
                    List<AccessRequestItemPO> group = facts.items(id);
                    verifyAction(request, group, actor, input);
                    if (input.getReviewToken() == null || input.getReviewToken().isBlank()) {
                        // 必须从当前办理影响预览取得签名。
                        throw BizIllegalException.invalid("请先核对办理内容");
                    }
                    reviews.verify(
                            input.getAction(), Long.toString(id), request.getVersion(), input, input.getReviewToken());
                    if ("WITHDRAW".equals(input.getAction())) {
                        // 本人仅可撤回未生效项目。
                        checkOwner(request, actor);
                        withdraw(group, input.getItemId(), request.getJoint());
                    } else {
                        // 分流和审批均不允许用管理员身份扩张到本人额外数据访问。
                        if (!facts.enabledUser(request.getApplicantId())) {
                            throw BizIllegalException.state("申请账号当前不可办理");
                        }
                        if ("PLAN".equals(input.getAction())) {
                            plan(request, group, input);
                        } else {
                            handle(request, group, actor, input);
                        }
                    }
                    advance(request);
                    var after = facts.items(id);
                    var changes = new ArrayList<ChangeVO>();
                    changes.add(new ChangeVO("decision", "办理动作", null, input.getAction()));
                    after.forEach(item -> {
                        // 审计同一条记录包含各子项和实际结果，批准未生效时标为已批准。
                        changes.add(new ChangeVO(
                                "item",
                                "子项与生效状态",
                                item.getSourceId() == null
                                        ? null
                                        : facts.source(item.getSourceId()).getName(),
                                item.getState()));
                    });
                    return saveRevision(request, operation, command, input.getAction(), input.getReason(), changes);
                });
    }

    /**
     * 预览人员、规则及提案的实际前置条件，正式提交仍在锁内复核。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ImpactPreviewVO preview(long id, RequestActionDTO input) {
        permissions.require(operation(input.getAction()));
        var request = requireRequest(id);
        var group = facts.items(id);
        verifyAction(request, group, UserContext.getUser(), input);
        var changes = new ArrayList<ChangeVO>();
        if (!"WITHDRAW".equals(input.getAction()) && !facts.enabledUser(request.getApplicantId())) {
            // 停用的申请人仍可保留历史，但不能继续核定或批准。
            throw BizIllegalException.state("申请账号当前不可办理");
        }
        switch (input.getAction()) {
            case "ROUTE" -> {
                // 核定前展示当前真实负责人员以及未知责任的分流结果。
                var item = requestedItem(group, input.getItemId());
                long source = routeSource(group, item.getId(), input.getSourceId());
                changes.add(new ChangeVO("source", "数据源", sourceName(item.getSourceId()), sourceName(source)));
                appendAssignment(changes, request, source, "审批负责人");
            }
            case "PLAN" -> {
                // 组合核对基于整单来源和实际责任，已有办理决定时直接拒绝。
                var selected = plannedSources(request, group, input, UserContext.getUser());
                changes.add(new ChangeVO(
                        "scope",
                        "数据源范围",
                        group.stream()
                                .map(item -> sourceName(item.getSourceId()))
                                .distinct()
                                .collect(Collectors.joining("、")),
                        selected.stream().map(this::sourceName).collect(Collectors.joining("、"))));
                changes.add(new ChangeVO(
                        "mode",
                        "办理方式",
                        request.getJoint() ? "联合办理" : "独立办理",
                        Boolean.TRUE.equals(input.getJoint()) ? "联合办理" : "独立办理"));
                selected.forEach(source -> appendAssignment(changes, request, source, sourceName(source) + "审批负责人"));
            }
            case "APPROVE" -> {
                // 规则全文和是否本次整组生效来源于当前数据库。
                var item = requestedItem(group, input.getItemId());
                facts.enabledSource(item.getSourceId());
                facts.resolveRules(input.getRuleIds())
                        .forEach(rule -> changes.add(new ChangeVO("rule", rule.getName(), null, rule.getContent())));
                boolean complete = !request.getJoint()
                        || group.stream()
                                .allMatch(member -> Objects.equals(member.getId(), item.getId())
                                        || "APPROVED".equals(member.getState()));
                if (complete && request.getJoint()) {
                    // 任一已批准来源失效时直接阻止进入最终核对。
                    group.stream()
                            .filter(member -> !Objects.equals(member.getId(), item.getId()))
                            .forEach(member -> {
                                facts.enabledSource(member.getSourceId());
                                facts.currentRules(reads.itemRules(member.getId()));
                            });
                }
                changes.add(new ChangeVO(
                        "state",
                        "确认后状态",
                        null,
                        !complete ? "已批准，待联合生效" : request.getJoint() ? "全部必要子项通过，整组生效" : "本项生效"));
            }
            case "SUPPLEMENT", "REJECT" -> {
                // 无处理说明时继续在编辑步骤反馈。
                requiredReason(input.getReason());
                String result =
                        "SUPPLEMENT".equals(input.getAction()) ? "需补充" : request.getJoint() ? "本项驳回，联合申请结束" : "本项已驳回";
                changes.add(new ChangeVO("state", "确认后状态", null, result));
            }
            case "WITHDRAW" -> {
                // 已生效的来源不纳入撤回，只有实际可撤回的项目进入核对。
                withdrawable(group, input.getItemId(), request.getJoint())
                        .forEach(item ->
                                changes.add(new ChangeVO("source", "撤回范围", null, sourceName(item.getSourceId()))));
                changes.add(new ChangeVO("state", "确认后状态", null, request.getSubmittedAt() == null ? "草稿已删除" : "已撤回"));
            }
            default -> throw BizIllegalException.invalid("不支持的申请操作");
        }
        return reviews.preview(
                input.getAction(), Long.toString(id), request.getVersion(), input, changes, List.of(), List.of());
    }

    /**
     * 已核定来源和责任按申请人回避规则计算。
     */
    private void appendAssignment(List<ChangeVO> changes, AccessRequestPO request, long source, String label) {
        var owner = facts.person(facts.assignee(source, request.getApplicantId()));
        changes.add(new ChangeVO(
                "assignee",
                label,
                null,
                owner == null ? "待分流（当前无可用审批负责人）" : owner.getDisplayName() + "（" + owner.getUsername() + "）"));
    }

    /**
     * 空来源在申请核对中使用可理解的当前状态。
     */
    private String sourceName(Long source) {
        return source == null ? "待核定数据源" : facts.source(source).getName();
    }

    /**
     * 先确认操作归属，再向可操作人披露具体的版本冲突。
     */
    private void verifyAction(
            AccessRequestPO request, List<AccessRequestItemPO> group, long actor, RequestActionDTO input) {
        var action = input.getAction();
        if ("WITHDRAW".equals(action)) {
            // 本人撤回不受正常分派状态影响。
            checkOwner(request, actor);
        } else if ("PLAN".equals(action)) {
            // 无可经办项的人员不能读取或改写组合。
            if (group.stream()
                    .noneMatch(item -> "PENDING".equals(item.getState())
                            && (facts.assignee(item.getSourceId(), request.getApplicantId()) == null
                                    || Objects.equals(
                                            facts.assignee(item.getSourceId(), request.getApplicantId()), actor)))) {
                throw BizIllegalException.missing();
            }
        } else {
            // 审批只可作用于该项，分流只可处理待分流或本人负责的项。
            var item = requestedItem(group, input.getItemId());
            var assignee = facts.assignee(item.getSourceId(), request.getApplicantId());
            if ("ROUTE".equals(action)
                    ? assignee != null && assignee != actor
                    : actor == request.getApplicantId() || !Objects.equals(assignee, actor)) {
                throw BizIllegalException.denied();
            }
            if (!"PENDING".equals(item.getState())) {
                // 历史结论禁止直接改写。
                throw BizIllegalException.state("该子项当前不可办理");
            }
        }
        if (!Objects.equals(request.getVersion(), input.getExpectedVersion())) {
            // 并发结果变化后应按当前单据重新核对。
            throw BizIllegalException.conflict();
        }
    }

    /**
     * 子项必须真实归属正在办理的整份申请。
     */
    private AccessRequestItemPO requestedItem(List<AccessRequestItemPO> group, String itemId) {
        long id = Ids.parse(itemId);
        return group.stream().filter(row -> row.getId() == id).findFirst().orElseThrow(BizIllegalException::missing);
    }

    /**
     * 解析经审批目录明确授权的动作。
     */
    public static String operation(String action) {
        return switch (action) {
                // 不使用前端输入构造任意权限编码。
            case "ROUTE", "PLAN" -> "access-requests.route";
            case "APPROVE" -> "access-requests.review";
            case "SUPPLEMENT" -> "access-requests.supplement";
            case "REJECT" -> "access-requests.reject";
            case "WITHDRAW" -> "my-requests.withdraw";
            default -> throw BizIllegalException.invalid("不支持的申请操作");
        };
    }

    /**
     * 办理结论出现之前可以重新核定独立或联合组合。
     */
    private void plan(AccessRequestPO request, List<AccessRequestItemPO> group, RequestActionDTO input) {
        var selected = plannedSources(request, group, input, UserContext.getUser());
        replaceItems(request.getId(), selected, "PENDING");
        request.setJoint(Boolean.TRUE.equals(input.getJoint()));
    }

    /**
     * 调整整单范围的前置条件供编辑核对和最终提交复用。
     */
    private List<Long> plannedSources(
            AccessRequestPO request, List<AccessRequestItemPO> group, RequestActionDTO input, long actor) {
        if (reads.hasDecisions(request.getId())
                || group.stream().anyMatch(item -> !"PENDING".equals(item.getState()) || item.getHandledBy() != null)) {
            // 已经办理的子项不允许被删除或替换。
            throw BizIllegalException.state("已有办理结论，不能调整申请组合");
        }
        if (group.stream()
                .noneMatch(item -> facts.assignee(item.getSourceId(), request.getApplicantId()) == null
                        || Objects.equals(facts.assignee(item.getSourceId(), request.getApplicantId()), actor))) {
            // 有分流功能但不在当前办理范围的人员无权修改单据。
            throw BizIllegalException.missing();
        }
        var selected = AccessFacts.ids(input.getSourceIds());
        boolean joint = Boolean.TRUE.equals(input.getJoint());
        if (selected.isEmpty() || (joint && selected.size() < 2)) {
            // 联合办理至少有两个不同来源。
            throw BizIllegalException.invalid("请选择数据源，联合办理至少选择两个");
        }
        requiredReason(input.getReason());
        selected.forEach(facts::enabledSource);
        return selected;
    }

    /**
     * 批准、要求补充、驳回由当前具体负责人办理。
     */
    private void handle(AccessRequestPO request, List<AccessRequestItemPO> group, long actor, RequestActionDTO input) {
        var item = requestedItem(group, input.getItemId());
        long itemId = item.getId();
        if (!"PENDING".equals(item.getState())) {
            // 办理完成的子项按历史保留，不能直接修改。
            throw BizIllegalException.state("该子项当前不可办理");
        }
        var assignee = facts.assignee(item.getSourceId(), request.getApplicantId());
        if ("ROUTE".equals(input.getAction())) {
            // 只在待分流或当前本人办理的子项上核定新源。
            if (assignee != null && assignee != actor) {
                throw BizIllegalException.missing();
            }
            long source = routeSource(group, itemId, input.getSourceId());
            resetItem(item, source);
            return;
        }
        if (actor == request.getApplicantId() || !Objects.equals(assignee, actor)) {
            // 防止本人自批和无办理归属的审批。
            throw BizIllegalException.denied();
        }
        reads.rememberHandler(itemId, actor, request.getVersion() + 1);
        item.setHandledBy(actor);
        item.setHandledAt(reads.now());
        item.setReason(input.getReason());
        switch (input.getAction()) {
                // 批准后的实际生效范围在同一事务判断。
            case "APPROVE" -> approve(request, item, group, input);
            case "REJECT" -> {
                item.setReason(requiredReason(input.getReason()));
                item.setState("REJECTED");
                items.updateById(item);
                if (request.getJoint()) {
                    // 一项未通过，联合组合其余尚未生效的范围同时结束。
                    closeUnfinished(group, itemId);
                }
            }
            case "SUPPLEMENT" -> {
                item.setReason(requiredReason(input.getReason()));
                item.setState("NEEDS_SUPPLEMENT");
                items.updateById(item);
            }
            default -> throw BizIllegalException.invalid("不支持的审批操作");
        }
    }

    /**
     * 核定只能选已启用且没有被其他必要子项占用的数据源。
     */
    private long routeSource(List<AccessRequestItemPO> group, long itemId, String sourceId) {
        long source = Ids.parse(sourceId);
        facts.enabledSource(source);
        if (group.stream().anyMatch(other -> other.getId() != itemId && Objects.equals(other.getSourceId(), source))) {
            // 同一申请不会新增重复来源。
            throw BizIllegalException.invalid("申请已包含该数据源");
        }
        return source;
    }

    /**
     * 单项批准立即生效，联合办理只有最后一项完备时生成整组来源。
     */
    private void approve(
            AccessRequestPO request,
            AccessRequestItemPO item,
            List<AccessRequestItemPO> group,
            RequestActionDTO input) {
        facts.enabledSource(item.getSourceId());
        var rules = facts.resolveRules(input.getRuleIds());
        for (int index = 0; index < rules.size(); index++) {
            // 按稳定编号持久保存选择，正文另存当次快照。
            reads.addItemRule(item.getId(), Ids.parse(rules.get(index).getId()), index + 1);
        }
        item.setRuleSnapshot(json.write(rules));
        item.setState("APPROVED");
        items.updateById(item);
        var eligible = request.getJoint() ? group : List.of(item);
        if (eligible.stream().anyMatch(other -> !"APPROVED".equals(other.getState()))) {
            // 已批准的子项继续等待其他审批，当前不产生授权。
            return;
        }
        for (var approved : eligible) {
            // 最后一项批准时重新核查整组当前数据源和规则存在性。
            facts.enabledSource(approved.getSourceId());
            facts.currentRules(reads.itemRules(approved.getId()));
            var grant = new AccessGrantPO();
            grant.setItemId(approved.getId());
            grants.insert(grant);
            approved.setState("EFFECTIVE");
            items.updateById(approved);
        }
    }

    /**
     * 不覆盖已生效和历史办理事实。
     */
    private void withdraw(List<AccessRequestItemPO> group, String target, boolean joint) {
        withdrawable(group, target, joint).forEach(this::withdrawItem);
    }

    /**
     * 提交前的撤回范围也用于展示核对后果。
     */
    private List<AccessRequestItemPO> withdrawable(List<AccessRequestItemPO> group, String target, boolean joint) {
        var itemId = Ids.nullable(target);
        if (joint && group.stream().anyMatch(item -> "EFFECTIVE".equals(item.getState()))) {
            // 生效后只能从授权管理整组撤销。
            throw BizIllegalException.state("已生效的组合须通过授权撤销办理");
        }
        var open = group.stream()
                .filter(item -> joint || itemId == null || Objects.equals(itemId, item.getId()))
                .filter(item -> Set.of("DRAFT", "PENDING", "NEEDS_SUPPLEMENT", "APPROVED")
                        .contains(item.getState()))
                .toList();
        if (open.isEmpty()) {
            // 没有可以由本人撤回的范围。
            throw BizIllegalException.state("没有可撤回的申请子项");
        }
        return open;
    }

    /**
     * 一票驳回结束联合组合中尚未生效的其他项。
     */
    private void closeUnfinished(List<AccessRequestItemPO> group, long excluded) {
        group.stream()
                .filter(row -> row.getId() != excluded
                        && Set.of("PENDING", "NEEDS_SUPPLEMENT", "APPROVED").contains(row.getState()))
                .forEach(this::withdrawItem);
    }

    /**
     * 只变更当前状态，原办理人和审批规则快照保留。
     */
    private void withdrawItem(AccessRequestItemPO item) {
        item.setState("WITHDRAWN");
        items.updateById(item);
    }

    /**
     * 仅在明确未办理时替换尚无授权依赖的子项。
     */
    private void replaceItems(long request, List<Long> sourceIds, String state) {
        items.delete(Wrappers.<AccessRequestItemPO>lambdaQuery().eq(AccessRequestItemPO::getRequestId, request));
        insertItems(request, sourceIds, state);
    }

    /**
     * 未知数据源保留一条可分流子项。
     */
    private void insertItems(long request, List<Long> sourceIds, String state) {
        var selected = sourceIds.isEmpty() ? Collections.<Long>singletonList(null) : sourceIds;
        for (Long source : selected) {
            // 每个已知源为一条独立办理记录。
            var row = new AccessRequestItemPO();
            row.setRequestId(request);
            row.setSourceId(source);
            row.setState(state);
            items.insert(row);
        }
    }

    /**
     * 回到可按当前责任分派的状态，清除原待办理内容。
     */
    private void resetItem(AccessRequestItemPO item, Long source) {
        reads.clearItemRules(item.getId());
        items.update(Wrappers.<AccessRequestItemPO>lambdaUpdate()
                .eq(AccessRequestItemPO::getId, item.getId())
                .set(AccessRequestItemPO::getSourceId, source)
                .set(AccessRequestItemPO::getState, "PENDING")
                .set(AccessRequestItemPO::getRuleSnapshot, null)
                .set(AccessRequestItemPO::getHandledBy, null)
                .set(AccessRequestItemPO::getHandledAt, null)
                .set(AccessRequestItemPO::getReason, null));
    }

    /**
     * 本人可见整单，经办人只读对应的当前或历史子项。
     */
    private List<AccessRequestItemPO> visible(AccessRequestPO row, long actor, String queue) {
        List<AccessRequestItemPO> all = facts.items(row.getId());
        if ((queue == null || "MINE".equals(queue))
                && row.getApplicantId() == actor
                && permissions.has("my-requests.view")) {
            // 已删除草稿不再作为可打开的申请。
            return row.getSubmittedAt() == null && all.stream().noneMatch(item -> "DRAFT".equals(item.getState()))
                    ? List.of()
                    : all;
        }
        boolean canReview = permissions.has("access-requests.review");
        boolean canRoute = permissions.has("access-requests.route") && (queue == null || "ROUTING".equals(queue));
        boolean canPending = canReview && (queue == null || "PENDING".equals(queue));
        boolean canHistory = canReview && (queue == null || "HANDLED".equals(queue));
        var handled = canHistory ? Set.copyOf(reads.handledItems(row.getId(), actor)) : Set.<Long>of();
        var granted = queue == null && permissions.has("grants.view")
                ? Set.copyOf(reads.grantedItems(row.getId()))
                : Set.<Long>of();
        boolean active = (canPending || canRoute) && facts.enabledUser(row.getApplicantId());
        return all.stream()
                .filter(item -> {
                    // 读取经办事实和当前分派资格均保留各自作用。
                    if (granted.contains(item.getId())
                            || canHistory
                                    && (Objects.equals(item.getHandledBy(), actor) || handled.contains(item.getId()))) {
                        return true;
                    }
                    if (!active || !"PENDING".equals(item.getState())) {
                        return false;
                    }
                    var assigned = facts.assignee(item.getSourceId(), row.getApplicantId());
                    return canPending && Objects.equals(assigned, actor) || canRoute && assigned == null;
                })
                .toList();
    }

    /**
     * 已经办队列优先显示本人的实际记录，重提后的待办由新负责人领取。
     */
    private AccessRequestVO queueView(AccessRequestPO row, long actor, String queue, AccessListQuery query) {
        var visible = visible(row, actor, queue);
        if ("MINE".equals(queue)) {
            // 本人的状态筛选选中整单，详情保留全部子项。
            return view(row, visible);
        }
        var history = new HashMap<String, RequestItemVO>();
        if ("HANDLED".equals(queue) && visible.stream().anyMatch(item -> !Objects.equals(item.getHandledBy(), actor))) {
            // 已办子项重提或转交后，从原版本恢复该经办人的历史决定。
            for (String snapshot : reads.handledSnapshots(row.getId(), actor)) {
                var original = json.read(snapshot, AccessRequestVO.class);
                original.getItems().stream()
                        .filter(item -> item.getHandler() != null
                                && Long.toString(actor).equals(item.getHandler().getId()))
                        .forEach(item -> {
                            // 原子项未单独补充时，它办理所依据的是当时的整单资料。
                            if (item.getApplication() == null) {
                                item.setApplication(new RequestApplicationVO(
                                        original.getPurpose(), original.getDataNeeded(), original.getSupplement()));
                            }
                            history.putIfAbsent(item.getId(), item);
                        });
            }
        }
        Long sourceFilter = Ids.nullable(query.getSourceId());
        var details = visible.stream()
                .map(item -> "HANDLED".equals(queue) && !Objects.equals(item.getHandledBy(), actor)
                        ? history.get(item.getId().toString())
                        : itemView(row, item))
                .filter(Objects::nonNull)
                .filter(item -> sourceFilter == null
                        || item.getSource() != null
                                && sourceFilter
                                        .toString()
                                        .equals(item.getSource().getId()))
                .filter(item -> query.getState() == null || query.getState().equals(item.getState()))
                .toList();
        return render(row, details, !"HANDLED".equals(queue));
    }

    /**
     * 组装本人或当前可见的子项详情。
     */
    private AccessRequestVO view(AccessRequestPO row, List<AccessRequestItemPO> visible) {
        return render(row, visible.stream().map(item -> itemView(row, item)).toList(), true);
    }

    /**
     * 分流项展示实际无法办理的原因，独立补充只覆盖这个子项。
     */
    private RequestItemVO itemView(AccessRequestPO row, AccessRequestItemPO item) {
        boolean pending = "PENDING".equals(item.getState());
        Long assignee = pending ? facts.assignee(item.getSourceId(), row.getApplicantId()) : null;
        String state = pending && assignee == null ? "ROUTING" : item.getState();
        String reason = pending && assignee == null
                ? item.getSourceId() == null ? "待核定数据源" : "当前数据源无可用审批负责人"
                : item.getReason();
        var application = item.getApplicationPurpose() == null
                ? null
                : new RequestApplicationVO(
                        item.getApplicationPurpose(), item.getApplicationDataNeeded(), item.getApplicationSupplement());
        return new RequestItemVO(
                item.getId().toString(),
                facts.source(item.getSourceId()),
                state,
                facts.person(assignee),
                facts.person(item.getHandledBy()),
                Times.instant(item.getHandledAt()),
                reason,
                facts.snapshot(item.getRuleSnapshot()),
                application);
    }

    /**
     * 只有有分流权限且确有整单调整资格时才提供完整计划来源。
     */
    private AccessRequestVO render(AccessRequestPO row, List<RequestItemVO> details, boolean includePlan) {
        long actor = UserContext.getUser();
        boolean plan = includePlan
                && row.getSubmittedAt() != null
                && facts.enabledUser(row.getApplicantId())
                && permissions.has("access-requests.route")
                && details.stream()
                        .anyMatch(item -> "ROUTING".equals(item.getState())
                                || "PENDING".equals(item.getState())
                                        && item.getAssignee() != null
                                        && Long.toString(actor)
                                                .equals(item.getAssignee().getId()));
        List<AccessSourceOptionVO> sources = List.of();
        if (plan) {
            // 任何原始子项有办理决定时保持结构，不从当前可见子项反推完整范围。
            var all = facts.items(row.getId());
            plan = !reads.hasDecisions(row.getId()) && all.stream().allMatch(item -> "PENDING".equals(item.getState()));
            if (plan) {
                sources = all.stream()
                        .map(AccessRequestItemPO::getSourceId)
                        .filter(Objects::nonNull)
                        .map(facts::source)
                        .toList();
            }
        }
        return new AccessRequestVO(
                row.getId().toString(),
                row.getVersion(),
                facts.person(row.getApplicantId()),
                reads.requestDepartment(row.getApplicantId()),
                row.getPurpose(),
                row.getDataNeeded(),
                row.getSupplement(),
                row.getJoint(),
                Times.instant(row.getSubmittedAt()),
                details,
                plan,
                sources);
    }

    /**
     * 保存整单版本历史及命令成功审计。
     */
    private MutationVO saveRevision(
            AccessRequestPO row,
            String operation,
            String command,
            String action,
            String reason,
            List<ChangeVO> changes) {
        reads.revision(
                row.getId(),
                row.getVersion(),
                action,
                UserContext.getUser(),
                json.write(view(row, facts.items(row.getId()))));
        var bounded = reason == null || reason.codePointCount(0, reason.length()) <= 500
                ? reason
                : reason.substring(0, reason.offsetByCodePoints(0, 500));
        return facts.record(
                operation,
                "access_request",
                row.getId(),
                "访问申请 " + row.getId(),
                row.getVersion(),
                command,
                bounded,
                changes);
    }

    /**
     * 所有写入内容先清理外部多余边界空白。
     */
    private void applyText(AccessRequestPO row, AccessRequestDTO input) {
        row.setPurpose(input.getPurpose().trim());
        row.setDataNeeded(input.getDataNeeded().trim());
        row.setSupplement(Objects.requireNonNullElse(input.getSupplement(), "").trim());
    }

    /**
     * 推进整单修订，持久状态继续支持重复请求核实。
     */
    private void advance(AccessRequestPO row) {
        row.setVersion(row.getVersion() + 1);
        requests.updateById(row);
    }

    /**
     * 读取稳定申请聚合。
     */
    private AccessRequestPO requireRequest(long id) {
        var row = requests.selectById(id);
        if (row == null) {
            // 统一未知或不可查看的资源反馈。
            throw BizIllegalException.missing();
        }
        return row;
    }

    /**
     * 只有申请本人可以修改草稿或撤回。
     */
    private void checkOwner(AccessRequestPO row, long actor) {
        if (row.getApplicantId() != actor) {
            // 不披露其他人员的申请和版本。
            throw BizIllegalException.missing();
        }
    }

    /**
     * 补充与拒绝必须写实际办理原因。
     */
    private String requiredReason(String reason) {
        if (reason == null || reason.isBlank()) {
            // 办理人须向申请人给出具体原因。
            throw BizIllegalException.invalid("请填写办理说明");
        }
        return reason.trim();
    }

    /**
     * 将日期筛选起点转为业务列表的 UTC 日界线。
     */
    static LocalDateTime start(LocalDate date, String zone) {
        if (date == null) {
            // 日期查询可以仅提供一个端点。
            return null;
        }
        try {
            // 按用户显示的自然日换算到平台 UTC。
            return LocalDateTime.ofInstant(
                    date.atStartOfDay(ZoneId.of(Objects.requireNonNullElse(zone, "UTC")))
                            .toInstant(),
                    ZoneOffset.UTC);
        } catch (DateTimeException invalid) {
            // 直接反馈日期筛选输入无效。
            throw BizIllegalException.invalid("日期或时区不正确");
        }
    }

    /**
     * 日期区间上界取下一天起点。
     */
    static LocalDateTime stop(LocalDate date, String zone) {
        return date == null ? null : start(date.plusDays(1), zone);
    }
}
