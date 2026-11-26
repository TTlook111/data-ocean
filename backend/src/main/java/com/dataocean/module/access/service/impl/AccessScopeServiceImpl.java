package com.dataocean.module.access.service.impl;

import static com.dataocean.module.access.service.impl.AccessConfigurationServiceImpl.pattern;
import static com.dataocean.module.access.service.impl.AccessRequestServiceImpl.start;
import static com.dataocean.module.access.service.impl.AccessRequestServiceImpl.stop;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.entity.po.AccessGrantPO;
import com.dataocean.module.access.entity.po.AccessRequestItemPO;
import com.dataocean.module.access.entity.po.AccessRequestPO;
import com.dataocean.module.access.entity.query.AccessListQuery;
import com.dataocean.module.access.entity.vo.AccessGrantVO;
import com.dataocean.module.access.entity.vo.AccessOriginVO;
import com.dataocean.module.access.entity.vo.AccessScopeVO;
import com.dataocean.module.access.entity.vo.DepartmentGrantVO;
import com.dataocean.module.access.entity.vo.GrantDirectoryVO;
import com.dataocean.module.access.entity.vo.OutputRuleVO;
import com.dataocean.module.access.mapper.AccessGrantMapper;
import com.dataocean.module.access.mapper.AccessReadMapper;
import com.dataocean.module.access.mapper.AccessRequestItemMapper;
import com.dataocean.module.access.mapper.AccessRequestMapper;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.datasource.mapper.DatasourceRecordMapper;
import com.dataocean.module.iam.entity.dto.VersionActionDTO;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.ImpactCountVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.mapper.DepartmentMapper;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.ReviewService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 当前权威数据访问范围与个人来源的实际结束处理。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AccessScopeServiceImpl implements AccessScopeService {

    /**
     * 个人来源的实际开始与结束记录。
     */
    private final AccessGrantMapper grants;

    /**
     * 访问申请和原审批依据。
     */
    private final AccessRequestMapper requests;

    /**
     * 个人来源关联审批子项。
     */
    private final AccessRequestItemMapper items;

    /**
     * 按实际实时条件查询可用来源。
     */
    private final AccessReadMapper reads;

    /**
     * 读取部门历史名称。
     */
    private final DepartmentMapper departments;

    /**
     * 按来源当前生命周期确定访问。
     */
    private final DatasourceRecordMapper sources;

    /**
     * 双来源共用的规则核对。
     */
    private final AccessFacts facts;

    /**
     * 原命令原子执行与审计保护。
     */
    private final MutationService mutations;

    /**
     * 绑定撤销理由、办理人和当前聚合版本。
     */
    private final ReviewService reviews;

    /**
     * 保留撤销后的申请修订及原来源事实。
     */
    private final JsonCodec json;

    /**
     * 对同源的实际部门与申请来源逐笔保留，再合并相同正文。
     */
    @Override
    public List<AccessScopeVO> current(long user) {
        var effective = new LinkedHashMap<Long, List<AccessOriginVO>>();
        for (var row : reads.departmentSources(user)) {
            // 直属兼任与主部门同口径，来源仅在成员关系有效时计入。
            var department = departments.selectById(row.getDepartmentId());
            var origin = new AccessOriginVO(
                    row.getId().toString(),
                    "DEPARTMENT",
                    department.getId().toString(),
                    department.getName(),
                    facts.currentRules(reads.departmentRules(row.getId())));
            effective
                    .computeIfAbsent(row.getSourceId(), ignored -> new ArrayList<>())
                    .add(origin);
        }
        for (var row : reads.personalSources(user)) {
            // 每个批准子项对应一笔可独立核实的个人来源。
            var item = items.selectById(row.getItemId());
            var origin = new AccessOriginVO(
                    row.getId().toString(),
                    "REQUEST",
                    item.getRequestId().toString(),
                    "访问申请 " + item.getRequestId(),
                    facts.currentRules(reads.itemRules(item.getId())));
            effective
                    .computeIfAbsent(item.getSourceId(), ignored -> new ArrayList<>())
                    .add(origin);
        }
        return effective.entrySet().stream()
                .map(entry -> scope(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 每次使用前读取当前权威来源，失败时对上层业务拒绝。
     */
    @Override
    public AccessScopeVO requireSource(long user, long source) {
        return findSource(user, source).orElseThrow(BizIllegalException::denied);
    }

    /**
     * 使用同一权威范围读取可选源授权，缺少来源是正常的查询结果。
     */
    @Override
    public Optional<AccessScopeVO> findSource(long user, long source) {
        var target = Long.toString(source);
        return current(user).stream()
                .filter(item -> item.getSource().getId().equals(target))
                .findFirst();
    }

    /**
     * 管理列表当前状态和计数使用一致的筛选条件。
     */
    @Override
    public PageVO<AccessGrantVO> grants(AccessListQuery query) {
        return PageVO.from(
                reads.grants(
                        query.toFixedOrderMpPage(OrderItem.desc("g.id")),
                        Ids.nullable(query.getSourceId()),
                        Ids.nullable(query.getRequestId()),
                        pattern(query.getApplicant()),
                        pattern(query.getHandler()),
                        query.getState(),
                        start(query.getDateFrom(), query.getTimeZone()),
                        stop(query.getDateTo(), query.getTimeZone())),
                this::grant);
    }

    /**
     * 按原申请和该账号的实际来源取得全部历史。
     */
    @Override
    public PageVO<AccessGrantVO> userGrants(long user, AccessListQuery query) {
        return PageVO.from(
                reads.userGrants(
                        query.toFixedOrderMpPage(OrderItem.desc("g.id")), user, Ids.nullable(query.getSourceId())),
                this::grant);
    }

    /**
     * 合并两种来源的同一页，部门没有审批人且不提供个人撤销。
     */
    @Override
    public PageVO<GrantDirectoryVO> grantDirectory(AccessListQuery query) {
        var page = reads.grantDirectory(
                query.toFixedOrderMpPage(
                        OrderItem.desc("visible.started_at"),
                        OrderItem.asc("visible.kind"),
                        OrderItem.desc("visible.record_id"),
                        OrderItem.desc("visible.membership_id")),
                Ids.nullable(query.getSourceId()),
                Ids.nullable(query.getRequestId()),
                Ids.nullable(query.getGrantId()),
                pattern(query.getApplicant()),
                pattern(query.getHandler()),
                pattern(query.getKeyword()),
                query.getState(),
                start(query.getDateFrom(), query.getTimeZone()),
                stop(query.getDateTo(), query.getTimeZone()));
        return PageVO.from(page, entry -> {
            // 申请来源复用独立授权的正式历史结构。
            if ("REQUEST".equals(entry.kind())) {
                return new GrantDirectoryVO(
                        "request:" + entry.recordId(), grant(grants.selectById(entry.recordId())), null);
            }
            var department = departmentGrant(new AccessReadMapper.DepartmentGrantRow(
                    entry.recordId(),
                    entry.membershipId(),
                    entry.departmentId(),
                    entry.departmentName(),
                    entry.userId(),
                    entry.sourceId(),
                    entry.startedAt(),
                    entry.endedAt(),
                    entry.state(),
                    entry.ruleSnapshot()));
            return new GrantDirectoryVO("department:" + department.getId(), null, department);
        });
    }

    /**
     * 从真实配置、直属关系及源状态计算当前和历史的部门来源。
     */
    @Override
    public PageVO<DepartmentGrantVO> departmentGrants(Long user, AccessListQuery query) {
        return PageVO.from(
                reads.departmentGrants(
                        query.toFixedOrderMpPage(
                                OrderItem.desc("grant_row.started_at"),
                                OrderItem.desc("grant_row.configuration_id"),
                                OrderItem.desc("grant_row.membership_id")),
                        user,
                        Ids.nullable(query.getSourceId()),
                        pattern(query.getApplicant()),
                        pattern(query.getKeyword()),
                        query.getState(),
                        start(query.getDateFrom(), query.getTimeZone()),
                        stop(query.getDateTo(), query.getTimeZone())),
                this::departmentGrant);
    }

    /**
     * 以同一口径装配管理列表和个人历史的部门行。
     */
    private DepartmentGrantVO departmentGrant(AccessReadMapper.DepartmentGrantRow row) {
        return new DepartmentGrantVO(
                row.configurationId() + ":" + row.membershipId(),
                Long.toString(row.departmentId()),
                row.departmentName(),
                facts.person(row.userId()),
                facts.source(row.sourceId()),
                row.state(),
                Times.instant(row.startedAt()),
                Times.instant(row.endedAt()),
                "EFFECTIVE".equals(row.state())
                        ? facts.currentRules(reads.departmentRules(row.configurationId()))
                        : facts.snapshot(row.ruleSnapshot()));
    }

    /**
     * 展示当前有效的受影响源和撤销后保留的来源。
     */
    @Override
    public ImpactPreviewVO previewRevoke(long id, int version, String reason) {
        var row = requireGrant(id);
        var request = request(row, version);
        var revoked = group(row, request);
        var scope = current(request.getApplicantId());
        var affected = revoked.stream()
                .map(record -> {
                    // 逐源列出仍保留的授权。
                    var item = items.selectById(record.getItemId());
                    var source = facts.source(item.getSourceId());
                    var remaining = scope.stream()
                            .filter(effective -> effective.getSource().getId().equals(source.getId()))
                            .flatMap(effective -> effective.getOrigins().stream())
                            .filter(origin -> !"REQUEST".equals(origin.getKind())
                                    || !origin.getId().equals(record.getId().toString()))
                            .map(AccessOriginVO::getOriginName)
                            .toList();
                    return source.getName() + " · "
                            + (remaining.isEmpty() ? "失去访问" : "保留 " + String.join("、", remaining));
                })
                .toList();
        return reviews.preview(
                "ACCESS_REVOKE",
                Long.toString(id),
                version,
                new RevokeProposal(reason),
                List.of(new ChangeVO("state", "授权来源", "有效或暂停", "已结束")),
                List.of(),
                affected);
    }

    /**
     * 撤销只结束本笔来源，联合授予由同一锁内原子结束。
     */
    @Override
    @Transactional
    public MutationVO revoke(long id, String command, VersionActionDTO input) {
        String operation = "grants.revoke";
        return mutations.execute(
                operation, command, Long.toString(id), input, input.getExpectedVersion(), true, false, () -> {
                    // 同一预览既锁定原聚合，也校验原因。
                    var row = requireGrant(id);
                    var request = request(row, input.getExpectedVersion());
                    reviews.verify(
                            "ACCESS_REVOKE",
                            Long.toString(id),
                            input.getExpectedVersion(),
                            new RevokeProposal(input.getReason()),
                            input.getReviewToken());
                    var before = group(row, request);
                    end(before, UserContext.getUser(), input.getReason().trim());
                    request.setVersion(request.getVersion() + 1);
                    requests.updateById(request);
                    var snapshot = before.stream()
                            .map(grant -> grant(grants.selectById(grant.getId())))
                            .toList();
                    reads.revision(
                            request.getId(),
                            request.getVersion(),
                            "REVOKE",
                            UserContext.getUser(),
                            json.write(snapshot));
                    return facts.record(
                            operation,
                            "access_grant",
                            id,
                            "授权来源 " + id,
                            request.getVersion(),
                            command,
                            input.getReason().trim(),
                            snapshot.stream()
                                    .map(grant -> new ChangeVO(
                                            "state", grant.getSource().getName(), "有效或暂停", "已结束"))
                                    .toList());
                });
    }

    /**
     * 即使账号随后恢复，也不复活已因账号停用而结束的个人来源。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void terminateForUser(long user, long actor, String reason) {
        var pending =
                requests.selectList(Wrappers.<AccessRequestPO>lambdaQuery().eq(AccessRequestPO::getApplicantId, user));
        for (var request : pending) {
            // 按申请更新实际来源，同时提升版本以使旧审批核对失效。
            var all = facts.items(request.getId());
            var active = openGrants(all);
            var unfinished = all.stream()
                    .filter(item ->
                            List.of("PENDING", "APPROVED", "NEEDS_SUPPLEMENT").contains(item.getState()))
                    .toList();
            if (!active.isEmpty() || !unfinished.isEmpty()) {
                end(active, actor, reason);
                unfinished.forEach(item -> {
                    // 不覆盖原有批准人，账号安全事件由原 IAM 审计记录。
                    item.setState("ENDED");
                    items.updateById(item);
                });
                request.setVersion(request.getVersion() + 1);
                requests.updateById(request);
                reads.revision(
                        request.getId(),
                        request.getVersion(),
                        "ACCOUNT_DISABLED",
                        actor,
                        json.write(facts.items(request.getId())));
            }
        }
    }

    /**
     * 只有开放配置会阻止归档，历史按组织身份留存。
     */
    @Override
    public boolean hasDepartmentConfiguration(long department) {
        return reads.configured(department);
    }

    /**
     * 预览列出受数据源状态影响、仍保留历史关系的来源。
     */
    @Override
    public List<ImpactCountVO> sourceImpact(long source) {
        return List.of(
                new ImpactCountVO("个人授权来源", reads.grantImpact(source)),
                new ImpactCountVO("部门初始配置", reads.departmentImpact(source)));
    }

    /**
     * 组合单中仍开放的全部来源同时办理。
     */
    private List<AccessGrantPO> group(AccessGrantPO grant, AccessRequestPO request) {
        return request.getJoint() ? openGrants(facts.items(request.getId())) : List.of(grant);
    }

    /**
     * 仅查仍开启的来源，避免覆盖之前的结束原因。
     */
    private List<AccessGrantPO> openGrants(List<AccessRequestItemPO> linked) {
        if (linked.isEmpty()) {
            // 没有子项时不构建空的 IN 清单。
            return List.of();
        }
        return grants.selectList(Wrappers.<AccessGrantPO>lambdaQuery()
                .in(
                        AccessGrantPO::getItemId,
                        linked.stream().map(AccessRequestItemPO::getId).toList())
                .isNull(AccessGrantPO::getEndedAt));
    }

    /**
     * 保存当前终止事实，同时保留原人工办理信息。
     */
    private void end(List<AccessGrantPO> active, long actor, String reason) {
        var at = reads.now();
        for (var grant : active) {
            // 在同一事务结束个人来源并更新申请的来源状态。
            grant.setEndedAt(at);
            grant.setEndedBy(actor);
            grant.setEndReason(reason);
            grants.updateById(grant);
            var item = items.selectById(grant.getItemId());
            item.setState("ENDED");
            items.updateById(item);
        }
    }

    /**
     * 不允许通过历史结束记录再次计算撤销提案。
     */
    private AccessGrantPO requireGrant(long id) {
        var row = grants.selectById(id);
        if (row == null) {
            // 仅存在且可撤销的申请来源进入核对。
            throw BizIllegalException.missing();
        }
        if (row.getEndedAt() != null) {
            // 原命令可回读，新的命令不能再次撤销。
            throw BizIllegalException.state("该授权来源已结束");
        }
        return row;
    }

    /**
     * 撤销时使用整份申请的当前版本检查并发。
     */
    private AccessRequestPO request(AccessGrantPO row, int expected) {
        var item = items.selectById(row.getItemId());
        var request = requests.selectById(item.getRequestId());
        if (!Objects.equals(request.getVersion(), expected)) {
            // 同一联合组合已变化时重新生成预览。
            throw BizIllegalException.conflict();
        }
        return request;
    }

    /**
     * 构造来源历史及当前可用状态，审批人取实际办理事实。
     */
    private AccessGrantVO grant(AccessGrantPO row) {
        var item = items.selectById(row.getItemId());
        var request = requests.selectById(item.getRequestId());
        var source = sources.selectById(item.getSourceId());
        boolean available = facts.enabledUser(request.getApplicantId())
                && "ENABLED".equals(source.getLifecycle())
                && (!request.getJoint() || reads.jointAvailable(request.getId()));
        String state = row.getEndedAt() != null ? "ENDED" : available ? "EFFECTIVE" : "SUSPENDED";
        return new AccessGrantVO(
                row.getId().toString(),
                facts.person(request.getApplicantId()),
                facts.person(item.getHandledBy()),
                AccessFacts.sourceOption(source),
                request.getId().toString(),
                request.getVersion(),
                request.getJoint(),
                state,
                Times.instant(item.getHandledAt()),
                Times.instant(row.getStartedAt()),
                Times.instant(row.getEndedAt()),
                row.getEndReason(),
                facts.snapshot(item.getRuleSnapshot()));
    }

    /**
     * 对当前有效规则按中文正文去重，来源仍分开回看。
     */
    private AccessScopeVO scope(long source, List<AccessOriginVO> origins) {
        var distinct = new LinkedHashMap<String, OutputRuleVO>();
        for (var origin : origins) {
            // 任何一笔有效来源中存在的限制都适用于最终返回。
            origin.getRules().forEach(rule -> distinct.putIfAbsent(rule.getContent(), rule));
        }
        return new AccessScopeVO(facts.source(source), List.copyOf(distinct.values()), origins);
    }

    /**
     * 预览与确认共同使用的撤销理由。
     *
     * @param reason 操作人填写的实际撤销原因
     */
    private record RevokeProposal(String reason) {}
}
