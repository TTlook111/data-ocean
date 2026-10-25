package com.dataocean.module.fieldsemantic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.fieldsemantic.entity.dto.GovernanceFeedbackDTO;
import com.dataocean.module.fieldsemantic.entity.po.*;
import com.dataocean.module.fieldsemantic.entity.query.GovernancePageQuery;
import com.dataocean.module.fieldsemantic.mapper.*;
import com.dataocean.module.fieldsemantic.service.*;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.query.entity.po.QueryTask;
import com.dataocean.module.query.service.IamS1QueryService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class GovernanceFeedbackServiceImpl implements GovernanceFeedbackService {
    private final GovernanceFeedbackMapper mapper;
    private final GovernanceFieldUsageMapper usages;
    private final GovernanceConfidenceEventMapper events;
    private final QueryTaskMapper tasks;
    private final IamS1QueryService queries;
    private final IamS1AdminGuard guard;
    private final GovernanceTaskService governanceTasks;
    private final FieldSemanticService semantic;
    private final GovernanceAuditService audit;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final FieldGovernanceProgressService progress;
    @Override public Map<String,Object> own(String taskId,Long userId){
        var task=tasks.selectOne(new LambdaQueryWrapper<QueryTask>().eq(QueryTask::getTaskId,taskId));
        if(task==null||!Objects.equals(task.getUserId(),userId))throw new BusinessException(403,"无权读取该问数的反馈");
        queries.get(taskId,userId);
        var row=mapper.selectOne(new LambdaQueryWrapper<GovernanceFeedback>().eq(GovernanceFeedback::getTaskId,taskId).eq(GovernanceFeedback::getUserId,userId));
        return row==null?Map.of("status","NOT_SUBMITTED"):ownView(row);
    }
    private Map<String,Object> ownView(GovernanceFeedback row){
        Map<String,Object> result=new LinkedHashMap<>();result.put("id",row.getId());result.put("status",row.getStatus());result.put("feedbackType",row.getFeedbackType());
        result.put("reasonCode",row.getReasonCode());result.put("comment",row.getComment());result.put("rowVersion",row.getRowVersion());result.put("followupStatus",row.getFollowupStatus());return result;
    }
    @Override public List<Map<String,Object>> history(Long id,Long userId){
        var row=mapper.selectById(id);if(row==null)throw new BusinessException(404,"反馈不存在");
        guard.requireDatasourceFunction(userId,"governance:field:view",row.getDatasourceId());return audit.history("FEEDBACK",id);
    }
    @Override @Transactional public Map<String,Object> submit(String taskId,Long userId,GovernanceFeedbackDTO request) {
        queries.get(taskId,userId);
        mapper.lockTask(taskId);
        var task=tasks.selectOne(new LambdaQueryWrapper<QueryTask>().eq(QueryTask::getTaskId,taskId));
        if(task==null||!Objects.equals(task.getUserId(),userId)||!"COMPLETED".equals(task.getStatus()))throw new BusinessException(403,"只能评价自己已完成的问数结果");
        if(!Set.of("LIKE","DISLIKE").contains(request.getFeedbackType()))throw new BusinessException("评价类型无效");
        if(request.getReasonCode()==null)request.setReasonCode("UNKNOWN");
        GovernanceFieldUsage target=null;
        if(request.getColumnMetaId()!=null) target=requireUsed(task,request.getColumnMetaId());
        var row=mapper.selectOne(new LambdaQueryWrapper<GovernanceFeedback>().eq(GovernanceFeedback::getTaskId,taskId).eq(GovernanceFeedback::getUserId,userId));
        if(row!=null&&Objects.equals(row.getFeedbackType(),request.getFeedbackType())&&Objects.equals(row.getReasonCode(),request.getReasonCode())
                &&Objects.equals(row.getColumnMetaId(),request.getColumnMetaId())&&Objects.equals(row.getComment(),request.getComment()))return ownView(row);
        if(row==null){row=new GovernanceFeedback();row.setTaskId(taskId);row.setUserId(userId);row.setDatasourceId(task.getDatasourceId());row.setRowVersion(1);}
        else { events.update(null,new LambdaUpdateWrapper<GovernanceConfidenceEvent>().eq(GovernanceConfidenceEvent::getFeedbackId,row.getId())
                    .eq(GovernanceConfidenceEvent::getActive,1).set(GovernanceConfidenceEvent::getActive,0));row.setRowVersion(row.getRowVersion()+1); }
        row.setFeedbackType(request.getFeedbackType());row.setReasonCode(request.getReasonCode());row.setComment(request.getComment());
        row.setColumnMetaId(target==null?null:target.getColumnMetaId());row.setDefinitionId(target==null?null:target.getDefinitionId());row.setRevisionId(target==null?null:target.getRevisionId());
        row.setStatus(target!=null&&request.getReasonCode().startsWith("FIELD_")?"PENDING_REVIEW":"PENDING_ATTRIBUTION");row.setUpdatedAt(LocalDateTime.now());
        row.setReviewedBy(null);row.setReviewNote(null);row.setFollowupStatus("NONE");
        if(row.getId()==null)mapper.insert(row);
        else mapper.update(null,new LambdaUpdateWrapper<GovernanceFeedback>().eq(GovernanceFeedback::getId,row.getId())
                .set(GovernanceFeedback::getFeedbackType,row.getFeedbackType()).set(GovernanceFeedback::getReasonCode,row.getReasonCode())
                .set(GovernanceFeedback::getComment,row.getComment()).set(GovernanceFeedback::getColumnMetaId,row.getColumnMetaId())
                .set(GovernanceFeedback::getDefinitionId,row.getDefinitionId()).set(GovernanceFeedback::getRevisionId,row.getRevisionId())
                .set(GovernanceFeedback::getStatus,row.getStatus()).set(GovernanceFeedback::getFollowupStatus,"NONE").set(GovernanceFeedback::getRowVersion,row.getRowVersion())
                .set(GovernanceFeedback::getReviewedBy,null).set(GovernanceFeedback::getReviewNote,null).set(GovernanceFeedback::getResolutionJson,null).set(GovernanceFeedback::getUpdatedAt,row.getUpdatedAt()));
        audit.record("FEEDBACK",row.getId(),"SUBMITTED",row,userId);
        queries.feedback(taskId,userId,request.getFeedbackType());return ownView(row);
    }
    private GovernanceFieldUsage requireUsed(QueryTask task,Long columnId){
        var target=usages.selectOne(new LambdaQueryWrapper<GovernanceFieldUsage>().eq(GovernanceFieldUsage::getTaskId,task.getTaskId())
                .eq(GovernanceFieldUsage::getColumnMetaId,columnId));
        if(target==null||!Objects.equals(target.getDatasourceId(),task.getDatasourceId())
                ||!GovernanceFieldProfileServiceImpl.usedFields(task).contains(target.getFieldName()))
            throw new BusinessException(409,"该字段没有实际使用证据，不能承担本次评价");
        return target;
    }
    @Override public Map<String,Object> list(Long ds,Long userId,GovernancePageQuery query){
        guard.requireDatasourceFunction(userId,"governance:field:view",ds);
        var page=mapper.selectPage(query.toMpPage(),new LambdaQueryWrapper<GovernanceFeedback>().eq(GovernanceFeedback::getDatasourceId,ds)
                .orderByDesc(GovernanceFeedback::getId));
        return Map.of("records",page.getRecords().stream().map(this::view).toList(),"total",page.getTotal());
    }
    @Override @Transactional public void review(Long id,Integer version,boolean approved,Long columnId,String reasonCode,String note,Long userId){
        review(id,version,approved,columnId,reasonCode,note,userId,Map.of());
    }
    @Override @Transactional public void review(Long id,Integer version,boolean approved,Long columnId,String reasonCode,String note,Long userId,Map<String,String> acceptance){
        var row=mapper.selectById(id);if(row==null)throw new BusinessException(404,"反馈不存在");
        guard.requireDatasourceFunction(userId,"governance:field:manage",row.getDatasourceId());mapper.lockTask(row.getTaskId());
        row=mapper.selectById(id);
        if(!Objects.equals(row.getRowVersion(),version)||!Set.of("PENDING_REVIEW","PENDING_ATTRIBUTION").contains(row.getStatus()))throw new BusinessException(409,"反馈已处理或版本变化");
        if(note==null||note.isBlank())throw new BusinessException("请填写归因依据及审核意见");
        var task=tasks.selectOne(new LambdaQueryWrapper<QueryTask>().eq(QueryTask::getTaskId,row.getTaskId()));
        if(task==null||!"COMPLETED".equals(task.getStatus()))throw new BusinessException(409,"查询证据不完整");
        boolean fieldReason=Set.of("FIELD_MEANING","FIELD_USAGE").contains(Objects.toString(reasonCode,""));
        GovernanceFieldUsage target=null;
        if(approved&&fieldReason){
            if("DISLIKE".equals(row.getFeedbackType())&&(acceptance==null||Objects.toString(acceptance.get("scenario"),"").isBlank()||Objects.toString(acceptance.get("expectedResult"),"").isBlank()))
                throw new BusinessException("确认字段问题前请填写复现情景和预期结果，作为后续修复验收标准");
            if(columnId==null)throw new BusinessException("字段归因需选择实际使用字段");
            target=requireUsed(task,columnId);
            if(target.getDefinitionId()==null||target.getRevisionId()==null)throw new BusinessException(409,"该字段缺少任务固定的已审核语义版本，保留结果评价但不能计分");
        }
        var update=new LambdaUpdateWrapper<GovernanceFeedback>().eq(GovernanceFeedback::getId,id).eq(GovernanceFeedback::getRowVersion,version)
                .set(GovernanceFeedback::getStatus,!approved?"REJECTED":fieldReason?"APPLIED":"REVIEWED")
                .set(GovernanceFeedback::getReviewedBy,userId).set(GovernanceFeedback::getReviewNote,note)
                .set(GovernanceFeedback::getReasonCode,Objects.toString(reasonCode,"UNKNOWN")).set(GovernanceFeedback::getRowVersion,version+1)
                .set(GovernanceFeedback::getUpdatedAt,LocalDateTime.now());
        if(target!=null)update.set(GovernanceFeedback::getColumnMetaId,target.getColumnMetaId()).set(GovernanceFeedback::getDefinitionId,target.getDefinitionId()).set(GovernanceFeedback::getRevisionId,target.getRevisionId());
        if(approved&&fieldReason&&"DISLIKE".equals(row.getFeedbackType()))update.set(GovernanceFeedback::getResolutionJson,write(Map.of("problem",note,"scenario",acceptance.get("scenario"),"expectedResult",acceptance.get("expectedResult"))));
        if(mapper.update(null,update)!=1)throw new BusinessException(409,"反馈被并发修改");
        audit.record("FEEDBACK",id,approved?"CONFIRMED":"REJECTED",Map.of("expectedVersion",version,"reasonCode",Objects.toString(reasonCode,"UNKNOWN"),"note",note,"columnMetaId",Objects.toString(columnId,"")),userId);
        if(target!=null){
            var event=new GovernanceConfidenceEvent();event.setFeedbackId(id);event.setFeedbackVersion(version+1);
            event.setDefinitionId(target.getDefinitionId());event.setRevisionId(target.getRevisionId());event.setDirection(row.getFeedbackType());
            event.setEvidenceKey(evidenceKey(row,task,target));
            event.setEvidencePolicyVersion(2);
            event.setActive(1);event.setOperatorId(userId);event.setCreatedAt(LocalDateTime.now());events.insert(event);
            var current=semantic.evaluate(target.getColumnMetaId(),target.getSnapshotId());
            boolean followup="DISLIKE".equals(row.getFeedbackType())&&Objects.equals(current.revisionId(),target.getRevisionId());
            if(followup)
                governanceTasks.queueFeedback(target.getColumnMetaId(),target.getSnapshotId(),target.getRevisionId(),userId);
            mapper.update(null,new LambdaUpdateWrapper<GovernanceFeedback>().eq(GovernanceFeedback::getId,id).eq(GovernanceFeedback::getRowVersion,version+1)
                    .set(GovernanceFeedback::getFollowupStatus,followup?"QUEUED":"DISLIKE".equals(row.getFeedbackType())?"ALREADY_CHANGED":"NONE"));
        }
    }
    private Map<String,Object> view(GovernanceFeedback row){
        Map<String,Object> result=new LinkedHashMap<>();result.put("id",row.getId());result.put("taskId",row.getTaskId());
        result.put("feedbackType",row.getFeedbackType());result.put("reasonCode",row.getReasonCode());result.put("comment",row.getComment());
        result.put("columnMetaId",row.getColumnMetaId());result.put("revisionId",row.getRevisionId());result.put("status",row.getStatus());
        result.put("rowVersion",row.getRowVersion());result.put("reviewNote",row.getReviewNote());result.put("followupStatus",row.getFollowupStatus());
        result.put("resolution",row.getResolutionJson()==null?Map.of():read(row.getResolutionJson()));
        if(row.getColumnMetaId()!=null){var usage=usages.selectOne(new LambdaQueryWrapper<GovernanceFieldUsage>().eq(GovernanceFieldUsage::getTaskId,row.getTaskId()).eq(GovernanceFieldUsage::getColumnMetaId,row.getColumnMetaId()));
            if(usage!=null){var evaluation=semantic.evaluate(usage.getColumnMetaId(),usage.getSnapshotId());result.put("currentRevisionId",evaluation.revisionId());}}
        // 仅提供实际使用字段的安全标识，不返回查询业务行或其他用户的结果内容。
        var task=tasks.selectOne(new LambdaQueryWrapper<QueryTask>().eq(QueryTask::getTaskId,row.getTaskId()));
        List<Map<String,Object>> targets=new ArrayList<>();if(task!=null) {
            Set<String> used=GovernanceFieldProfileServiceImpl.usedFields(task);
            for(var usage:usages.selectList(new LambdaQueryWrapper<GovernanceFieldUsage>().eq(GovernanceFieldUsage::getTaskId,row.getTaskId())))
                if(used.contains(usage.getFieldName()))targets.add(Map.of("columnMetaId",usage.getColumnMetaId(),"fieldName",usage.getFieldName()));
        }result.put("targets",targets);return result;
    }
    private String evidenceKey(GovernanceFeedback row,QueryTask task,GovernanceFieldUsage target){
        try {
            if(task.getResultSql()==null||task.getResultSql().isBlank())throw new BusinessException("缺少最终 SQL 证据，不能计分");
            String normalized=net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(task.getResultSql()).toString();
            String raw="SQL_CASE_V2|"+task.getDatasourceId()+"|"+task.getActiveMetadataSnapshotId()+"|"+normalized+"|"+target.getDefinitionId()+"|"+target.getRevisionId();
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }catch(BusinessException exception){throw exception;}catch(Exception exception){throw new BusinessException("最终 SQL 证据无法规范化，保留评价但不能计分");}
    }
    @Override @Transactional public void verifyResolution(Long id,com.dataocean.module.fieldsemantic.entity.dto.GovernanceFeedbackVerificationDTO request,Long userId){
        var row=mapper.selectById(id);if(row==null)throw new BusinessException(404,"反馈不存在");
        guard.requireDatasourceFunction(userId,"governance:field:manage",row.getDatasourceId());mapper.lockTask(row.getTaskId());row=mapper.selectById(id);
        if(!Objects.equals(request.getExpectedVersion(),row.getRowVersion())||!"APPLIED".equals(row.getStatus())||!"AWAITING_VERIFICATION".equals(row.getFollowupStatus()))throw new BusinessException(409,"反馈或复查阶段已变化");
        if(!request.isConfirmed()||request.getActualResult()==null||request.getActualResult().isBlank()||request.getEvidenceReference()==null||request.getEvidenceReference().isBlank())throw new BusinessException("请填写实际结果、验收证据引用并明确确认人工核验");
        var resolution=read(row.getResolutionJson());
        if(Objects.toString(resolution.get("scenario"),"").isBlank()||!Objects.equals(Objects.toString(resolution.get("expectedResult"),"").trim(),request.getActualResult().trim()))throw new BusinessException("实际结果不符合原验收标准，问题保持待验证；不能修改预期结果后结案");
        var usage=requireUsed(tasks.selectOne(new LambdaQueryWrapper<QueryTask>().eq(QueryTask::getTaskId,row.getTaskId())),row.getColumnMetaId());
        var state=progress.progress(row.getColumnMetaId(),usage.getSnapshotId(),userId);
        if(!"ROUND_COMPLETED".equals(state.stage())||!Objects.equals(state.revisionId(),request.getTargetRevisionId())||Objects.equals(state.revisionId(),row.getRevisionId()))throw new BusinessException(409,"修复版本没有当前有效复查证据，请重新核对");
        resolution.put("verification",Map.of("type","HUMAN_VERIFIED","revisionId",state.revisionId(),"checkBatchId",state.checkBatchId(),"actualResult",request.getActualResult(),"reference",request.getEvidenceReference(),"verifiedBy",userId));
        if(mapper.update(null,new LambdaUpdateWrapper<GovernanceFeedback>().eq(GovernanceFeedback::getId,id).eq(GovernanceFeedback::getRowVersion,request.getExpectedVersion()).eq(GovernanceFeedback::getFollowupStatus,"AWAITING_VERIFICATION")
                .set(GovernanceFeedback::getFollowupStatus,"VERIFIED").set(GovernanceFeedback::getResolutionJson,write(resolution)).set(GovernanceFeedback::getRowVersion,request.getExpectedVersion()+1))!=1)throw new BusinessException(409,"反馈被并发修改");
        audit.record("FEEDBACK",id,"RESOLUTION_VERIFIED",resolution,userId);
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception exception){throw new BusinessException("反馈验收资料格式无效");}}
    private Map<String,Object> read(String value){try{if(value==null)return new LinkedHashMap<>();return json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception exception){throw new BusinessException("反馈验收资料格式无效");}}
}
