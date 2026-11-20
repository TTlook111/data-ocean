package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.common.ErrorVO.FieldErrorVO;
import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.FieldValidationException;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.governance.entity.dto.GovernanceConfirmDTO;
import com.dataocean.module.governance.entity.dto.GovernanceControlDTO;
import com.dataocean.module.governance.entity.dto.GovernanceDraftDTO;
import com.dataocean.module.governance.entity.dto.GovernanceDraftInputDTO;
import com.dataocean.module.governance.entity.dto.GovernanceGapDTO;
import com.dataocean.module.governance.entity.dto.GovernanceImpactTargetsDTO;
import com.dataocean.module.governance.entity.po.GovernanceConfirmationPO;
import com.dataocean.module.governance.entity.po.GovernanceGapPO;
import com.dataocean.module.governance.entity.po.GovernanceImpactPO;
import com.dataocean.module.governance.entity.po.GovernanceItemPO;
import com.dataocean.module.governance.entity.po.GovernanceNoteChangePO;
import com.dataocean.module.governance.entity.po.GovernanceRevisionPO;
import com.dataocean.module.governance.entity.vo.GovernanceMutationVO;
import com.dataocean.module.governance.entity.vo.GovernanceNoteChangeVO;
import com.dataocean.module.governance.mapper.GovernanceConfirmationMapper;
import com.dataocean.module.governance.mapper.GovernanceGapMapper;
import com.dataocean.module.governance.mapper.GovernanceImpactMapper;
import com.dataocean.module.governance.mapper.GovernanceNoteChangeMapper;
import com.dataocean.module.governance.mapper.GovernanceRevisionMapper;
import com.dataocean.module.governance.service.GovernanceCommandService;
import com.dataocean.module.governance.service.GovernanceTarget;
import com.dataocean.module.governance.service.impl.GovernanceEntries.Context;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.ImpactCountVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ReviewService;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.JsonNode;

/**
 * 同源锁下提交业务定义、复核、审计和必要事件。
 */
