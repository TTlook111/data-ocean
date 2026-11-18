package com.dataocean.module.audit.service.impl;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.audit.entity.dto.AuditFailureDTO;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.entity.po.AuditPO;
import com.dataocean.module.audit.entity.query.AuditQuery;
import com.dataocean.module.audit.entity.vo.AuditActorVO;
import com.dataocean.module.audit.entity.vo.AuditDetailVO;
import com.dataocean.module.audit.entity.vo.AuditRowVO;
import com.dataocean.module.audit.entity.vo.AuditSummaryVO;
import com.dataocean.module.audit.mapper.AuditMapper;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 写入与业务同事务的成功审计，并独立记录已回滚的拒绝或失败。
 */
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    /**
     * 所属模块的持久化读写适配，不向接口直接返回 PO。
     */
    private final AuditMapper mapper;

    /**
     * 统一的明确类型 JSON 编解码适配。
     */
    private final JsonCodec json;

    /**
     * 独立事务只记录拒绝或失败，不创建虚假的字段差异及成功记录。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(AuditFailureDTO data) {
        var principal = UserContext.current();
        var actor = new AuditActorVO(
                "USER", Long.toString(principal.userId()), principal.username(), principal.displayName());

        // 未创建目标时使用固定标识，失败审计仅记录可披露的请求结果。
        var row = AuditPO.builder()
                .actorKind(actor.getKind())
                .actorUserId(principal.userId())
                .actorSnapshot(json.write(actor))
                .actionCode(data.getActionCode())
                .targetType(data.getTargetType())
                .targetId(data.getTargetId() == null ? "not-created" : data.getTargetId())
                .targetName(data.getTargetId())
                .outcome(data.getHttpStatus() >= 500 ? "FAILED" : "REJECTED")
                .reason("HTTP " + data.getHttpStatus())
                .build();
        mapper.insert(row);
    }

    /**
     * 以本次可信人员身份保存同事务成功审计。
     */
    public void record(AuditWriteDTO data) {
        var actor = UserContext.current();
        persist(data, new AuditActorVO("USER", Long.toString(actor.userId()), actor.username(), actor.displayName()));
    }

    /**
     * 以受控初始化主体保存真实初始化审计。
     */
    public void bootstrap(AuditWriteDTO data) {
        persist(data, new AuditActorVO("BOOTSTRAP", null, null, "受控初始化"));
    }

    /**
     * 以明确系统主体写入同事务的业务审计。
     */
    @Override
    public void system(AuditWriteDTO data, String actorName) {
        persist(data, new AuditActorVO("SYSTEM", null, null, actorName));
    }

    /**
     * 保存必要主体快照、字段差异和角色版本，不复制秘密或完整业务原文。
     */
    private void persist(AuditWriteDTO data, AuditActorVO actor) {
        var before = new AuditSummaryVO(List.of(), data.getBeforeRoles() == null ? List.of() : data.getBeforeRoles());
        var after =
                new AuditSummaryVO(data.getChanges(), data.getAfterRoles() == null ? List.of() : data.getAfterRoles());

        // 写入主体快照、命令关联和必要差异，由数据库生成审计编号及时间。
        var row = AuditPO.builder()
                .actorKind(actor.getKind())
                .actorUserId(Ids.nullable(actor.getUserId()))
                .actorSnapshot(json.write(actor))
                .actionCode(data.getActionCode())
                .targetType(data.getTargetType())
                .targetId(data.getTargetId())
                .targetName(data.getTargetName())
                .outcome("SUCCESS")
                .reason(data.getReason())
                .beforeSummary(json.write(before))
                .afterSummary(json.write(after))
                .securityVersion(data.getSecurityVersion())
                .commandId(data.getCommandId())
                .build();
        mapper.insert(row);
    }

    /**
     * 确保关键业务变化已在同一事务保存对应的成功审计。
     */
    public void assertRecorded(long actor, String command, String action, String target) {
        if (mapper.recorded(actor, command, action, target) < 1) {
            throw new IllegalStateException("Critical mutation has no matching audit");
        }
    }

    /**
     * 在当前权限和查询范围内过滤、统计并返回有效分页记录。
     */
    @Transactional(readOnly = true)
    public PageVO<AuditRowVO> list(AuditQuery query) {
        LocalDateTime from = null, until = null;
        if ((query.getFromDate() == null) != (query.getToDate() == null)) {
            throw BizIllegalException.invalid("请同时填写开始与结束日期");
        }
        if (query.getFromDate() != null) {
            try {
                LocalDate first = LocalDate.parse(query.getFromDate()), last = LocalDate.parse(query.getToDate());
                if (last.isBefore(first)) {
                    throw BizIllegalException.invalid("开始日期不能晚于结束日期");
                }
                ZoneId zone = ZoneId.of(query.getTimeZone());
                from = LocalDateTime.ofInstant(first.atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
                until = LocalDateTime.ofInstant(
                        last.plusDays(1).atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
            } catch (DateTimeException error) {
                throw BizIllegalException.invalid("日期或时区不正确");
            }
        }
        if (query.getOutcome() != null
                && !List.of("SUCCESS", "FAILED", "REJECTED").contains(query.getOutcome())) {
            throw BizIllegalException.invalid("审计结果不正确");
        }
        Page<AuditPO> page = query.toMpPage("a.occurred_at", false);
        page.addOrder(OrderItem.desc("a.id"));
        return PageVO.from(mapper.page(page, query, from, until), this::row);
    }

    /**
     * 从真实记录及主体快照组装可披露的审计列表行。
     */
    private AuditRowVO row(AuditPO data) {
        AuditRowVO result = new AuditRowVO();
        fillRow(result, data);
        return result;
    }

    /**
     * 统一填充列表及详情共用的历史快照字段，不向列表附加业务详情。
     */
    private void fillRow(AuditRowVO result, AuditPO data) {
        result.setId(Ids.text(data.getId()));
        result.setActor(json.read(data.getActorSnapshot(), AuditActorVO.class));
        result.setActionCode(data.getActionCode());
        result.setTargetType(data.getTargetType());
        result.setTargetId(data.getTargetId());
        result.setTargetName(data.getTargetName());
        result.setOutcome(data.getOutcome());
        result.setOccurredAt(Times.instant(data.getOccurredAt()));
    }

    /**
     * 按当前查看资格读取完整业务详情或真实历史快照。
     */
    public AuditDetailVO detail(long id) {
        var data = mapper.selectById(id);
        if (data == null) {
            throw BizIllegalException.missing();
        }
        var summary = data.getAfterSummary() == null
                ? new AuditSummaryVO(List.of(), List.of())
                : json.read(data.getAfterSummary(), AuditSummaryVO.class);
        // 详情在同一份审计概览字段上补充业务明细。
        AuditDetailVO result = new AuditDetailVO();
        fillRow(result, data);
        result.setReason(data.getReason());
        result.setChanges(summary.getChanges());
        result.setSecurityVersion(Ids.text(data.getSecurityVersion()));
        result.setCommandId(data.getCommandId());
        result.setBusinessRequestId(data.getBusinessRequestId());
        result.setRoleSnapshots(summary.getRoleSnapshots());
        return result;
    }

    /**
     * 从真实关联目标人员的成功审计读取可核验的历史角色依据。
     */
    public List<RoleVersionRefVO> recoveryRoles(long userId, long auditId) {
        var data = mapper.selectById(auditId);
        if (data == null
                || !data.getTargetType().equals("user")
                || !data.getTargetId().equals(Long.toString(userId))
                || !data.getOutcome().equals("SUCCESS")) {
            throw BizIllegalException.missing();
        }
        var before = data.getBeforeSummary() == null ? null : json.read(data.getBeforeSummary(), AuditSummaryVO.class);
        var after = data.getAfterSummary() == null ? null : json.read(data.getAfterSummary(), AuditSummaryVO.class);
        if (before != null && !before.getRoleSnapshots().isEmpty()) {
            return before.getRoleSnapshots();
        }
        if (after != null && !after.getRoleSnapshots().isEmpty()) {
            return after.getRoleSnapshots();
        }
        throw BizIllegalException.state("该记录没有可恢复的角色分配依据");
    }
}
