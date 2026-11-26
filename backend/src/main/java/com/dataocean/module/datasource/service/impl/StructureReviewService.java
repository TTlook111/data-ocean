package com.dataocean.module.datasource.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.CommonException;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.datasource.entity.dto.ChangeReviewDTO;
import com.dataocean.module.datasource.entity.dto.ChangeReviewPreviewDTO;
import com.dataocean.module.datasource.entity.dto.ChangeReviewProposalDTO;
import com.dataocean.module.datasource.entity.po.DatasourcePO;
import com.dataocean.module.datasource.entity.po.StructureChangePO;
import com.dataocean.module.datasource.entity.query.StructureChangeQuery;
import com.dataocean.module.datasource.entity.vo.StructureChangeVO;
import com.dataocean.module.datasource.entity.vo.StructureChangeVO.Review;
import com.dataocean.module.datasource.mapper.CatalogMapper;
import com.dataocean.module.datasource.mapper.CatalogObjectMapper;
import com.dataocean.module.datasource.mapper.DatasourcePageMapper;
import com.dataocean.module.datasource.mapper.DatasourcePageMapper.ChangeProjection;
import com.dataocean.module.datasource.mapper.DatasourceRecordMapper;
import com.dataocean.module.datasource.mapper.StructureChangeRecordMapper;
import com.dataocean.module.datasource.service.DatasourceEvents;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ReviewService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;

/**
 * 操作只回答来源事实能否确认，并留存真实技术依据。
 */
@Service
@RequiredArgsConstructor
public class StructureReviewService {

    /**
     * 数据源来源锁与审计。
     */
    private final SourceManagement sources;

    /**
     * 真实目录版本。
     */
    private final DatasourceRecordMapper records;

    /**
     * 查询变化及锁定待核实记录。
     */
    private final DatasourcePageMapper pages;

    /**
     * 写入核实证据和变化状态。
     */
    private final StructureChangeRecordMapper changes;

    /**
     * 查询对象世代及登记身份退役。
     */
    private final CatalogObjectMapper objects;

    /**
     * 读取历史结构和证据中的 JSON 值。
     */
    private final JsonCodec json;

    /**
     * 从成功快照取得固定的前后结构。
     */
    private final CatalogMapper catalog;

    /**
     * 共用页面和动作权限。
     */
    private final PermissionService permissions;

    /**
     * 确认及提交绑定到一致的提案。
     */
    private final ReviewService reviews;

    /**
     * 幂等命令和写入事务。
     */
    private final MutationService commands;

    /**
     * 与目录事实一起发出跨模块事件。
     */
    private final DatasourceEvents events;

    /**
     * 浏览当前源的实际变化记录。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<StructureChangeVO> list(long source, StructureChangeQuery query) {
        permissions.require("collections.view");
        sources.source(source, false);

        // 日期按请求业务时区换算为半开 UTC 区间。
        ZoneId zone = ZoneId.of(query.getTimeZone());
        LocalDateTime from = query.getFromDate() == null
                ? null
                : LocalDate.parse(query.getFromDate())
                        .atStartOfDay(zone)
                        .withZoneSameInstant(ZoneOffset.UTC)
                        .toLocalDateTime();
        LocalDateTime until = query.getToDate() == null
                ? null
                : LocalDate.parse(query.getToDate())
                        .plusDays(1)
                        .atStartOfDay(zone)
                        .withZoneSameInstant(ZoneOffset.UTC)
                        .toLocalDateTime();
        String word = Objects.toString(query.getKeyword(), "");
        String type = Objects.toString(query.getChangeType(), "");
        String stage = Objects.toString(query.getStage(), "");
        Long object = Ids.nullable(query.getObjectId());

        // 分页插件用专用计数语句复用变化列表的关联和条件。
        var page = query.<ChangeProjection>toFixedOrderMpPage();
        page.setCountId("changeCount");
        return PageVO.from(
                pages.changePage(page, source, word, type, stage, from, until, object, query.getTaskId()),
                this::viewOf);
    }

    /**
     * 回看独立变化事实，权限由采集记录页面决定。
     */
    public StructureChangeVO detail(long source, long id) {
        permissions.require("collections.view");
        return viewOf(require(source, id, false));
    }

