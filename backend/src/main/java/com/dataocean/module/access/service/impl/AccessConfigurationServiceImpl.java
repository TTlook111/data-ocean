package com.dataocean.module.access.service.impl;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.TextSearch;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.entity.dto.DepartmentAccessDTO;
import com.dataocean.module.access.entity.dto.OutputRuleDTO;
import com.dataocean.module.access.entity.dto.ResponsibilityDTO;
import com.dataocean.module.access.entity.po.DepartmentSourcePO;
import com.dataocean.module.access.entity.po.OutputRulePO;
import com.dataocean.module.access.entity.po.ResponsibilityPO;
import com.dataocean.module.access.entity.query.AccessListQuery;
import com.dataocean.module.access.entity.vo.AccessScopeVO;
import com.dataocean.module.access.entity.vo.AccessSourceOptionVO;
import com.dataocean.module.access.entity.vo.DepartmentAccessVO;
import com.dataocean.module.access.entity.vo.OutputRuleVO;
import com.dataocean.module.access.entity.vo.ResponsibilityVO;
import com.dataocean.module.access.mapper.AccessReadMapper;
import com.dataocean.module.access.mapper.DepartmentSourceMapper;
import com.dataocean.module.access.mapper.OutputRuleMapper;
import com.dataocean.module.access.mapper.ResponsibilityMapper;
import com.dataocean.module.access.service.AccessConfigurationService;
import com.dataocean.module.datasource.entity.po.DatasourcePO;
import com.dataocean.module.datasource.mapper.DatasourceRecordMapper;
import com.dataocean.module.iam.entity.po.DepartmentPO;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.ImpactCountVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.UserOptionVO;
import com.dataocean.module.iam.mapper.DepartmentMapper;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ReviewService;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 共享规则、审批责任及直属部门初始来源的配置实现。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AccessConfigurationServiceImpl implements AccessConfigurationService {

    /**
     * 共享规则的当前修订。
     */
    private final OutputRuleMapper rules;

    /**
     * 责任的当前配置。
     */
    private final ResponsibilityMapper responsibilities;

    /**
     * 部门历史配置与当前生效区间。
     */
    private final DepartmentSourceMapper departmentSources;

    /**
     * 读取组织状态及配置版本。
     */
    private final DepartmentMapper departments;

    /**
     * 稳定数据源读取。
     */
    private final DatasourceRecordMapper sources;

    /**
     * 多表队列、时间与部门锚点。
     */
    private final AccessReadMapper reads;

    /**
     * 授权配置共用的事实校验。
     */
    private final AccessFacts facts;

    /**
     * 共用带授权变更锁的写命令。
     */
    private final MutationService mutations;

    /**
     * 规则引用及快照编码。
     */
    private final JsonCodec json;

    /**
     * 配置预览绑定当前版本和全局授权变化。
     */
    private final ReviewService reviews;

    /**
     * 根据是否结束初始访问核对同一个预览入口。
     */
    private final PermissionService permissions;

    /**
     * 查询条件与总数采用同一数据库快照。
     */
    @Override
    public PageVO<OutputRuleVO> rules(AccessListQuery query) {
        var filter = Wrappers.<OutputRulePO>lambdaQuery();
        for (String term : TextSearch.terms(query.getKeyword())) {
            // 关键词可命中名称或限制全文，多词取交集。
            filter.apply("(LOWER(name) LIKE {0} ESCAPE '!' OR LOWER(content) LIKE {0} ESCAPE '!')", term);
        }
        for (String term : TextSearch.terms(query.getRuleName())) {
            // 规则名称和返回限制的条件取交集。
            filter.apply("LOWER(name) LIKE {0} ESCAPE '!'", term);
        }
        for (String term : TextSearch.terms(query.getRuleContent())) {
            // 规则正文只按本字段筛选。
            filter.apply("LOWER(content) LIKE {0} ESCAPE '!'", term);
        }
        return PageVO.from(rules.selectPage(query.toFixedOrderMpPage(OrderItem.desc("id")), filter), AccessFacts::rule);
    }

    /**
     * 锁内检验版本与名称，所有有效来源实时采用新修订。
     */
    @Override
    @Transactional
    public MutationVO saveRule(Long id, String command, OutputRuleDTO input) {
        String operation = id == null ? "output-rules.create" : "output-rules.edit";
        return mutations.execute(
                operation,
                command,
                id == null ? "new" : id.toString(),
                input,
                input.getExpectedVersion(),
                true,
                false,
                () -> {
                    // 编辑现有实体，或以一号修订创建。
                    var row = id == null ? new OutputRulePO() : rules.selectById(id);
                    if (row == null) {
                        // 不存在的编辑目标不创建替代资源。
                        throw BizIllegalException.missing();
                    }
                    if (!Objects.equals(row.getVersion(), input.getExpectedVersion())) {
                        // 禁止用旧预览覆盖其他规则编辑。
                        throw BizIllegalException.conflict();
                    }
                    if (id != null) {
                        // 共享正文修改沿用最新引用影响的核对依据。
                        confirm("OUTPUT_RULE_EDIT", id, row.getVersion(), input, input.getReviewToken());
                    }
                    var name = input.getName().trim();
                    var content = input.getContent().trim();
                    if (rules.exists(Wrappers.<OutputRulePO>lambdaQuery()
                            .eq(OutputRulePO::getName, name)
                            .ne(id != null, OutputRulePO::getId, id))) {
                        // 规则名称在配置选项中保持唯一。
                        throw BizIllegalException.state("输出限制规则名称已存在");
                    }
                    if (Objects.equals(row.getName(), name) && Objects.equals(row.getContent(), content)) {
                        // 纯重复保存无需递增授权版本。
                        return new MutationVO(command, "output_rule", id.toString(), row.getVersion());
                    }
                    var changes = List.of(
                            new ChangeVO("name", "规则名称", row.getName(), name),
                            new ChangeVO("content", "限制内容", row.getContent(), content));
                    row.setName(name);
                    row.setContent(content);
                    row.setVersion(id == null ? 1 : row.getVersion() + 1);
                    row.setUpdatedAt(reads.now());
                    if (id == null) {
                        // 新规则获得独立且可持续引用的编号。
                        rules.insert(row);
                    } else {
                        // 保持现有授权绑定原规则编号。
                        rules.updateById(row);
                    }
                    return facts.record(
                            operation, "output_rule", row.getId(), name, row.getVersion(), command, null, changes);
                });
    }

    /**
     * 只提供可配置源的名称和编号。
     */
    @Override
    public PageVO<AccessSourceOptionVO> sourceOptions(AccessListQuery query) {
        var filter = Wrappers.<DatasourcePO>lambdaQuery().eq(DatasourcePO::getLifecycle, "ENABLED");
        for (String term : TextSearch.terms(query.getKeyword())) {
            // 搜索不披露连接信息。
            filter.apply("LOWER(name) LIKE {0} ESCAPE '!'", term);
        }
        return PageVO.from(
                sources.selectPage(query.toFixedOrderMpPage(OrderItem.asc("name"), OrderItem.asc("id")), filter),
                AccessFacts::sourceOption);
    }

    /**
     * 编辑核对保存前后的共享正文及实际引用来源数量。
     */
    @Override
    public ImpactPreviewVO previewRule(long id, OutputRuleDTO input) {
        var row = rules.selectById(id);
        if (row == null) {
            // 不签发未知配置的核对。
            throw BizIllegalException.missing();
        }
        verifyVersion(row.getVersion(), input.getExpectedVersion());
        var key = Long.toString(id);
        return reviews.preview(
                "OUTPUT_RULE_EDIT",
                key,
                row.getVersion(),
                input,
                List.of(new ChangeVO(
                        "content", "输出限制", row.getContent(), input.getContent().trim())),
                List.of(
                        new ImpactCountVO("个人授权来源", reads.activeRuleGrants(id)),
                        new ImpactCountVO("部门初始配置", reads.activeRuleDepartments(id))),
                List.of("保存后，当前有效引用统一采用新内容。"));
    }

    /**
     * 选项按实际页面和审批资格筛选。
     */
    @Override
    public PageVO<UserOptionVO> approvers(String keyword, PageQuery query) {
        return PageVO.from(
                reads.approvers(
                        query.toFixedOrderMpPage(OrderItem.asc("u.display_name"), OrderItem.asc("u.id")),
                        pattern(keyword)),
                AccessFacts::person);
    }

    /**
     * 源、两类责任及责任状态的组合查询。
     */
    @Override
    public PageVO<ResponsibilityVO> responsibilities(AccessListQuery query) {
        return PageVO.from(
                reads.responsibilities(
                        query.toFixedOrderMpPage(OrderItem.asc("s.name"), OrderItem.asc("s.id")),
                        pattern(query.getKeyword()),
                        pattern(query.getApplicant()),
                        pattern(query.getHandler()),
                        query.getState()),
                this::responsibility);
    }

    /**
     * 实时反映两位责任人的可用性，保存配置并不授予任何数据访问。
     */
    @Override
    @Transactional
    public MutationVO saveResponsibility(long sourceId, String command, ResponsibilityDTO input) {
        String operation = "sources.approval-config";
        return mutations.execute(
                operation, command, Long.toString(sourceId), input, input.getExpectedVersion(), true, false, () -> {
                    // 责任配置以当前源和两个可用的具体账号为准。
                    var source = facts.enabledSource(sourceId);
                    var row = responsibilities.selectById(sourceId);
                    int version = row == null ? 0 : row.getVersion();
                    if (input.getExpectedVersion() != version) {
                        // 重配须基于最后一次保存版本。
                        throw BizIllegalException.conflict();
                    }
                    confirm("RESPONSIBILITY_EDIT", sourceId, version, input, input.getReviewToken());
                    long primary = Ids.parse(input.getPrimaryUserId());
                    long alternate = Ids.parse(input.getAlternateUserId());
                    validateApprovers(primary, alternate);
                    if (row != null && row.getPrimaryUserId() == primary && row.getAlternateUserId() == alternate) {
                        // 无实质变更不写审计，也不刷新队列修订。
                        return new MutationVO(command, "datasource", Long.toString(sourceId), version);
                    }
                    var changes = List.of(
                            new ChangeVO(
                                    "primary",
                                    "主审批人",
                                    row == null ? null : row.getPrimaryUserId().toString(),
                                    Long.toString(primary)),
                            new ChangeVO(
                                    "alternate",
                                    "替代负责人",
                                    row == null
                                            ? null
                                            : row.getAlternateUserId().toString(),
                                    Long.toString(alternate)));
                    var configured = new ResponsibilityPO();
                    configured.setSourceId(sourceId);
                    configured.setPrimaryUserId(primary);
                    configured.setAlternateUserId(alternate);
                    configured.setVersion(version + 1);
                    configured.setUpdatedAt(reads.now());
                    if (row == null) {
                        // 首次保存建立唯一源责任记录。
                        responsibilities.insert(configured);
                    } else {
                        // 待审批项继续按同一源的当前责任动态分派。
                        responsibilities.updateById(configured);
                    }
                    return facts.record(
                            operation,
                            "datasource",
                            sourceId,
                            source.getName(),
                            configured.getVersion(),
                            command,
                            null,
                            changes);
                });
    }

    /**
     * 预览显示当前两位人员和待重新分派的未办理项。
     */
    @Override
    public ImpactPreviewVO previewResponsibility(long id, ResponsibilityDTO input) {
        facts.enabledSource(id);
        var row = responsibilities.selectById(id);
        int version = row == null ? 0 : row.getVersion();
        verifyVersion(version, input.getExpectedVersion());
        long primary = Ids.parse(input.getPrimaryUserId());
        long alternate = Ids.parse(input.getAlternateUserId());
        validateApprovers(primary, alternate);
        return reviews.preview(
                "RESPONSIBILITY_EDIT",
                Long.toString(id),
                version,
                input,
                List.of(
                        new ChangeVO(
                                "primary", "主审批人", row == null ? null : name(row.getPrimaryUserId()), name(primary)),
                        new ChangeVO(
                                "alternate",
                                "替代负责人",
                                row == null ? null : name(row.getAlternateUserId()),
                                name(alternate))),
                List.of(new ImpactCountVO("待办理子项", reads.pendingImpact(id))),
                List.of());
    }

    /**
     * 至少保证两位具名且当前可实际执行审批的账号。
     */
    private void validateApprovers(long primary, long alternate) {
        if (primary == alternate || !reads.eligible(primary) || !reads.eligible(alternate)) {
            // 停用与未完成改密的账号不能承担审批。
            throw BizIllegalException.invalid("请选择两位不同且可审批的人员");
        }
    }

    /**
     * 核对使用可辨认姓名和稳定登录名。
     */
    private String name(long id) {
        var person = facts.person(id);
        return person.getDisplayName() + "（" + person.getUsername() + "）";
    }

    /**
     * 组装未配置、可分派或待复核的责任视图。
     */
    private ResponsibilityVO responsibility(DatasourcePO source) {
        var option = AccessFacts.sourceOption(source);
        var row = responsibilities.selectById(source.getId());
        if (row == null) {
            // 未配置时保持空负责人，便于定位待分流原因。
            return new ResponsibilityVO(option, null, null, 0, "UNCONFIGURED", null);
        }
        boolean ready = reads.eligible(row.getPrimaryUserId()) && reads.eligible(row.getAlternateUserId());
        return new ResponsibilityVO(
                option,
                facts.person(row.getPrimaryUserId()),
                facts.person(row.getAlternateUserId()),
                row.getVersion(),
                ready ? "READY" : "REVIEW",
                Times.instant(row.getUpdatedAt()));
    }

    /**
     * 按稳定部门 ID 回看原有选择和影响口径。
     */
    @Override
    public DepartmentAccessVO department(long id) {
        var row = checkedDepartment(id);
        return new DepartmentAccessVO(
                Long.toString(id),
                row.getName(),
                row.getVersion(),
                row.isAccessReviewRequired(),
                reads.memberCount(id),
                activeSources(id).stream()
                        .map(source -> new AccessScopeVO(
                                facts.source(source.getSourceId()),
                                facts.currentRules(reads.departmentRules(source.getId())),
                                List.of()))
                        .toList());
    }

    /**
     * 按经核对的部门版本替换配置，保留已结束历史。
     */
    @Override
    @Transactional
    public MutationVO saveDepartment(long id, String command, DepartmentAccessDTO input) {
        String operation = departmentOperation(input);
        return mutations.execute(
                operation, command, Long.toString(id), input, input.getExpectedVersion(), true, false, () -> {
                    // 当前成员修改、配置变化及恢复都会更新部门版本。
                    var row = checkedDepartment(id);
                    if (!Objects.equals(row.getVersion(), input.getExpectedVersion())) {
                        // 预览后的成员或配置发生变化需重新核对。
                        throw BizIllegalException.conflict();
                    }
                    confirm("DEPARTMENT_ACCESS_EDIT", id, row.getVersion(), input, input.getReviewToken());
                    if (!input.getSources().isEmpty() && !"ENABLED".equals(row.getLifecycle())) {
                        // 停用部门只可结束现有配置。
                        throw BizIllegalException.state("请先恢复部门再配置初始访问");
                    }
                    var before = activeSources(id);
                    var previous = before.stream()
                            .collect(Collectors.toMap(
                                    DepartmentSourcePO::getSourceId,
                                    config -> new HashSet<>(reads.departmentRules(config.getId()))));
                    var desired = new LinkedHashMap<Long, Set<Long>>();
                    for (var selection : input.getSources()) {
                        // 避免同源多笔配置影响来源计算。
                        long source = Ids.parse(selection.getSourceId());
                        facts.enabledSource(source);
                        facts.resolveRules(selection.getRuleIds());
                        if (desired.putIfAbsent(source, Set.copyOf(AccessFacts.ids(selection.getRuleIds()))) != null) {
                            // 表单每个数据源只有一组规则。
                            throw BizIllegalException.invalid("请合并重复的数据源");
                        }
                    }
                    if (previous.equals(desired) && !row.isAccessReviewRequired()) {
                        // 规则仅改变顺序时保持原来源和配置版本。
                        return new MutationVO(command, "department", Long.toString(id), row.getVersion());
                    }
                    for (var old : before) {
                        // 仅结束被移除或规则实质改变的旧来源。
                        if (!Objects.equals(previous.get(old.getSourceId()), desired.get(old.getSourceId()))) {
                            old.setEndedAt(reads.now());
                            departmentSources.updateById(old);
                        }
                    }
                    desired.forEach((source, ids) -> {
                        // 保留原配置来源，新的配置才创建新生效区间。
                        if (!Objects.equals(previous.get(source), ids)) {
                            var selection = ids.stream().sorted().toList();
                            var configured = new DepartmentSourcePO();
                            configured.setDepartmentId(id);
                            configured.setSourceId(source);
                            configured.setRuleSnapshot(json.write(facts.currentRules(selection)));
                            departmentSources.insert(configured);
                            for (int index = 0; index < selection.size(); index++) {
                                // 关系编排序号保持稳定，JSON 快照仅记录当时实际正文。
                                reads.addDepartmentRule(configured.getId(), selection.get(index), index + 1);
                            }
                        }
                    });
                    reads.confirmDepartment(id);
                    return facts.record(
                            operation,
                            "department",
                            id,
                            row.getName(),
                            row.getVersion() + 1,
                            command,
                            input.getReason(),
                            List.of(new ChangeVO("sources", "初始数据访问", json.write(previous), json.write(desired))));
                });
    }

    /**
     * 按当前直属成员与真实现有来源核对增减和输出限制全文。
     */
    @Override
    public ImpactPreviewVO previewDepartment(long id, DepartmentAccessDTO input) {
        permissions.require(departmentOperation(input));
        var department = checkedDepartment(id);
        verifyVersion(department.getVersion(), input.getExpectedVersion());
        var before = activeSources(id).stream()
                .collect(Collectors.toMap(
                        DepartmentSourcePO::getSourceId,
                        config -> ruleSummary(facts.currentRules(reads.departmentRules(config.getId())))));
        var changes = new java.util.ArrayList<ChangeVO>();
        for (var selection : input.getSources()) {
            // 每条新配置核对当前数据源身份与规则全文。
            long source = Ids.parse(selection.getSourceId());
            var selected = facts.enabledSource(source);
            changes.add(new ChangeVO(
                    "source",
                    selected.getName(),
                    before.remove(source),
                    ruleSummary(facts.resolveRules(selection.getRuleIds()))));
        }
        before.forEach((source, rules) ->
                changes.add(new ChangeVO("source", facts.source(source).getName(), rules, null)));
        return reviews.preview(
                "DEPARTMENT_ACCESS_EDIT",
                Long.toString(id),
                department.getVersion(),
                input,
                changes,
                List.of(new ImpactCountVO("直属成员", Math.toIntExact(reads.memberCount(id)))),
                List.of());
    }

    /**
     * 在预览和保存间保持相同的专用动作权限。
     */
    private String departmentOperation(DepartmentAccessDTO input) {
        return input.getSources().isEmpty() ? "departments.end-access" : "departments.access";
    }

    /**
     * 读回规则名称及限制全文，避免把规则名称当作授权原文。
     */
    private String ruleSummary(List<OutputRuleVO> values) {
        return values.stream()
                .map(rule -> rule.getName() + "：" + rule.getContent())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 变更须携带该实际提案和当前全局授权版本的凭据。
     */
    private void confirm(String action, long id, int version, Object input, String token) {
        if (token == null || token.isBlank()) {
            // 管理者仍需使用真实预览的当前结果。
            throw BizIllegalException.invalid("请先核对授权配置");
        }
        reviews.verify(action, Long.toString(id), version, input, token);
    }

    /**
     * 过期版本不生成新的同目标核对。
     */
    private void verifyVersion(int version, Integer expected) {
        if (!Objects.equals(version, expected)) {
            // 客户端须回读后重新核对。
            throw BizIllegalException.conflict();
        }
    }

    /**
     * 归档部门只提供现有组织历史读取，不能再设置配置。
     */
    private DepartmentPO checkedDepartment(long id) {
        var row = departments.selectById(id);
        if (row == null || "ARCHIVED".equals(row.getLifecycle())) {
            // 禁止通过配置接口扩展归档资源的披露。
            throw BizIllegalException.missing();
        }
        return row;
    }

    /**
     * 列出尚未结束的部内配置。
     */
    private List<DepartmentSourcePO> activeSources(long id) {
        return departmentSources.selectList(Wrappers.<DepartmentSourcePO>lambdaQuery()
                .eq(DepartmentSourcePO::getDepartmentId, id)
                .isNull(DepartmentSourcePO::getEndedAt)
                .orderByAsc(DepartmentSourcePO::getId));
    }

    /**
     * 空搜索条件不参与 SQL，其余搜索按公共字面转义处理。
     */
    static String pattern(String value) {
        return value == null || value.isBlank() ? null : TextSearch.pattern(value);
    }
}
