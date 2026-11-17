package com.dataocean.module.audit.service.impl;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {
    private final AuditMapper mapper;
    private final JsonCodec json;
    public void record(AuditWriteDTO data) {
        var actor=UserContext.current();
        persist(data,new AuditActorVO("USER",Long.toString(actor.userId()),actor.username(),actor.displayName()));
    }
    public void bootstrap(AuditWriteDTO data) { persist(data,new AuditActorVO("BOOTSTRAP",null,null,"受控初始化")); }
    private void persist(AuditWriteDTO data,AuditActorVO actor) {
        var before=new AuditSummaryVO(List.of(),data.getBeforeRoles()==null?List.of():data.getBeforeRoles());
        var after=new AuditSummaryVO(data.getChanges(),data.getAfterRoles()==null?List.of():data.getAfterRoles());
        var row=new AuditPO(null,actor.getKind(),Ids.nullable(actor.getUserId()),json.write(actor),data.getActionCode(),data.getTargetType(),data.getTargetId(),data.getTargetName(),
            "SUCCESS",data.getReason(),json.write(before),json.write(after),data.getSecurityVersion(),data.getCommandId(),null,null,null);
        mapper.insert(row);
    }
    public void assertRecorded(long actor,String command,String action,String target) {
        if (mapper.recorded(actor,command,action,target)<1) throw new IllegalStateException("Critical mutation has no matching audit");
    }
    public PageVO<AuditRowVO> list(AuditQuery query) {
        LocalDateTime from=null,until=null;
        if ((query.getFromDate()==null)!=(query.getToDate()==null)) throw BizIllegalException.invalid("请同时填写开始与结束日期");
        if (query.getFromDate()!=null) {
            try {
                LocalDate first=LocalDate.parse(query.getFromDate()),last=LocalDate.parse(query.getToDate());
                if (last.isBefore(first)) throw BizIllegalException.invalid("开始日期不能晚于结束日期");
                ZoneId zone=ZoneId.of(query.getTimeZone());
                from=LocalDateTime.ofInstant(first.atStartOfDay(zone).toInstant(),ZoneOffset.UTC);
                until=LocalDateTime.ofInstant(last.plusDays(1).atStartOfDay(zone).toInstant(),ZoneOffset.UTC);
            } catch (java.time.DateTimeException error) { throw BizIllegalException.invalid("日期或时区不正确"); }
        }
        if (query.getOutcome()!=null && !List.of("SUCCESS","FAILED","REJECTED").contains(query.getOutcome())) throw BizIllegalException.invalid("审计结果不正确");
        Page<AuditPO> page=query.toMpPage("a.occurred_at",false);page.addOrder(OrderItem.desc("a.id"));
        return PageVO.from(mapper.page(page,query,from,until),this::row);
    }
    private AuditRowVO row(AuditPO data) { return new AuditRowVO(Ids.text(data.getId()),json.read(data.getActorSnapshot(),AuditActorVO.class),data.getActionCode(),data.getTargetType(),data.getTargetId(),data.getTargetName(),data.getOutcome(),Times.instant(data.getOccurredAt())); }
    public AuditDetailVO detail(long id) {
        var data=mapper.find(id);if(data==null)throw BizIllegalException.missing();
        var summary=data.getAfterSummary()==null?new AuditSummaryVO(List.of(),List.of()):json.read(data.getAfterSummary(),AuditSummaryVO.class);
        return new AuditDetailVO(Ids.text(data.getId()),json.read(data.getActorSnapshot(),AuditActorVO.class),data.getActionCode(),data.getTargetType(),data.getTargetId(),data.getTargetName(),data.getOutcome(),Times.instant(data.getOccurredAt()),data.getReason(),summary.getChanges(),Ids.text(data.getSecurityVersion()),data.getCommandId(),data.getBusinessRequestId(),summary.getRoleSnapshots());
    }
    public List<RoleVersionRefVO> recoveryRoles(long userId,long auditId) {
        var data=mapper.find(auditId);
        if(data==null || !data.getTargetType().equals("user") || !data.getTargetId().equals(Long.toString(userId)) || !data.getOutcome().equals("SUCCESS")) throw BizIllegalException.missing();
        var before=data.getBeforeSummary()==null?null:json.read(data.getBeforeSummary(),AuditSummaryVO.class);
        var after=data.getAfterSummary()==null?null:json.read(data.getAfterSummary(),AuditSummaryVO.class);
        if(before!=null && !before.getRoleSnapshots().isEmpty()) return before.getRoleSnapshots();
        if(after!=null && !after.getRoleSnapshots().isEmpty()) return after.getRoleSnapshots();
        throw BizIllegalException.state("该记录没有可恢复的角色分配依据");
    }
}