    /**
     * 使用当前变化、已选结论及具体依据签发核对。
     */
    public ImpactPreviewVO preview(long source, long id, ChangeReviewPreviewDTO input) {
        DatasourcePO current = sources.source(source, false);
        var view = viewOf(require(source, id, false));
        validate(current, view, input.getExpectedVersion(), input.getProposal());
        return reviews.preview(
                "REVIEW_STRUCTURE_CHANGE",
                view.getChangeId(),
                view.getVersion(),
                input.getProposal(),
                List.of(new ChangeVO(
                        "decision",
                        "核实结论",
                        view.getFactState(),
                        input.getProposal().getDecision())),
                List.of(),
                List.of());
    }

    /**
     * 变化证据、目录版本、审计和领域事件同事务更新。
     */
    public StructureChangeVO review(long source, long id, String command, ChangeReviewDTO input) {
        commands.execute(
                "collections.review",
                command,
                "change:" + id,
                List.of(input.getExpectedVersion(), input.getProposal()),
                input.getExpectedVersion(),
                false,
                false,
                () -> {
                    // 先锁源，再锁具体待核实变化。
                    DatasourcePO current = sources.source(source, true);
                    ChangeProjection row = require(source, id, true);
                    var view = viewOf(row);
                    var proposal = input.getProposal();
                    validate(current, view, input.getExpectedVersion(), proposal);
                    reviews.verify(
                            "REVIEW_STRUCTURE_CHANGE",
                            view.getChangeId(),
                            view.getVersion(),
                            proposal,
                            input.getReviewToken());

                    Long object = null;
                    Long revision = null;
                    if (List.of("REBUILT", "SAME_OBJECT").contains(proposal.getDecision())) {
                        // 用当时两侧观察重新解析身份；历史只追加核实证据，不改写原观察。
                        var earlier = catalog.object(
                                source,
                                null,
                                row.beforeObservationId(),
                                current.getCurrentConnectionRevisionId(),
                                false,
                                true);
                        var next = catalog.currentIdentityObservation(source, current.getCurrentSnapshotId(), id);
                        long prior = earlier.objectId();
                        boolean rebuilt = "REBUILT".equals(proposal.getDecision());
                        object = rebuilt
                                ? catalog.newObject(source, prior, objects.generation(source, prior) + 1, null)
                                : prior;
                        revision = catalog.newRevision(object, next, rebuilt ? null : earlier);
                    }

                    resolve(
                            source,
                            row,
                            UUID.fromString(command).toString(),
                            UserContext.getUser(),
                            proposal.getDecision(),
                            proposal.getEvidence().strip(),
                            object,
                            revision);
                    // 核实结果与对应治理影响一起生效。
                    events.structure(source, null, id);
                    events.catalog(source, current.getCurrentSnapshotId(), records.catalogVersion(source));
                    auditChange(current, view, proposal, command);
                    return new MutationVO(command, "structure_change", view.getChangeId(), view.getVersion() + 1);
                });
        return detail(source, id);
    }

