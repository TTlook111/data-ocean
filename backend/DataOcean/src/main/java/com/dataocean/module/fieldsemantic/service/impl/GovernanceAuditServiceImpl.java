package com.dataocean.module.fieldsemantic.service.impl;
import com.dataocean.module.fieldsemantic.entity.po.GovernanceAuditEvent;
import com.dataocean.module.fieldsemantic.mapper.GovernanceAuditEventMapper;
import com.dataocean.module.fieldsemantic.service.GovernanceAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service @RequiredArgsConstructor
public class GovernanceAuditServiceImpl implements GovernanceAuditService {
    private final GovernanceAuditEventMapper mapper;
    private final ObjectMapper json;
    @Override public java.util.List<java.util.Map<String,Object>> history(String type,Long id){
        var result=new java.util.ArrayList<java.util.Map<String,Object>>();
        for(var event:mapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GovernanceAuditEvent>()
                .eq(GovernanceAuditEvent::getObjectType,type).eq(GovernanceAuditEvent::getObjectId,id).orderByDesc(GovernanceAuditEvent::getId).last("LIMIT 100"))){
            java.util.Map<String,Object> row=new java.util.LinkedHashMap<>();row.put("id",event.getId());row.put("action",event.getAction());
            row.put("operatorId",event.getOperatorId());row.put("createdAt",event.getCreatedAt());
            try{row.put("payload",json.readValue(event.getPayloadJson(),new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,Object>>(){}));}
            catch(Exception exception){throw new com.dataocean.common.exception.BusinessException("审计证据格式异常，请联系维护人员核对");}
            result.add(row);
        }return result;
    }
    @Override @Transactional
    public void record(String type,Long id,String action,Object payload,Long userId){
        var event=new GovernanceAuditEvent();event.setObjectType(type);event.setObjectId(id);event.setAction(action);event.setOperatorId(userId);event.setCreatedAt(LocalDateTime.now());
        try{event.setPayloadJson(json.writeValueAsString(payload));}
        catch(Exception exception){throw new IllegalStateException("治理审计证据序列化失败",exception);}
        mapper.insert(event);
    }
}