@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class GovernanceCommands implements GovernanceCommandService {

    /**
     * 公共命令防重和锁后资格检查。
     */
    private final MutationService commands;

    /**
     * 再次核对动作和原始依据权限。
     */
    private final PermissionService permissions;

    /**
     * B 批成功目录和同一来源锁。
     */
    private final GovernanceCatalog catalog;

    /**
     * 治理身份、当前版本和采集依赖。
     */
    private final GovernanceEntries entries;

    /**
     * 实际业务内容、不变修订及确认规则。
     */
    private final GovernanceContent content;

    /**
     * 治理实际依据和资料引用登记。
     */
    private final GovernanceEvidence evidence;

    /**
     * 对人工选择采用的原始建议复核版本与权限。
     */
    private final GovernanceGenerationTasks generation;

    /**
     * 来源影响与权威使用限制。
     */
    private final GovernanceImpacts impacts;

    /**
     * 具体复核关联的持久化记录。
     */
    private final GovernanceImpactMapper impactRows;

    /**
     * 可复查的内容修订和确认记录。
     */
    private final GovernanceRevisionMapper revisions;

    /**
     * 命令实际产生的审核记录。
     */
    private final GovernanceConfirmationMapper confirmations;

    /**
     * 补充问题及人工核清记录。
     */
    private final GovernanceGapMapper gaps;

    /**
     * 资料来源及待定位状态。
     */
    private final GovernanceNoteChangeMapper noteChanges;

    /**
     * 只对已核权的内容组织安全响应。
     */
    private final GovernanceQueries queries;

    /**
     * 共用危险动作核对凭据。
     */
    private final ReviewService reviews;

    /**
     * 仅记录治理业务元数据的审计服务。
     */
    private final AuditService audit;

    /**
     * 与确认和禁止事实同事务的可靠通知。
     */
    private final GovernanceEvents events;

    /**
     * 读写结构化治理正文。
     */
    private final JsonCodec json;

    /**
     * 保存可部分填写的草稿，重复相同内容保留原修订。
     */
    @Override
    public GovernanceMutationVO draft(GovernanceTarget target, String command, GovernanceDraftDTO input) {
        MutationVO result = commands.execute("governance.edit", command, target.commandTarget(), input,
                expected(input.getPrecondition().getExpectedVersion()), false, false, () -> {
                    var source = catalog.source(target.source(), true);
                    var current = entries.get(source, target);
                    entries.verify(current, input.getPrecondition(), false);
                    var saved = save(source, current, input.getDraft());
                    if (saved.changed()) {
                        // 实际业务更新才改变来源办理进度和审计。
                        var item = saved.item();
                        entries.update(item, saved.revision().getId(), item.getLastConfirmedRevisionId());
                        impacts.started(source.id(), item.getId());
                        record("governance.edit", "governance_item", item.getId(), label(current), command,
                                "保存治理草稿");
                    }
                    return result(command, saved.item());
                });
        return outcome(target, result, false);
    }

    /**
     * 仅对明确业务内容项建立补充问题。
     */
    @Override
    public GovernanceMutationVO supplement(GovernanceTarget target, String command, GovernanceGapDTO input) {
        MutationVO result = commands.execute("governance.supplement", command, target.commandTarget(), input,
                expected(input.getPrecondition().getExpectedVersion()), false, false, () -> {
                    var source = catalog.source(target.source(), true);
                    var current = entries.get(source, target);
                    entries.verify(current, input.getPrecondition(), false);
                    content.requirePath(target.isColumn(), input.getContentPath());
                    var item = entries.ensure(target, current.entry().generation());
                    String question = input.getQuestion().strip();
                    if (gaps.exists(Wrappers.<GovernanceGapPO>lambdaQuery()
                            .eq(GovernanceGapPO::getItemId, item.getId())
                            .eq(GovernanceGapPO::getContentPath, input.getContentPath())
                            .eq(GovernanceGapPO::getQuestion, question)
                            .eq(GovernanceGapPO::getState, "OPEN"))) {
                        // 重复补充只返回实际现行状态。
                        return result(command, item);
                    }
                    var gap = new GovernanceGapPO();
                    gap.setItemId(item.getId());
                    gap.setFoundRevisionId(item.getHeadRevisionId());
                    gap.setContentPath(input.getContentPath());
                    gap.setQuestion(question);
                    gap.setOrigin("MANUAL_SUPPLEMENT");
                    gap.setState("OPEN");
                    gap.setOpenedBy(UserContext.getUser());
                    gaps.insert(gap);
                    entries.update(item, item.getHeadRevisionId(), item.getLastConfirmedRevisionId());
                    impacts.started(source.id(), item.getId());
                    record("governance.supplement", "governance_item", item.getId(), label(current), command,
                            "记录治理缺口");
                    return result(command, item);
                });
        return outcome(target, result, false);
    }

    /**
     * 锁后确认真实业务定义或删除证据，事务中的草稿不会部分提交。
     */
    @Override
    public GovernanceMutationVO confirm(GovernanceTarget target, String command, GovernanceConfirmDTO input) {
        if (!List.of("CONFIRM", "ACK_REMOVAL").contains(input.getAction())) {
            // 只接受此模块公开的两类审核动作。
            throw BizIllegalException.invalid("不支持的治理确认动作");
        }
        MutationVO result = commands.execute("governance.confirm", command, target.commandTarget(), input,
                expected(input.getPrecondition().getExpectedVersion()), false, false, () -> {
                    var source = catalog.source(target.source(), true);
                    var current = entries.get(source, target);
                    boolean removal = "ACK_REMOVAL".equals(input.getAction());
                    entries.verify(current, input.getPrecondition(), removal);
                    return confirmUnderLock(source, current, input, removal, command);
                });
        return outcome(target, result, true);
    }

    /**
     * 只将尚未清楚定位的资料影响关联到实际同源目标。
     */
    @Override
    public GovernanceNoteChangeVO associate(long sourceId, long change, String command, GovernanceImpactTargetsDTO input) {
        permissions.require("business-notes.view");
        commands.execute("governance.edit", command, "governance-note:" + sourceId + "/" + change, input,
                input.getExpectedVersion(), false, false, () -> {
                    var source = catalog.source(sourceId, true);
                    var note = requireNoteChange(sourceId, change);
                    permissions.require("business-notes.view");
                    if (!Objects.equals(note.getVersion(), input.getExpectedVersion())) {
                        // 避免把另一位操作者已完成的关联覆盖掉。
                        throw BizIllegalException.conflict();
                    }
                    if (!"NEEDS_MAPPING".equals(note.getMappingState())) {
                        throw BizIllegalException.state("该资料已经完成定位");
                    }
                    var targets = input.getTargets().stream()
                            .map(target -> GovernanceTarget.parse(sourceId, Ids.parse(target.getObjectId()),
                                    target.getKind(), target.getTargetId())).toList();
                    if (targets.stream().distinct().count() != targets.size()) {
                        throw BizIllegalException.invalid("请选择不重复的影响条目");
                    }
                    for (var target : targets) {
                        // 任一不属于可治理成功目录的对象都回滚整批关联。
                        var current = entries.get(source, target);
                        if (!"AVAILABLE".equals(current.availability())) {
                            throw BizIllegalException.state("所选来源条目目前不可治理");
                        }
                        impacts.associate(note, entries.ensure(target, current.entry().generation()));
                    }
                    note.setMappingState("MAPPED");
                    note.setVersion(note.getVersion() + 1);
                    noteChanges.updateById(note);
                    record("governance.edit", "governance_note_change", change, "资料变化", command,
                            "关联治理影响条目");
                    return new MutationVO(command, "governance_note_change", Long.toString(change), note.getVersion());
                });
        return queries.note(requireNoteChange(sourceId, change));
    }

    /**
     * 正式提交前给用户核对本对象及继承列的影响。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ImpactPreviewVO preview(GovernanceTarget target, GovernanceControlDTO input) {
        permissions.require("governance.forbid");
        var current = entries.get(catalog.source(target.source(), false), target);
        verifyForbid(current, input);
        return reviews.preview("FORBID", target.commandTarget(), input.getExpectedVersion(), proposed(current, input),
                List.of(new ChangeVO("usage", "生产使用", queries.blocked(target) ? "禁止" : "允许", "禁止")),
                List.of(new ImpactCountVO(target.isColumn() ? "字段" : "表及字段",
                        target.isColumn() ? 1 : current.entry().columns().size() + 1)),
                List.of("受影响对象将立即停止参与生产使用"));
    }

    /**
     * 在锁内重新核对预览关联的结构和真实影响。
     */
    @Override
    public GovernanceMutationVO forbid(GovernanceTarget target, String command, GovernanceControlDTO input) {
        MutationVO result = commands.execute("governance.forbid", command, target.commandTarget(), input,
                expected(input.getExpectedVersion()), false, false, () -> {
                    var current = entries.get(catalog.source(target.source(), true), target);
                    verifyForbid(current, input);
                    reviews.verify("FORBID", target.commandTarget(), input.getExpectedVersion(),
                            proposed(current, input), input.getReviewToken());
                    var item = current.item();
                    if (queries.blocked(target)) {
                        // 已存在当前权威使用限制。
                        return result(command, item);
                    }
                    item = entries.ensure(target, current.entry().generation());
                    impacts.forbid(item, null, "MANUAL", input.getProposal().getReason().strip(),
                            UUID.fromString(command).toString(), UserContext.getUser());
                    entries.update(item, item.getHeadRevisionId(), item.getLastConfirmedRevisionId());
                    record("governance.forbid", "governance_item", item.getId(), label(current), command, "禁止使用");
                    return result(command, item);
                });
        return outcome(target, result, false);
    }

    /**
     * 审核与确认内容以及需要结转的每条影响具有共同事务边界。
     */
    private MutationVO confirmUnderLock(
            Source source, Context current, GovernanceConfirmDTO input, boolean removal, String command) {
        var item = current.item();
        var revision = head(item);
        boolean changed = false;
        if (input.getDraft() != null) {
            // 仅审核者只能确认已经保存的内容。
            permissions.require("governance.edit");
            if (removal) {
                throw BizIllegalException.invalid("移除确认只能处理删除事实");
            }
            var saved = save(source, current, input.getDraft());
            item = saved.item();
            revision = saved.revision();
            changed = saved.changed();
        }
        if (removal) {
            // 没有开放的移除事项时不补造一条人工审核。
            if (item == null || !impacts.open(item.getId())) {
                return result(command, item);
            }
        } else {
            // 源不可用、实际补充问题或已失效证据均不能确认。
            var definition = revision == null ? json.mapper().createObjectNode()
                    : json.mapper().readTree(revision.getContent());
            content.confirm(current.target().isColumn(), current.field() == null ? "" : current.field().nativeType(),
                    definition, current.field() == null ? null : current.field().sourceComment());
            verifyGaps(item.getId());
            evidence.confirm(source, evidence.list(revision.getId()));
            if (!changed && Objects.equals(item.getLastConfirmedRevisionId(), revision.getId())
                    && !impacts.open(item.getId())) {
                // 完全相同的再次确认保留旧记录。
                return result(command, item);
            }
        }

        var confirmation = new GovernanceConfirmationPO();
        confirmation.setItemId(item.getId());
        confirmation.setItemRevisionId(revision == null ? null : revision.getId());
        confirmation.setAction(input.getAction());
        confirmation.setSnapshotId(current.entry().observation().snapshotId());
        confirmation.setObjectRevisionId(current.entry().observation().revisionId());
        confirmation.setColumnRevisionId(current.columnRevision());
        confirmation.setCommandId(UUID.fromString(command).toString());
        confirmation.setConfirmedBy(UserContext.getUser());
        confirmations.insert(confirmation);
        Long headId = revision == null ? null : revision.getId();
        entries.update(item, headId, removal ? item.getLastConfirmedRevisionId() : headId);
        var affected = impacts.resolve(item, confirmation.getId(), removal, queries.blocked(current.target()));
        events.confirmed(source.id(), item.getId(), item.getVersion(), confirmation.getId(), headId, affected,
                confirmation.getCommandId());
        record("governance.confirm", "governance_item", item.getId(), label(current), command,
                removal ? "确认移除处理" : "人工确认治理");
        return result(command, item);
    }

    /**
     * 保存真正变化后的业务修订、证据和核对结论。
     */
    private Saved save(Source source, Context context, GovernanceDraftInputDTO draft) {
        var item = context.item();
        var prior = head(item);
        var after = content.normalize(context.target().isColumn(), draft.getContent());
        var oldEvidence = evidence.list(prior == null ? null : prior.getId());
        var newEvidence = evidence.prepare(source, context, draft, oldEvidence);
        Long suggestionId = null;
        if (draft.getSuggestionSelection() != null) {
            // 只有实际沿用的模型字段才与原始建议的证据相连。
            var selection = draft.getSuggestionSelection();
            var suggestion = generation.adopt(Ids.parse(selection.getSuggestionId()), context);
            var proposed = json.mapper().readTree(suggestion.getContent());
            if (selection.getContentPaths().stream().distinct().count() != selection.getContentPaths().size()
                    || selection.getContentPaths().stream().anyMatch(path -> !proposed.has(path))) {
                throw BizIllegalException.invalid("请选择当次原始建议的实际内容");
            }
            var selected = selection.getContentPaths().stream()
                    .filter(path -> proposed.path(path).equals(after.path(path))).toList();
            if (!selected.isEmpty()) {
                // 人工实改保留其定义，明确选择替换的字段使用模型实际引用。
                suggestionId = suggestion.getId();
                newEvidence.removeIf(ref -> selected.contains(ref.getContentPath())
                        && "NOTE".equals(ref.getKind()));
                generation.suggestionEvidence(suggestionId).stream()
                        .filter(ref -> selected.contains(ref.getContentPath())).forEach(newEvidence::add);
            }
        }
        boolean changed = !after.equals(prior == null ? json.mapper().createObjectNode()
                : json.mapper().readTree(prior.getContent()))
                || !evidence.keys(oldEvidence).equals(evidence.keys(newEvidence));
        var revision = prior;
        if (changed) {
            // 只创建实质变化的内容修订，不原地改旧确认。
            item = entries.ensure(context.target(), context.entry().generation());
            revision = new GovernanceRevisionPO();
            revision.setItemId(item.getId());
            revision.setRevision(prior == null ? 1 : prior.getRevision() + 1);
            revision.setPreviousRevisionId(prior == null ? null : prior.getId());
            revision.setContent(json.write(after));
            revision.setOrigin(suggestionId == null ? "MANUAL" : "AI_ASSISTED");
            revision.setSuggestionId(suggestionId);
            revision.setSnapshotId(context.entry().observation().snapshotId());
            revision.setObjectRevisionId(context.entry().observation().revisionId());
            revision.setColumnRevisionId(context.columnRevision());
            revision.setChangedBy(UserContext.getUser());
            revisions.insert(revision);
            evidence.record(source.id(), revision.getId(), newEvidence);
        }
        if (draft.getGapResolutions() != null && !draft.getGapResolutions().isEmpty()) {
            // 已回答的实际业务内容与问题结论必须一起提交。
            resolveGaps(item, revision, draft, after);
            changed = true;
        }
        return new Saved(item, revision, changed);
    }

    /**
     * 将手工结论定位到当前仍开放的实际问题。
     */
    private void resolveGaps(GovernanceItemPO item, GovernanceRevisionPO revision, GovernanceDraftInputDTO draft,
            JsonNode after) {
        if (revision == null || item == null) {
            throw BizIllegalException.invalid("请先填写对应的业务说明");
        }
        for (var input : draft.getGapResolutions()) {
            long gapId = Ids.parse(input.getGapId());
            var gap = gaps.selectById(gapId);
            if (gap == null || !gap.getItemId().equals(item.getId()) || !"OPEN".equals(gap.getState())
                    || !after.hasNonNull(gap.getContentPath())) {
                throw BizIllegalException.state("请填写实际业务内容并核对待补充项");
            }
            gaps.update(Wrappers.<GovernanceGapPO>lambdaUpdate()
                    .eq(GovernanceGapPO::getId, gapId)
                    .set(GovernanceGapPO::getState, "RESOLVED")
                    .set(GovernanceGapPO::getResolution, input.getResolution().strip())
                    .set(GovernanceGapPO::getResolvedRevisionId, revision.getId())
                    .set(GovernanceGapPO::getResolvedBy, UserContext.getUser())
                    .setSql("resolved_at=UTC_TIMESTAMP(3)"));
        }
    }

    /**
     * 任何未核清的真实关键问题按业务字段定位。
     */
    private void verifyGaps(long item) {
        var open = gaps.selectList(Wrappers.<GovernanceGapPO>lambdaQuery()
                .eq(GovernanceGapPO::getItemId, item).eq(GovernanceGapPO::getState, "OPEN"));
        if (!open.isEmpty()) {
            // 回传问题对应位置，不回显业务证据的敏感原文。
            throw new FieldValidationException(open.stream()
                    .map(gap -> new FieldErrorVO("draft.content." + gap.getContentPath(),
                            "VALIDATION_FAILED", "请先核清该内容的待补充问题")).toList());
        }
    }

    /**
     * 审计只记录稳定身份和动作事实。
     */
    private void record(String action, String type, long id, String label, String command, String result) {
        audit.record(AuditWriteDTO.builder()
                .actionCode(action).targetType(type).targetId(Long.toString(id)).targetName(label)
                .changes(List.of(new ChangeVO("action", "治理操作", null, result)))
                .securityVersion(commands.auditSecurityVersion(false))
                .commandId(UUID.fromString(command).toString()).build());
    }

    /**
     * 命令写入后读取本次确认和已解释的并发版本。
     */
    private GovernanceMutationVO outcome(GovernanceTarget target, MutationVO result, boolean review) {
        var current = queries.item(target);
        // 同一命令编号可用于不同动作的防重范围，只有确认返回确认记录。
        var confirmation = review && current.getItemId() != null
                ? confirmations.selectOne(Wrappers.<GovernanceConfirmationPO>lambdaQuery()
                        .eq(GovernanceConfirmationPO::getConfirmedBy, UserContext.getUser())
                        .eq(GovernanceConfirmationPO::getCommandId, result.getCommandId())
                        .eq(GovernanceConfirmationPO::getItemId, current.getItemId()))
                : null;
        var affected = confirmation == null ? List.<String>of()
                : impactRows.selectList(Wrappers.<GovernanceImpactPO>lambdaQuery()
                                .eq(GovernanceImpactPO::getConfirmationId, confirmation.getId()))
                        .stream().map(impact -> Long.toString(impact.getId())).toList();
        return new GovernanceMutationVO(result.getCommandId(), target.view(), current.getItemId(),
                result.getVersion() == 0 ? null : result.getVersion(), current.getCurrentRevision(),
                confirmation == null ? null : Long.toString(confirmation.getId()), affected, current.isBlocked());
    }

    /**
     * 共用命令防重登记只使用可回读的稳定引用。
     */
    private MutationVO result(String command, GovernanceItemPO item) {
        return new MutationVO(command, "governance_item", item == null ? "0" : Long.toString(item.getId()),
                item == null ? 0 : item.getVersion());
    }

    /**
     * 虚拟条目的版本在公共命令内部比较按零值表达。
     */
    private int expected(Integer version) {
        return version == null ? 0 : version;
    }

    /**
     * 读取条目的当前修订。
     */
    private GovernanceRevisionPO head(GovernanceItemPO item) {
        return item == null || item.getHeadRevisionId() == null ? null : revisions.selectById(item.getHeadRevisionId());
    }

    /**
     * 检查待定位事项属于当前选择的源。
     */
    private GovernanceNoteChangePO requireNoteChange(long source, long change) {
        var note = noteChanges.selectById(change);
        if (note == null || note.getSourceId() != source) {
            throw BizIllegalException.missing();
        }
        return note;
    }

    /**
     * 用确切当前结构和受影响范围生成危险动作的核对提案。
     */
    private List<Object> proposed(Context current, GovernanceControlDTO input) {
        return List.of(input.getProposal(), current.structureRevision(),
                current.entry().columns().stream().map(column -> column.columnId()).toList());
    }

    /**
     * 保持预览与正式命令核对相同的目标和版本。
     */
    private void verifyForbid(Context current, GovernanceControlDTO input) {
        if (!"FORBID".equals(input.getAction()) || !"AVAILABLE".equals(current.availability())) {
            throw BizIllegalException.state("当前条目不能执行该使用控制");
        }
        if (!Objects.equals(input.getExpectedVersion(), current.item() == null ? null : current.item().getVersion())) {
            throw BizIllegalException.conflict();
        }
    }

    /**
     * 审计目标只展示来源结构的真实名称。
     */
    private String label(Context current) {
        return current.field() == null ? current.entry().observation().structure().object().name()
                : current.field().name();
    }

    /**
     * 保存后必须同时处理的条目、内容和实际修改状态。
     *
     * @param item 已建立的稳定条目
     * @param revision 本次保存后可确认的内容修订
     * @param changed 是否真正修改定义或核对记录
     */
    private record Saved(GovernanceItemPO item, GovernanceRevisionPO revision, boolean changed) {}
}