    /**
     * 在核实命令事务内保存证据、应用结论并推进目录版本。
     */
    private void resolve(
            long source,
            ChangeProjection row,
            String command,
            long actor,
            String decision,
            String evidence,
            Long object,
            Long revision) {
        boolean excluded = List.of("STILL_EXISTS", "EXCLUDE").contains(decision);
        // 核实结论决定变化类型、处理阶段以及旧对象是否退役。
        String type =
                switch (decision) {
                    case "REMOVED" -> "OBJECT_REMOVED";
                    case "REBUILT" -> "OBJECT_REBUILT";
                    default -> row.changeType();
                };

        changes.appendReview(
                source,
                row.id(),
                command,
                actor,
                row.version(),
                decision,
                evidence,
                row.afterObservationId(),
                object,
                revision);
        int affected = changes.update(Wrappers.<StructureChangePO>lambdaUpdate()
                .eq(StructureChangePO::getSourceId, source)
                .eq(StructureChangePO::getId, row.id())
                .eq(StructureChangePO::getVersion, row.version())
                .set(StructureChangePO::getChangeType, type)
                .set(StructureChangePO::getFactState, excluded ? "EXCLUDED" : "CONFIRMED")
                .set(StructureChangePO::getStage, excluded ? "COMPLETED" : "PENDING_GOVERNANCE")
                .set(object != null, StructureChangePO::getObjectId, object)
                .setIncrBy(StructureChangePO::getVersion, 1)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
        if (affected != 1) {
            // 版本冲突时与已追加的核实证据一起回滚。
            throw BizIllegalException.conflict();
        }
        if (List.of("REMOVED", "REBUILT").contains(decision)) {
            // 已证实的旧身份仅保留历史查看，新同名对象拥有独立身份。
            objects.retire(source, row.comparisonObjectId(), row.id());
        }
        records.advanceCatalogVersion(source);
    }

    /**
     * 审计目标使用真实变化编号，以便公共命令可靠查询。
     */
    private void auditChange(
            DatasourcePO source, StructureChangeVO view, ChangeReviewProposalDTO proposal, String command) {
        sources.recordStructureChange(
                source,
                view.getChangeId(),
                command,
                proposal.getEvidence().strip(),
                view.getFactState(),
                proposal.getDecision());
    }

    /**
     * 校验实质可办、当前版本和规定的事实结论。
     */
    private void validate(DatasourcePO source, StructureChangeVO view, int version, ChangeReviewProposalDTO proposal) {
        if (version != view.getVersion()) {
            throw BizIllegalException.conflict();
        }
        if (!"PENDING".equals(view.getFactState()) || view.getSuccessorChangeId() != null) {
            throw new CommonException(409507, 409, "此变化已有结论或已转给后续记录", null);
        }
        List<String> decisions =
                switch (view.getChangeType()) {
                    case "ACCESS_LOST" -> List.of("REMOVED", "STILL_EXISTS");
                    case "IDENTITY_UNCERTAIN" -> List.of("SAME_OBJECT", "REBUILT");
                    default -> List.of("CONFIRM", "EXCLUDE");
                };
        if (!decisions.contains(proposal.getDecision())
                || proposal.getEvidence().strip().isEmpty()) {
            throw BizIllegalException.invalid("请选择适用的核实结论并填写依据");
        }
        if ("IDENTITY_UNCERTAIN".equals(view.getChangeType())
                && (source.getCurrentSnapshotId() == null
                        || catalog.currentIdentityObservation(
                                        source.getId(),
                                        source.getCurrentSnapshotId(),
                                        Long.parseLong(view.getChangeId()))
                                == null)) {
            throw new CommonException(409507, 409, "该观察已有更新的成功快照，请打开最新记录", null);
        }
    }

    /**
     * 源号和变化号共同限定查询及写入范围。
     */
    private ChangeProjection require(long source, long id, boolean lock) {
        ChangeProjection row = lock ? pages.lockChange(source, id) : pages.changeDetail(source, id);
        if (row == null) {
            // 其他来源的变化也视为不在当前查询范围内。
            throw BizIllegalException.missing();
        }
        return row;
    }

    /**
     * 将实际变化和最新核实事实组装为可披露视图。
     */
    private StructureChangeVO viewOf(ChangeProjection row) {
        Review review = row.decision() == null
                ? null
                : new Review(row.decision(), row.actorId(), row.evidenceText(), Times.instant(row.reviewedAt()));
        return new StructureChangeVO(
                Long.toString(row.id()),
                Long.toString(row.sourceId()),
                row.discoveryTaskId(),
                row.targetPath(),
                row.changeType(),
                row.attributeKind(),
                Times.instant(row.discoveredAt()),
                row.factState(),
                row.stage(),
                row.version(),
                Objects.toString(row.successorChangeId(), null),
                node(row.beforeValue()),
                node(row.afterValue()),
                node(row.evidence()),
                review);
    }

    /**
     * 读取数据库保存的历史 JSON 值，沿用原有空值语义。
     */
    private JsonNode node(String value) {
        return value == null ? null : json.mapper().readTree(value);
    }
}
