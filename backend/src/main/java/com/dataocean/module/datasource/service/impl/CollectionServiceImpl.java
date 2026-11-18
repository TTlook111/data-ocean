package com.dataocean.module.datasource.service.impl;

import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.CommonException;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.SecretKeyProvider;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.entity.dto.SourceActionPreviewDTO;
import com.dataocean.module.datasource.entity.dto.StartCollectionDTO;
import com.dataocean.module.datasource.entity.po.CollectionSnapshotPO;
import com.dataocean.module.datasource.entity.po.CollectionTaskPO;
import com.dataocean.module.datasource.entity.po.DatasourcePO;
import com.dataocean.module.datasource.entity.query.CatalogQuery;
import com.dataocean.module.datasource.entity.query.CollectionQuery;
import com.dataocean.module.datasource.entity.vo.CatalogObjectDetailVO;
import com.dataocean.module.datasource.entity.vo.CatalogPageVO;
import com.dataocean.module.datasource.entity.vo.CatalogPageVO.ObjectRow;
import com.dataocean.module.datasource.entity.vo.CollectionDetailVO;
import com.dataocean.module.datasource.entity.vo.CollectionDetailVO.Actor;
import com.dataocean.module.datasource.entity.vo.CollectionDetailVO.Content;
import com.dataocean.module.datasource.entity.vo.CollectionDetailVO.Counts;
import com.dataocean.module.datasource.entity.vo.CollectionDetailVO.Scope;
import com.dataocean.module.datasource.entity.vo.CollectionDetailVO.StopProgress;
import com.dataocean.module.datasource.mapper.CatalogMapper;
import com.dataocean.module.datasource.mapper.CatalogMapper.Observation;
import com.dataocean.module.datasource.mapper.CollectionMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper.RevisionRow;
import com.dataocean.module.datasource.security.DatasourceRequestPermission;
import com.dataocean.module.datasource.service.CollectionRunner;
import com.dataocean.module.datasource.service.DatasourceEvents;
import com.dataocean.module.iam.entity.dto.VersionActionDTO;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ReviewService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 采集接收、取消、任务回看及目录查询。不同页面各自核验权限。
 */
@Service
@RequiredArgsConstructor
public class CollectionServiceImpl {

    /**
     * 同一来源的配置和占位。
     */
    private final DatasourceMapper sources;

    /**
     * 采集接收和持久化进度。
     */
    private final CollectionMapper tasks;

    /**
     * 成功目录及已冻结的来源证据。
     */
    private final CatalogMapper catalog;

    /**
     * 当前权限核验。
     */
    private final PermissionService permission;

    /**
     * 共用核对凭据签发及校验。
     */
    private final ReviewService reviews;

    /**
     * 可靠命令登记及同事务提交。
     */
    private final MutationService commands;

    /**
     * 用户动作成功审计。
     */
    private final AuditService audit;

    /**
     * 来源与比较依据的稳定摘要。
     */
    private final SecretKeyProvider signatures;

    /**
     * 序列化明确类型的公开或内部提案。
     */
    private final JsonCodec json;

    /**
     * 接收及目录相关 Outbox。
     */
    private final DatasourceEvents events;

    /**
     * 在无实际执行时直接结束已取消任务。
     */
    private final CollectionRunner runner;

    /**
     * 依据固定范围生成一次采集核对。
     */
    public ImpactPreviewVO review(long sourceId, SourceActionPreviewDTO preview) {
        if (!"START_COLLECTION".equals(preview.getAction())) {
            throw BizIllegalException.invalid("不支持的采集核对动作");
        }
        StartProposal proposal = json.mapper().treeToValue(preview.getProposal(), StartProposal.class);
        permission.require(DatasourceRequestPermission.collectionOperation(proposal.retryOfTaskId() != null));
        DatasourcePO source = source(sourceId, false);
        requireVersion(source.getVersion(), preview.getExpectedVersion());
        RevisionRow revision = admissible(source, proposal.retryOfTaskId());
        var conf = revision.configuration();
        return reviews.preview(
                "START_COLLECTION",
                Long.toString(sourceId),
                source.getVersion(),
                proposal,
                List.of(
                        new ChangeVO("range", "采集范围", null, scopeLabel(revision)),
                        new ChangeVO("account", "采集账号", null, conf.getAccount())),
                List.of(),
                List.of());
    }

