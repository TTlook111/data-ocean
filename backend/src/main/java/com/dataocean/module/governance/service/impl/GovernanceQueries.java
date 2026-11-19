package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Entry;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.datasource.service.GovernanceNotes;
import com.dataocean.module.governance.entity.po.GovernanceConfirmationPO;
import com.dataocean.module.governance.entity.po.GovernanceGapPO;
import com.dataocean.module.governance.entity.po.GovernanceImpactPO;
import com.dataocean.module.governance.entity.po.GovernanceNoteChangePO;
import com.dataocean.module.governance.entity.po.GovernanceRevisionPO;
import com.dataocean.module.governance.entity.query.GovernanceColumnQuery;
import com.dataocean.module.governance.entity.query.GovernanceHistoryQuery;
import com.dataocean.module.governance.entity.query.GovernanceTableQuery;
import com.dataocean.module.governance.entity.vo.GovernanceActionsVO;
import com.dataocean.module.governance.entity.vo.GovernanceBasisVO;
import com.dataocean.module.governance.entity.vo.GovernanceColumnRowVO;
import com.dataocean.module.governance.entity.vo.GovernanceConfirmationVO;
import com.dataocean.module.governance.entity.vo.GovernanceFreshnessVO;
import com.dataocean.module.governance.entity.vo.GovernanceGapVO;
import com.dataocean.module.governance.entity.vo.GovernanceHistoryRowVO;
import com.dataocean.module.governance.entity.vo.GovernanceImpactVO;
import com.dataocean.module.governance.entity.vo.GovernanceItemVO;
import com.dataocean.module.governance.entity.vo.GovernanceNoteChangeVO;
import com.dataocean.module.governance.entity.vo.GovernanceObjectVO;
import com.dataocean.module.governance.entity.vo.GovernanceOriginVO;
import com.dataocean.module.governance.entity.vo.GovernanceRevisionVO;
import com.dataocean.module.governance.entity.vo.GovernanceTablePageVO;
import com.dataocean.module.governance.entity.vo.GovernanceTableRowVO;
import com.dataocean.module.governance.entity.vo.GovernanceTargetVO;
import com.dataocean.module.governance.mapper.GovernanceConfirmationMapper;
import com.dataocean.module.governance.mapper.GovernanceControlMapper;
import com.dataocean.module.governance.mapper.GovernanceGapMapper;
import com.dataocean.module.governance.mapper.GovernanceNoteChangeMapper;
import com.dataocean.module.governance.mapper.GovernanceReadMapper;
import com.dataocean.module.governance.mapper.GovernanceRevisionMapper;
import com.dataocean.module.governance.service.GovernanceQueryService;
import com.dataocean.module.governance.service.GovernanceTarget;
import com.dataocean.module.governance.service.impl.GovernanceEntries.Context;
import com.dataocean.module.iam.service.PermissionService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 在一致读视图中组合来源、实际治理结果和当前操作者可见信息。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class GovernanceQueries implements GovernanceQueryService {

    /**
     * B 批来源的权威成功快照。
     */
    private final GovernanceCatalog catalog;

    /**
     * 原件和资料位置的固定读取。
     */
    private final GovernanceNotes notes;

    /**
     * 业务条目及可办理状态。
     */
    private final GovernanceEntries entries;

    /**
     * 查询筛选、状态汇总和固定历史读取。
     */
    private final GovernanceReadMapper reads;

    /**
     * 可披露范围按当前授权获取。
     */
    private final PermissionService permissions;

    /**
     * 固定业务内容。
     */
    private final GovernanceRevisionMapper revisions;

    /**
     * 实际人工确认记录。
     */
    private final GovernanceConfirmationMapper confirmations;

    /**
     * 追加式问题及核清结果。
     */
    private final GovernanceGapMapper gaps;

    /**
     * 资料变化来源记录。
     */
    private final GovernanceNoteChangeMapper noteChanges;

    /**
     * 实际来源与条目的复核关系。
     */
    private final GovernanceImpacts impacts;

    /**
     * 权威的当前生产使用限制。
     */
    private final GovernanceControlMapper controls;

    /**
     * 记录确切修订的资料或结构证据。
     */
    private final GovernanceEvidence evidence;

    /**
     * 用公共编解码读取固定定义。
     */
    private final JsonCodec json;

    /**
     * 治理列表按当前成功目录读取，全部筛选和计数由同一查询计算。
     */
    @Override
    public GovernanceTablePageVO tables(long sourceId, GovernanceTableQuery query) {
        permissions.require("governance.view");
        var source = catalog.source(sourceId, false);
        PageVO<GovernanceTableRowVO> result;
        if (source.snapshotId() == null) {
            // 尚未成功采集的来源返回可解释的空列表。
            result = new PageVO<>(List.of(), 0, 1, query.getPageSize());
        } else {
            // 只接受既定白名单及状态筛选。
            String progress = filter(query.getProgress(), Set.of("UNSTARTED", "IN_PROGRESS", "COMPLETE"), "");
            String pendingKind = filter(query.getPendingKind(), Set.of("TABLE", "COLUMN"), "");
            boolean ascending = query.getSortBy() == null || Boolean.TRUE.equals(query.getIsAsc());
            if (query.getSortBy() != null && !"objectName".equals(query.getSortBy())) {
                throw BizIllegalException.invalid("不支持的排序字段");
            }
            var page = query.<GovernanceReadMapper.Table>toFixedOrderMpPage();
            page.setOptimizeCountSql(false);
            reads.tables(page, sourceId, source.snapshotId(), source.configurationId(), canReadDraft(),
                    keyword(query.getKeyword()), progress, query.getHasPendingImpact(), pendingKind, ascending);
            result = PageVO.from(page, row -> table(source, row));
        }
        return new GovernanceTablePageVO(result, string(source.snapshotId()), source.catalogVersion(),
                new GovernanceFreshnessVO(source.scopePending(), source.staleSince() != null,
                        source.scopePending() ? null : Times.instant(source.successfulAt()),
                        Times.instant(source.staleSince())), source.effectiveScope(), source.snapshotScope());
    }

    /**
     * 对象详情复用同一可用性与精确复核定位。
     */
    @Override
    public GovernanceObjectVO object(long source, long object) {
        permissions.require("governance.view");
        var state = catalog.source(source, false);
        var context = entries.get(state, GovernanceTarget.table(source, object));
        var current = current(context);
        var review = impacts.pending(source, object).stream().map(this::impact).toList();
        GovernanceTargetVO next = review.isEmpty() ? null : review.get(0).target();
        if (next == null && !"CONFIRMED".equals(current.state())) {
            // 首次办理通常从表说明开始。
            next = current.target();
        } else if (next == null && "AVAILABLE".equals(context.availability())) {
            // 表说明已处理时定位第一个当前待治理的字段。
            var query = new GovernanceColumnQuery();
            query.setPageSize(1L);
            query.setStatus("PENDING");
            var page = columnsOf(state, context, query);
            if (!page.getItems().isEmpty()) {
                next = page.getItems().get(0).target();
            }
        }
        var reference = context.entry().observation().structure().object();
        return new GovernanceObjectVO(Long.toString(source), state.name(), Long.toString(object),
                context.entry().generation(), path(reference.schema(), reference.name()), reference.type(),
                reference.comment(), basis(context), context.availability(), context.pending(), current, next, review);
    }

    /**
     * 字段导航从对象的实际结构修订分页。
     */
    @Override
    public PageVO<GovernanceColumnRowVO> columns(long sourceId, long object, GovernanceColumnQuery query) {
        permissions.require("governance.view");
        var source = catalog.source(sourceId, false);
        var context = entries.get(source, GovernanceTarget.table(sourceId, object));
        return columnsOf(source, context, query);
    }

    /**
     * 返回当前内容、真实审核、复核和动作状态。
     */
    @Override
    public GovernanceItemVO item(GovernanceTarget target) {
        permissions.require("governance.view");
        return current(entries.get(catalog.source(target.source(), false), target));
    }

    /**
     * 分页时固定修改、确认和确切内容的关联。
     */
    @Override
    public PageVO<GovernanceHistoryRowVO> history(GovernanceTarget target, GovernanceHistoryQuery query) {
        permissions.require("governance.view");
        if (!canReadDraft()) {
            // 不向只有页面概况权限的成员暴露历史草稿。
            throw BizIllegalException.denied();
        }
        var context = entries.get(catalog.source(target.source(), false), target);
        if (context.item() == null) {
            // 虚拟初始条目还没有人工或模型历史。
            return new PageVO<>(List.of(), 0, 1, query.getPageSize());
        }
        var page = query.<GovernanceReadMapper.History>toFixedOrderMpPage();
        page.setOptimizeCountSql(false);
        reads.history(page, context.item().getId(), query.getRevision());
        return PageVO.from(page, record -> historyRow(record, query.getRevision() != null));
    }

    /**
     * 保留同源待定位入口及资料来源回看。
     */
    @Override
    public PageVO<GovernanceNoteChangeVO> noteChanges(long source, Long note, PageQuery query) {
        permissions.require("governance.view");
        catalog.source(source, false);
        if (note != null) {
            // 读取具体资料的办理历史需要同源和资料读取权。
            permissions.require("business-notes.view");
        }
        var page = query.<GovernanceNoteChangePO>toFixedOrderMpPage(OrderItem.desc("occurred_at"), OrderItem.desc("id"));
        noteChanges.selectPage(page, Wrappers.<GovernanceNoteChangePO>lambdaQuery()
                .eq(GovernanceNoteChangePO::getSourceId, source)
                .eq(note == null, GovernanceNoteChangePO::getMappingState, "NEEDS_MAPPING")
                .eq(note != null, GovernanceNoteChangePO::getNoteId, note));
        return PageVO.from(page, this::note);
    }

    /**
     * 返回经过当前权限过滤的资料来源及办理进度。
     */
    public GovernanceNoteChangeVO note(GovernanceNoteChangePO note) {
        var related = impacts.byNote(note.getId());
        return new GovernanceNoteChangeVO(Long.toString(note.getId()), Long.toString(note.getSourceId()),
                note.getVersion(), Times.instant(note.getOccurredAt()), note.getMappingState(),
                impacts.stage(related, "NEEDS_MAPPING".equals(note.getMappingState())),
                noteOrigin(note), related.stream().map(this::impact).toList());
    }

    /**
     * 当前审批和人工核对人员可阅读其职责所需的治理草稿。
     */
    public boolean canReadDraft() {
        return permissions.has("governance.edit") || permissions.has("governance.confirm")
                || permissions.has("governance.supplement");
    }

    /**
     * 返回精确已确认或可披露的业务修订。
     */
    public GovernanceRevisionVO revision(GovernanceRevisionPO row) {
        if (row == null) {
            // 空白初始定义没有业务修订。
            return null;
        }
        return new GovernanceRevisionVO(Long.toString(row.getId()), row.getRevision(), json.mapper().readTree(row.getContent()),
                row.getOrigin(), entries.basis(row.getSnapshotId(), row.getObjectRevisionId(), row.getColumnRevisionId()),
                evidence.list(row.getId()).stream().map(evidence::view).toList(), Long.toString(row.getChangedBy()),
                Times.instant(row.getCreatedAt()));
    }

    /**
     * 读取条目的生效禁止，包括父表的当前限制。
     */
    public boolean blocked(GovernanceTarget key) {
        return controls.blocked(key.source(), key.object(), key.kind(), key.target());
    }

    /**
     * 构造当前结构依据，兼容确认移除使用最后成功观察。
     */
    public GovernanceBasisVO basis(Context context) {
        return entries.basis(context.entry().observation().snapshotId(), context.entry().observation().revisionId(),
                context.columnRevision());
    }

    /**
     * 各来源影响只在当前拥有相应来源读取权限时披露定位。
     */
    private GovernanceImpactVO impact(GovernanceImpactPO impact) {
        var item = reads.item(impact.getItemId());
        var target = entries.target(item);
        GovernanceOriginVO origin = null;
        if (impact.getStructureChangeId() != null && permissions.has("collections.view")) {
            // 只有确证事实进入业务复核。
            origin = new GovernanceOriginVO(Long.toString(impact.getStructureChangeId()), "CONFIRMED", null,
                    null, null, null);
        } else if (impact.getNoteChangeId() != null) {
            // 原资料状态及确切历史仍按资料权限披露。
            origin = noteOrigin(noteChanges.selectById(impact.getNoteChangeId()));
        }
        String name = "表说明";
        if (target.isColumn()) {
            // 即使字段移除，也使用最后成功观察中的合法名称定位。
            Entry former = catalog.historical(target.source(), target.object(), target.target());
            name = former == null ? "字段" : former.column(target.target()).name();
        }
        return new GovernanceImpactVO(Long.toString(impact.getId()), impact.getImpactKind(), impact.getState(),
                target.view(), name, Times.instant(impact.getStartedAt()), Times.instant(impact.getResolvedAt()),
                string(impact.getConfirmationId()), impact.getKnowledgeRequired(), origin);
    }

    /**
     * 将资料修订与权限快照转换成公开来源引用。
     */
    private GovernanceOriginVO noteOrigin(GovernanceNoteChangePO note) {
        if (note == null || !permissions.has("business-notes.view")) {
            // 不披露资料编号、有效性及其任何原文元数据。
            return null;
        }
        int before = notes.revision(note.getSourceId(), note.getNoteId(), note.getBeforeRevisionId()).revision();
        Integer after = note.getAfterRevisionId() == null ? null
                : notes.revision(note.getSourceId(), note.getNoteId(), note.getAfterRevisionId()).revision();
        return new GovernanceOriginVO(null, null, Long.toString(note.getId()), Long.toString(note.getNoteId()),
                before, after);
    }

    /**
     * 从按源授权的同一个页面投影转换每条表摘要。
     */
    private GovernanceTableRowVO table(Source source, GovernanceReadMapper.Table row) {
        var pending = new ArrayList<String>();
        if (row.totalCount() > 0 && !row.tableConfirmed()) {
            pending.add("TABLE");
        }
        if (row.totalCount() - row.confirmedCount() > (row.tableConfirmed() ? 0 : 1)) {
            pending.add("COLUMN");
        }
        var related = row.hasPendingImpact()
                ? impacts.pending(source.id(), row.objectId()).stream().map(this::impact).toList() : List.<GovernanceImpactVO>of();
        return new GovernanceTableRowVO(Long.toString(source.id()), string(row.objectId()),
                Long.toString(row.observationId()), row.generation(), path(row.namespace(), row.name()), row.objectType(),
                row.sourceComment(), row.businessName(), row.purpose(),
                entries.availability(source, false, row.sourcePending() || row.objectId() == null, true),
                row.progress(), row.confirmedCount(), row.totalCount(), pending, related);
    }

    /**
     * 将字段分页与固定结构修订一起发送给编辑区。
     */
    private PageVO<GovernanceColumnRowVO> columnsOf(Source source, Context context, GovernanceColumnQuery query) {
        String state = filter(query.getStatus(), Set.of("ALL", "PENDING", "CONFIRMED"), "ALL");
        var page = query.<GovernanceReadMapper.Column>toFixedOrderMpPage();
        page.setOptimizeCountSql(false);
        long revision = context.entry().observation().revisionId();
        reads.columns(page, source.id(), context.target().object(), revision, keyword(query.getKeyword()), state);
        return PageVO.from(page, column -> new GovernanceColumnRowVO(
                GovernanceTarget.column(source.id(), context.target().object(), column.columnId()).view(),
                column.name(), column.nativeType(), column.nullable(), column.sourceComment(),
                entries.basis(context.entry().observation().snapshotId(), revision, column.revisionId()),
                column.state(), column.pendingReview(), column.blocked()));
    }

    /**
     * 当前定义、旧确认和待复核分别投影，不把来源限制当成审核状态。
     */
    private GovernanceItemVO current(Context context) {
        var item = context.item();
        boolean readDraft = canReadDraft();
        var action = actions(context);
        boolean blocked = blocked(context.target());
        String name = context.field() == null ? "表说明" : context.field().name();
        String type = context.field() == null ? null : context.field().nativeType();
        String comment = context.field() == null ? context.entry().observation().structure().object().comment()
                : context.field().sourceComment();
        if (item == null) {
            // 读取虚拟条目时保留业务空白。
            return new GovernanceItemVO(context.target().view(), null, null, basis(context), "UNSTARTED", false,
                    null, null, List.of(), List.of(), blocked, context.availability(), name, type, comment, action);
        }
        var affected = impacts.forItem(item.getId());
        var issues = gaps.selectList(Wrappers.<GovernanceGapPO>lambdaQuery()
                .eq(GovernanceGapPO::getItemId, item.getId()).orderByAsc(GovernanceGapPO::getId));
        boolean pending = affected.stream().anyMatch(impact -> !"REVIEWED".equals(impact.getState()));
        boolean open = issues.stream().anyMatch(gap -> "OPEN".equals(gap.getState()));
        String state = item.getHeadRevisionId() == null ? "UNSTARTED"
                : !pending && !open && Objects.equals(item.getHeadRevisionId(), item.getLastConfirmedRevisionId())
                      ? "CONFIRMED" : "DRAFT";
        Long readable = readDraft ? item.getHeadRevisionId() : item.getLastConfirmedRevisionId();
        var definitions = readable == null ? null : revision(revisions.selectById(readable));
        var latest = confirmations.selectList(Wrappers.<GovernanceConfirmationPO>lambdaQuery()
                        .eq(GovernanceConfirmationPO::getItemId, item.getId())
                        .orderByDesc(GovernanceConfirmationPO::getId).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        return new GovernanceItemVO(context.target().view(), Long.toString(item.getId()), item.getVersion(),
                basis(context), state, pending, definitions, confirmed(latest),
                readDraft ? issues.stream().map(this::gap).toList() : List.of(),
                affected.stream().map(this::impact).toList(), blocked, context.availability(), name, type, comment,
                action);
    }

    /**
     * 权限与当前 B 批状态决定可用动作。
     */
    private GovernanceActionsVO actions(Context context) {
        boolean available = "AVAILABLE".equals(context.availability());
        boolean removal = "REMOVED".equals(context.availability()) && context.item() != null
                && impacts.open(context.item().getId());
        return new GovernanceActionsVO(available && permissions.has("governance.edit"),
                available && !context.target().isColumn() && permissions.has("governance.suggest"),
                available && permissions.has("governance.confirm"),
                available && permissions.has("governance.supplement"),
                available && permissions.has("governance.forbid"),
                removal && permissions.has("governance.confirm"));
    }

    /**
     * 返回已核清和未核清的问题记录。
     */
    private GovernanceGapVO gap(GovernanceGapPO gap) {
        return new GovernanceGapVO(Long.toString(gap.getId()), gap.getContentPath(), gap.getQuestion(), gap.getState(),
                gap.getResolution(), string(gap.getResolvedBy()), Times.instant(gap.getResolvedAt()));
    }

    /**
     * 读取实际确认人和发生时间。
     */
    private GovernanceConfirmationVO confirmed(GovernanceConfirmationPO row) {
        return row == null ? null : new GovernanceConfirmationVO(Long.toString(row.getId()), row.getAction(),
                string(row.getItemRevisionId()), Long.toString(row.getConfirmedBy()), Times.instant(row.getConfirmedAt()),
                entries.basis(row.getSnapshotId(), row.getObjectRevisionId(), row.getColumnRevisionId()));
    }

    /**
     * 同时显示实际固定结构及内容变动项。
     */
    private GovernanceHistoryRowVO historyRow(GovernanceReadMapper.History row, boolean details) {
        var body = row.revisionId() == null ? null : revisions.selectById(row.revisionId());
        var changes = new ArrayList<String>();
        if (body != null && "REVISION".equals(row.kind())) {
            // 差异只根据固定的前后业务修订计算。
            var current = json.mapper().readTree(body.getContent());
            var previous = row.previousRevisionId() == null ? json.mapper().createObjectNode()
                    : json.mapper().readTree(revisions.selectById(row.previousRevisionId()).getContent());
            var keys = new java.util.TreeSet<>(current.propertyNames());
            keys.addAll(previous.propertyNames());
            keys.stream().filter(key -> !current.path(key).equals(previous.path(key))).forEach(changes::add);
        }
        return new GovernanceHistoryRowVO(row.kind(), Long.toString(row.id()), Times.instant(row.occurredAt()),
                Long.toString(row.actor()), string(row.revisionId()), row.revision(), string(row.previousRevisionId()),
                row.action(), entries.basis(row.snapshotId(), row.objectRevisionId(), row.columnRevisionId()),
                details ? revision(body) : null, changes);
    }

    /**
     * 频繁使用的合法空参数规范化。
     */
    private String keyword(String input) {
        return Objects.toString(input, "").strip();
    }

    /**
     * 明确未支持的下拉值，保持查询结果可复验。
     */
    private String filter(String input, Set<String> values, String fallback) {
        if (input == null || input.isBlank()) {
            // 空条件应用文档定义的缺省值。
            return fallback;
        }
        if (!values.contains(input)) {
            // 已有页面应及时更新可选择状态。
            throw BizIllegalException.invalid("筛选状态不正确");
        }
        return input;
    }

    /**
     * 用 B 批源对象标识组装限定名称。
     */
    private String path(String namespace, String name) {
        return namespace == null || namespace.isBlank() ? name : namespace + "." + name;
    }

    /**
     * 对外 ID 恒为字符串，缺失值保持空。
     */
    private String string(Long value) {
        return Objects.toString(value, null);
    }
}
