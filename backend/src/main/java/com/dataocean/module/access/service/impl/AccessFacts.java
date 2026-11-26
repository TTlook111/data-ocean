package com.dataocean.module.access.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.common.Ids;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.entity.po.AccessRequestItemPO;
import com.dataocean.module.access.entity.po.OutputRulePO;
import com.dataocean.module.access.entity.vo.AccessSourceOptionVO;
import com.dataocean.module.access.entity.vo.OutputRuleVO;
import com.dataocean.module.access.mapper.AccessReadMapper;
import com.dataocean.module.access.mapper.AccessRequestItemMapper;
import com.dataocean.module.access.mapper.OutputRuleMapper;
import com.dataocean.module.access.mapper.ResponsibilityMapper;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.entity.po.DatasourcePO;
import com.dataocean.module.datasource.mapper.DatasourceRecordMapper;
import com.dataocean.module.iam.entity.po.UserPO;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.UserOptionVO;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.MutationService;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 归一授权各入口使用的实体依据、审批责任与审计事实。
 */
@Component
@RequiredArgsConstructor
class AccessFacts {

    /**
     * 当前共享规则。
     */
    private final OutputRuleMapper rules;

    /**
     * 当前责任配置。
     */
    private final ResponsibilityMapper responsibilities;

    /**
     * 申请子项事实。
     */
    private final AccessRequestItemMapper items;

    /**
     * 稳定数据源身份。
     */
    private final DatasourceRecordMapper sources;

    /**
     * 当前人员身份。
     */
    private final UserMapper users;

    /**
     * 与队列统一的资格条件。
     */
    private final AccessReadMapper reads;

    /**
     * 公共审计写入。
     */
    private final AuditService audits;

    /**
     * 公共授权版本和事务约束。
     */
    private final MutationService mutations;

    /**
     * 受控快照编解码。
     */
    private final JsonCodec json;

    /**
     * 将已知源和规则编号解析成不重复的稳定键。
     */
    static List<Long> ids(List<String> values) {
        if (values == null) {
            // 可选项缺省表示尚未指定范围。
            return List.of();
        }
        List<Long> parsed = values.stream().map(Ids::parse).distinct().toList();
        if (parsed.size() != values.size()) {
            // 不接受同一资源重复计入一个业务提案。
            throw BizIllegalException.invalid("请移除重复选项");
        }
        return parsed;
    }

    /**
     * 在同一权威快照中读取适用规则，按原选择顺序返回。
     */
    List<OutputRuleVO> resolveRules(List<String> ruleIds) {
        var keys = ids(ruleIds);
        if (keys.isEmpty()) {
            // 有效来源必须绑定至少一条明示限制。
            throw BizIllegalException.invalid("请选择至少一条输出限制规则");
        }
        var byId = rules.selectByIds(keys).stream().collect(Collectors.toMap(OutputRulePO::getId, AccessFacts::rule));
        if (byId.size() != keys.size()) {
            // 无法核验规则时不提交任何授权来源。
            throw BizIllegalException.state("部分输出限制规则已不可用，请重新选择");
        }
        return keys.stream().map(byId::get).toList();
    }

    /**
     * 以持久编号回读规则，内容始终取当前修订。
     */
    List<OutputRuleVO> currentRules(List<Long> ids) {
        return resolveRules(ids.stream().map(String::valueOf).toList());
    }

    /**
     * 回看办理时的全文及修订快照。
     */
    List<OutputRuleVO> snapshot(String stored) {
        return stored == null ? List.of() : List.of(json.read(stored, OutputRuleVO[].class));
    }

    /**
     * 读取开始审核或授予时允许使用的数据源。
     */
    DatasourcePO enabledSource(long id) {
        var row = sources.selectById(id);
        if (row == null || !"ENABLED".equals(row.getLifecycle())) {
            // 已停用或不存在的源不能建立新访问。
            throw BizIllegalException.state("数据源当前不可授予访问");
        }
        return row;
    }

    /**
     * 获得最小安全披露的数据源名称。
     */
    AccessSourceOptionVO source(Long id) {
        return id == null ? null : sourceOption(sources.selectById(id));
    }

    /**
     * 将稳定源身份映射到配置选项。
     */
    static AccessSourceOptionVO sourceOption(DatasourcePO row) {
        return new AccessSourceOptionVO(row.getId().toString(), row.getName());
    }

    /**
     * 申请账号停用时停止新的批复和分派。
     */
    boolean enabledUser(long id) {
        var row = users.selectById(id);
        return row != null && "ENABLED".equals(row.getLifecycle());
    }

    /**
     * 披露当前账号的必要身份字段，办理权限另行核验。
     */
    UserOptionVO person(Long id) {
        return id == null ? null : person(users.selectById(id));
    }

    /**
     * 使用现有人员选项结构，审批候选本身已通过查询资格。
     */
    static UserOptionVO person(UserPO row) {
        return new UserOptionVO(
                row.getId().toString(),
                row.getUsername(),
                row.getDisplayName(),
                row.getLifecycle(),
                row.getVersion(),
                "ENABLED".equals(row.getLifecycle()));
    }

    /**
     * 对待办使用当前有效责任；已经处理的内容从历史事实回看。
     */
    Long assignee(Long sourceId, long applicant) {
        if (sourceId == null) {
            // 未核定源需要先分流。
            return null;
        }
        var source = sources.selectById(sourceId);
        var configured = responsibilities.selectById(sourceId);
        if (source == null || !"ENABLED".equals(source.getLifecycle()) || configured == null) {
            // 源不可用或尚无具名责任，仍保留待分流。
            return null;
        }
        Long selected = Objects.equals(configured.getPrimaryUserId(), applicant)
                ? configured.getAlternateUserId()
                : configured.getPrimaryUserId();
        return !Objects.equals(selected, applicant) && reads.eligible(selected) ? selected : null;
    }

    /**
     * 读某份申请下全部稳定子项，调用方按访问范围披露。
     */
    List<AccessRequestItemPO> items(long request) {
        return items.selectList(Wrappers.<AccessRequestItemPO>lambdaQuery()
                .eq(AccessRequestItemPO::getRequestId, request)
                .orderByAsc(AccessRequestItemPO::getId));
    }

    /**
     * 从持久规则构造明确的对外快照。
     */
    static OutputRuleVO rule(OutputRulePO row) {
        return new OutputRuleVO(
                row.getId().toString(),
                row.getName(),
                row.getContent(),
                row.getVersion(),
                Times.instant(row.getUpdatedAt()));
    }

    /**
     * 保存访问配置或决定的实际结果，并返回共用的原命令定位。
     */
    MutationVO record(
            String operation,
            String kind,
            long id,
            String name,
            int version,
            String command,
            String reason,
            List<ChangeVO> changes) {
        var key = UUID.fromString(command).toString();
        audits.record(AuditWriteDTO.builder()
                .actionCode(operation)
                .targetType(kind)
                .targetId(Long.toString(id))
                .targetName(name)
                .reason(reason)
                .changes(changes)
                .beforeRoles(List.of())
                .afterRoles(List.of())
                .commandId(key)
                .securityVersion(mutations.auditSecurityVersion(true))
                .build());
        return new MutationVO(key, kind, Long.toString(id), version);
    }
}