    /**
     * 同命令只生成一个真实采集任务。
     */
    public CollectionDetailVO start(long sourceId, String command, StartCollectionDTO input) {
        StartProposal proposal = new StartProposal(input.getRetryOfTaskId());
        String operation = DatasourceRequestPermission.collectionOperation(proposal.retryOfTaskId() != null);
        commands.execute(
                operation,
                command,
                "datasource:" + sourceId,
                List.of(input.getExpectedVersion(), proposal),
                null,
                false,
                false,
                () -> {
                    // 源锁覆盖检查、候选创建、占位和审计。
                    DatasourcePO source = source(sourceId, true);
                    requireVersion(source.getVersion(), input.getExpectedVersion());
                    reviews.verify(
                            "START_COLLECTION",
                            Long.toString(sourceId),
                            source.getVersion(),
                            proposal,
                            input.getReviewToken());
                    RevisionRow revision = admissible(source, proposal.retryOfTaskId());
                    var user = UserContext.current();
                    Actor actor = new Actor("USER", Long.toString(user.userId()), user.username(), user.displayName());
                    String task = accept(
                            source,
                            revision,
                            normalizedCommand(command),
                            actor,
                            proposal.retryOfTaskId(),
                            null,
                            null,
                            null,
                            null);
                    record(operation, source, command, "task", "采集任务", null, task, null);
                    return new MutationVO(command, "datasource", Long.toString(sourceId), source.getVersion());
                });
        return view(tasks.byCommand(sourceId, UserContext.getUser(), normalizedCommand(command)));
    }

    /**
     * 单一接收入口供定时和人工动作复用。
     */
    public String accept(
            DatasourcePO source,
            RevisionRow revision,
            String command,
            Actor actor,
            String parent,
            Integer scheduleVersion,
            Long cycle,
            LocalDateTime due,
            String timeZone) {
        String id = UUID.randomUUID().toString();
        var conf = revision.configuration();
        byte[] contract = signatures.hmac(
                "catalog:contract:1|" + json.write(List.of(conf.getDatabaseType(), conf.getHost(), conf.getPort())));
        tasks.accept(
                id,
                command,
                source.getId(),
                revision,
                Ids.nullable(actor.id()),
                json.write(actor),
                parent,
                scheduleVersion,
                cycle,
                due,
                timeZone,
                contract);
        events.accepted(source.getId(), id, command, source.getVersion());
        return id;
    }

    /**
     * 取消核对绑定真实任务版本和明确的取消原因。
     */
    public ImpactPreviewVO reviewCancel(String id, SourceActionPreviewDTO preview) {
        if (!"CANCEL_COLLECTION".equals(preview.getAction())) {
            throw BizIllegalException.invalid("不支持的任务核对动作");
        }
        CancelProposal proposal = json.mapper().treeToValue(preview.getProposal(), CancelProposal.class);
        String reason = reason(proposal.reason());
        CollectionTaskPO task = task(id);
        requireVersion(task.getVersion(), preview.getExpectedVersion());
        requireInFlight(task);
        return reviews.preview(
                "CANCEL_COLLECTION",
                id,
                task.getVersion(),
                new CancelProposal(reason),
                List.of(new ChangeVO("state", "任务状态", task.getState(), "请求取消")),
                List.of(),
                List.of("当前目录保留最近成功结果，服务端会确认本次读取停止。"));
    }

    /**
     * 接收人工取消意图。排队任务没有源侧执行时直接结束。
     */
    public CollectionDetailVO cancel(String id, String command, VersionActionDTO input) {
        CollectionTaskPO original = task(id);
        String reason = reason(input.getReason());
        commands.execute(
                "collections.cancel",
                command,
                "collection:" + id,
                List.of(input.getExpectedVersion(), reason),
                null,
                false,
                false,
                () -> {
                    // 权限保护及源行先于任务锁。
                    DatasourcePO source = source(original.getSourceId(), true);
                    CollectionTaskPO current = tasks.task(id, true);
                    requireVersion(current.getVersion(), input.getExpectedVersion());
                    requireInFlight(current);
                    reviews.verify(
                            "CANCEL_COLLECTION",
                            id,
                            current.getVersion(),
                            new CancelProposal(reason),
                            input.getReviewToken());
                    if (!tasks.requestCancel(id, reason)) {
                        // 取消没有写入时结束本次命令，避免生成成功审计。
                        throw BizIllegalException.conflict();
                    }
                    if ("QUEUED".equals(current.getState())) {
                        runner.terminal(tasks.task(id, false), "CANCELLED", null, null);
                    }
                    record("collections.cancel", source, command, "task", "取消采集", null, id, reason);
                    return new MutationVO(command, "datasource", Long.toString(source.getId()), source.getVersion());
                });
        return view(task(id));
    }

    /**
     * 限制回看始终按当前采集记录权限。
     */
    public CollectionDetailVO detail(String id) {
        permission.require("collections.view");
        return view(task(id));
    }

    /**
     * 仅供已逐次复核会话和查看权限的采集订阅读取状态。
     */
    CollectionDetailVO streamSnapshot(String id) {
        return view(task(id));
    }

    /**
     * 查询实际任务开始日期、状态及发起方式，使用公共分页结果。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<CollectionDetailVO> list(long sourceId, CollectionQuery query) {
        permission.require("collections.view");
        source(sourceId, false);
        long size = query.getPageSize();
        if (query.getStartCommandId() != null) {
            CollectionTaskPO task =
                    tasks.byCommand(sourceId, UserContext.getUser(), normalizedCommand(query.getStartCommandId()));
            if (task == null) {
                return new PageVO<>(List.of(), 0, 1, size);
            }
            permission.require(DatasourceRequestPermission.collectionOperation(task.getParentTaskId() != null));
            return new PageVO<>(List.of(view(task)), 1, 1, size);
        }
        LocalDateTime from = null;
        LocalDateTime until = null;
        if (query.getFromDate() != null || query.getToDate() != null) {
            if (query.getFromDate() == null || query.getToDate() == null) {
                throw BizIllegalException.invalid("请同时填写开始和结束日期");
            }
            ZoneId zone = ZoneId.of(query.getTimeZone());
            from = LocalDateTime.ofInstant(
                    LocalDate.parse(query.getFromDate()).atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
            until = LocalDateTime.ofInstant(
                    LocalDate.parse(query.getToDate())
                            .plusDays(1)
                            .atStartOfDay(zone)
                            .toInstant(),
                    ZoneOffset.UTC);
            if (!until.isAfter(from)) {
                throw BizIllegalException.invalid("开始日期不得晚于结束日期");
            }
        }
        String number = normalize(query.getTaskNoKeyword());
        String kind = normalize(query.getTriggerKind());
        String state = normalize(query.getState());
        var page = tasks.page(sourceId, number, kind, state, from, until, query);

        // 只批量补充当前页的历史修订和成功快照，任务排序与计数保持原口径。
        var revisions = sources.revisions(
                sourceId,
                page.getRecords().stream()
                        .map(CollectionTaskPO::getConfigRevisionId)
                        .distinct()
                        .toList());
        var snapshotTasks = page.getRecords().stream()
                .filter(task -> "SUCCEEDED".equals(task.getState()))
                .map(CollectionTaskPO::getId)
                .toList();
        var snapshots = tasks.snapshotsForTasks(snapshotTasks).stream()
                .collect(Collectors.toMap(CollectionSnapshotPO::getTaskId, Function.identity()));
        return PageVO.from(
                page, task -> view(task, revisions.get(task.getConfigRevisionId()), snapshots.get(task.getId())));
    }

    /**
     * 在同一目录版本读出列表、总数和来源范围。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CatalogPageVO directory(long sourceId, CatalogQuery query) {
        permission.require("catalog.view");
        DatasourcePO source = source(sourceId, false);
        RevisionRow revision = sources.revision(sourceId, source.getCurrentConnectionRevisionId());
        Scope effective = scope(revision);
        int version = source.getCatalogVersion();
        long size = query.getPageSize();
        if (source.getCurrentSnapshotId() == null) {
            return new CatalogPageVO(new PageVO<>(List.of(), 0, 1, size), null, version, effective, null, 0);
        }
        String keyword = normalize(query.getObjectKeyword());
        String type = normalize(query.getObjectType());
        String state = normalize(query.getCatalogState());
        long readable = catalog.count(source.getId(), source.getCurrentSnapshotId(), revision.id(), "", "", "");
        long historicalRevision = tasks.snapshotConfiguration(source.getCurrentSnapshotId());
        var page =
                catalog.page(source.getId(), source.getCurrentSnapshotId(), revision.id(), keyword, type, state, query);
        return new CatalogPageVO(
                PageVO.from(page, row -> row(sourceId, row)),
                Long.toString(source.getCurrentSnapshotId()),
                version,
                effective,
                scope(sources.revision(sourceId, historicalRevision)),
                readable);
    }

    /**
     * 历史对象仅可使用明确成功快照，当前对象还须匹配有效范围。
     */
    @Transactional(readOnly = true)
    public CatalogObjectDetailVO object(long sourceId, Long requestedSnapshot, long id, boolean observation) {
        permission.require("catalog.view");
        DatasourcePO source = source(sourceId, false);
        Long snapshot =
                observation ? null : requestedSnapshot != null ? requestedSnapshot : source.getCurrentSnapshotId();
        if (snapshot == null && !observation) {
            throw BizIllegalException.missing();
        }
        Observation found = catalog.object(
                sourceId,
                snapshot,
                id,
                source.getCurrentConnectionRevisionId(),
                requestedSnapshot == null && !observation,
                observation);
        if (found == null) {
            throw BizIllegalException.missing();
        }
        var structure = found.structure();
        return new CatalogObjectDetailVO(
                row(sourceId, found),
                string(found.revisionId()),
                structure.fields(),
                structure.primaryKeys(),
                structure.foreignKeys(),
                structure.indexes(),
                structure.object().parent());
    }

    /**
     * 提交或重试只能从可用来源、明确的范围进入。
     */
    private RevisionRow admissible(DatasourcePO source, String retry) {
        if (!"ENABLED".equals(source.getLifecycle())) {
            throw BizIllegalException.state("请先启用数据源");
        }
        if (source.getPendingChangeId() != null) {
            throw new CommonException(409506, 409, "凭据替换正在核实", null);
        }
        if (source.getActiveTaskId() != null) {
            throw new CommonException(409504, 409, "此源已有进行中的采集", null);
        }
        if (retry != null) {
            CollectionTaskPO former = task(retry);
            if (former.getSourceId() != source.getId()
                    || !List.of("FAILED", "CANCELLED").contains(former.getState())) {
                throw BizIllegalException.missing();
            }
        }
        return sources.revision(source.getId(), source.getCurrentConnectionRevisionId());
    }

    /**
     * 对应实际任务内容和进度生成不会披露秘密的视图。
     */
    private CollectionDetailVO view(CollectionTaskPO task) {
        RevisionRow revision = sources.revision(task.getSourceId(), task.getConfigRevisionId());
        var snapshot = "SUCCEEDED".equals(task.getState()) ? tasks.snapshot(task.getId()) : null;
        return view(task, revision, snapshot);
    }

    /**
     * 用已读取的配置和成功快照组装采集任务视图。
     */
    private CollectionDetailVO view(CollectionTaskPO task, RevisionRow revision, CollectionSnapshotPO snapshot) {
        long attempted = task.getCompleteCount() + task.getPartialCount() + task.getFailedCount();
        Content content = null;
        if ("RUNNING".equals(task.getState())) {
            content = new Content(
                    task.getPhase(),
                    new Counts(
                            task.getTotalCount() != null,
                            task.getDiscoveredCount(),
                            task.getTotalCount(),
                            attempted,
                            task.getCompleteCount(),
                            task.getPartialCount(),
                            task.getFailedCount(),
                            task.getDiscoveredCount() - attempted),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        } else if ("SUCCEEDED".equals(task.getState())) {
            content = new Content(
                    null,
                    null,
                    Long.toString(snapshot.getId()),
                    string(snapshot.getPreviousSuccessSnapshotId()),
                    string(snapshot.getComparisonSnapshotId()),
                    task.getCompleteCount(),
                    snapshot.getAnalysis(),
                    snapshot.getNewChangeCount(),
                    null,
                    null,
                    null);
        } else if ("FAILED".equals(task.getState())) {
            content = new Content(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    task.getFailureCode(),
                    task.getFailureMessage(),
                    null);
        } else if ("CANCELLED".equals(task.getState())) {
            content = new Content(null, null, null, null, null, null, null, null, null, null, task.getCancelReason());
        }
        StopProgress stop = null;
        if ("CANCELLED".equals(task.getState())
                || ("RUNNING".equals(task.getState()) && !"RUNNING".equals(task.getExecutionState()))) {
            stop = new StopProgress(
                    instant(task.getCancelRequestedAt()),
                    instant(task.getStopConfirmedAt()),
                    task.getExecutionState(),
                    "STOPPED".equals(task.getExecutionState()) ? "已确认结束" : "正在核实原连接停止");
        }
        return new CollectionDetailVO(
                task.getId(),
                task.getTaskNo(),
                Long.toString(task.getSourceId()),
                task.getTriggerKind(),
                json.read(task.getActorRef(), Actor.class),
                scopeLabel(revision),
                scope(revision),
                revision.configuration().getAccount(),
                instant(task.getCreatedAt()),
                instant(task.getStartedAt()),
                instant(task.getEndedAt()),
                task.getParentTaskId(),
                revision.revision(),
                task.getState(),
                task.getExecutionState(),
                task.getVersion(),
                task.getStreamEpoch(),
                stop,
                content);
    }

    /**
     * 目录对象摘要从观察中的真实路径生成。
     */
    private ObjectRow row(long source, Observation row) {
        var ref = row.structure().object();
        String path = ref.schema().isEmpty() ? ref.name() : ref.schema() + "." + ref.name();
        return new ObjectRow(
                string(row.objectId()),
                Long.toString(row.id()),
                Long.toString(source),
                ref.schema(),
                ref.name(),
                path,
                ref.type(),
                ref.comment(),
                List.of(row.identityChangeId() == null ? "COLLECTED" : "IDENTITY_UNCERTAIN"),
                Long.toString(row.snapshotId()),
                instant(row.collectedAt()));
    }

    /**
     * 目录与任务用相同领域范围。
     */
    private Scope scope(RevisionRow revision) {
        var conf = revision.configuration();
        return new Scope(conf.getDatabaseType(), conf.getDatabaseName(), conf.getSchemas());
    }

    /**
     * 用户确认的范围描述包含选定 Schema。
     */
    private String scopeLabel(RevisionRow revision) {
        Scope scope = scope(revision);
        return scope.databaseName() + (scope.schemas().isEmpty() ? "" : " / " + String.join("、", scope.schemas()))
                + " · 全部可见对象";
    }

    /**
     * 保存命令对应的成功审计，不记录源库秘密。
     */
    private void record(
            String action,
            DatasourcePO source,
            String command,
            String field,
            String label,
            String before,
            String after,
            String reason) {
        audit.record(AuditWriteDTO.builder()
                .actionCode(action)
                .targetType("datasource")
                .targetId(Long.toString(source.getId()))
                .targetName(source.getName())
                .reason(reason)
                .changes(List.of(new ChangeVO(field, label, before, after)))
                .beforeRoles(List.of())
                .afterRoles(List.of())
                .securityVersion(commands.auditSecurityVersion(false))
                .commandId(normalizedCommand(command))
                .build());
    }

    /**
     * 避免在终态上重复办理取消。
     */
    private void requireInFlight(CollectionTaskPO task) {
        if (!List.of("QUEUED", "RUNNING").contains(task.getState()) || task.getCancelRequestedAt() != null) {
            throw BizIllegalException.state("该任务已结束或正在取消");
        }
    }

    /**
     * 使用同一规则核对同时发生的来源或任务变更。
     */
    private void requireVersion(int actual, Integer expected) {
        if (!Objects.equals(actual, expected)) {
            throw BizIllegalException.conflict();
        }
    }

    /**
     * 获取指定源，查找失败使用共用不可见响应。
     */
    private DatasourcePO source(long id, boolean lock) {
        DatasourcePO row = lock ? sources.lock(id) : sources.get(id);
        if (row == null) {
            throw BizIllegalException.missing();
        }
        return row;
    }

    /**
     * 使用 UUID 查阅任务。
     */
    private CollectionTaskPO task(String id) {
        CollectionTaskPO task = tasks.task(normalizedCommand(id), false);
        if (task == null) {
            throw BizIllegalException.missing();
        }
        return task;
    }

    /**
     * 限制取消原因的实际 Unicode 字符数并保留正文。
     */
    private String reason(String input) {
        String value = normalize(input);
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 500) {
            throw BizIllegalException.invalid("请填写 1 至 500 字的取消原因");
        }
        return value;
    }

    /**
     * 显式 UUID 格式避免传入任意命令标识。
     */
    private String normalizedCommand(String id) {
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException error) {
            throw BizIllegalException.invalid("任务或命令编号格式无效");
        }
    }

    /**
     * 通用文本筛选默认匹配全部。
     */
    private String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 将平台 UTC 时间返回为外部可读时间。
     */
    private Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    /**
     * 资源编号按十进制字符串输出。
     */
    private String string(Long value) {
        return value == null ? null : value.toString();
    }

    /**
     * 发起采集的非秘密预览提案。
     *
     * @param retryOfTaskId 发起重试时的历史任务
     */
    private record StartProposal(String retryOfTaskId) {}

    /**
     * 取消事件的签名内容。
     *
     * @param reason 明确记录的取消原因
     */
    private record CancelProposal(String reason) {}
}
