package com.dataocean.module.fieldsemantic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.fieldsemantic.entity.po.*;
import com.dataocean.module.fieldsemantic.mapper.*;
import com.dataocean.module.fieldsemantic.service.*;
import com.dataocean.module.fieldtag.entity.po.FieldTag;
import com.dataocean.module.fieldtag.mapper.FieldTagMapper;
import com.dataocean.module.query.entity.po.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.query.service.IamS1QueryService;
import com.dataocean.module.permission.s1.service.IamS1UserResourceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class GovernanceFieldProfileServiceImpl implements GovernanceFieldProfileService {
    private final QueryTaskMapper taskMapper;
    private final IamS1QueryService queries;
    private final IamS1UserResourceService userResources;
    private final FieldSemanticService semantic;
    private final GovernanceFieldUsageMapper usages;
    private final GovernanceConfidenceEventMapper events;
    private final FieldTagMapper tags;
    private final ObjectMapper json;
    private final com.dataocean.module.metadata.mapper.DbColumnMetaMapper columnMapper;
    private final com.dataocean.module.permission.s1.support.IamS1AdminGuard guard;
    private final GovernanceAuditService audit;

    @Override public Map<String,Object> evidence(Long snapshotId,Long columnId,Long userId,com.dataocean.module.fieldsemantic.entity.query.GovernancePageQuery query){
        var profile=current(snapshotId,columnId,userId);
        if(profile.get("revisionId")==null)return Map.of("records",List.of(),"total",0L);
        var evaluation=semantic.evaluate(columnId,snapshotId);
        var active=events.selectList(new LambdaQueryWrapper<GovernanceConfidenceEvent>().eq(GovernanceConfidenceEvent::getDefinitionId,evaluation.definitionId())
                .eq(GovernanceConfidenceEvent::getRevisionId,evaluation.revisionId()).eq(GovernanceConfidenceEvent::getActive,1).eq(GovernanceConfidenceEvent::getEvidencePolicyVersion,2));
        var latest=new HashMap<String,Long>();for(var event:active)latest.merge(Objects.toString(event.getEvidenceKey(),"legacy:"+event.getId()),event.getId(),Math::max);
        var page=events.selectPage(query.toMpPage(),new LambdaQueryWrapper<GovernanceConfidenceEvent>()
                .eq(GovernanceConfidenceEvent::getDefinitionId,evaluation.definitionId()).eq(GovernanceConfidenceEvent::getRevisionId,evaluation.revisionId()).orderByDesc(GovernanceConfidenceEvent::getId));
        var records=new ArrayList<Map<String,Object>>();
        for(var event:page.getRecords()){
            Map<String,Object> row=new LinkedHashMap<>();row.put("id",event.getId());row.put("feedbackId",event.getFeedbackId());row.put("direction",event.getDirection());
            row.put("createdAt",event.getCreatedAt());row.put("active",Integer.valueOf(1).equals(event.getActive()));
            boolean qualified="APPROVED".equals(profile.get("reviewStatus"));
            boolean currentPolicy=Integer.valueOf(2).equals(event.getEvidencePolicyVersion());
            boolean contributes=currentPolicy&&qualified&&Integer.valueOf(1).equals(event.getActive())&&Objects.equals(event.getId(),latest.get(Objects.toString(event.getEvidenceKey(),"legacy:"+event.getId())));
            row.put("contributes",contributes);row.put("contributionStatus",!currentPolicy?"LEGACY_POLICY":!qualified?"NOT_QUALIFIED":!Integer.valueOf(1).equals(event.getActive())?"WITHDRAWN":contributes?"CONTRIBUTING":"DUPLICATE");
            String note="反馈已确认，历史审核依据可在反馈操作记录中核对";
            for(var history:audit.history("FEEDBACK",event.getFeedbackId())){
                if("CONFIRMED".equals(history.get("action"))&&history.get("payload") instanceof Map<?,?> payload
                        &&payload.get("expectedVersion") instanceof Number version&&version.intValue()==event.getFeedbackVersion()-1){
                    note=Objects.toString(payload.get("note"),note);break;
                }
            }row.put("note",note);records.add(row);
        }return Map.of("records",records,"total",page.getTotal(),"revisionId",evaluation.revisionId());
    }

    @Override public Map<String,Object> current(Long snapshotId,Long columnId,Long userId){
        var column=columnMapper.selectById(columnId);
        if(column==null||!Objects.equals(column.getSnapshotId(),snapshotId))throw new BusinessException(404,"字段不属于当前快照");
        guard.requireDatasourceFunction(userId,"governance:field:view",column.getDatasourceId());
        var evaluation=semantic.evaluate(columnId,snapshotId);
        long positive=0,negative=0;
        if(evaluation.usable()) { var counts=GovernanceConfidencePolicy.summarize(events.selectList(new LambdaQueryWrapper<GovernanceConfidenceEvent>()
                .eq(GovernanceConfidenceEvent::getDefinitionId,evaluation.definitionId()).eq(GovernanceConfidenceEvent::getRevisionId,evaluation.revisionId())
                .eq(GovernanceConfidenceEvent::getActive,1).eq(GovernanceConfidenceEvent::getEvidencePolicyVersion,2)));positive=counts.positive();negative=counts.negative();}
        Map<String,Object> result=new LinkedHashMap<>();result.put("columnMetaId",columnId);result.put("fieldName",column.getTableName()+"."+column.getColumnName());
        result.put("revisionId",evaluation.revisionId());result.put("reviewStatus",evaluation.usable()?"APPROVED":"UNREVIEWED");
        result.put("score",GovernanceConfidencePolicy.score(positive,negative));result.put("evidenceCount",positive+negative);
        result.put("positiveCount",positive);result.put("negativeCount",negative);result.put("tags",tags.selectList(new LambdaQueryWrapper<FieldTag>().eq(FieldTag::getColumnMetaId,columnId)).stream().map(FieldTag::getTagName).toList());
        return result;
    }

    @Override public Map<String,Object> read(String taskId,List<String> fields) {
        if(fields==null||fields.isEmpty()||fields.size()>300)throw new BusinessException("字段档案范围为空或过大");
        var task=taskMapper.selectOne(new LambdaQueryWrapper<QueryTask>().eq(QueryTask::getTaskId,taskId));
        if(task==null||!"PROCESSING".equals(task.getStatus()))throw new BusinessException(409,"查询任务已结束或不存在");
        queries.get(taskId,task.getUserId());
        var catalog=userResources.candidateCatalog(task.getUserId(),task.getDatasourceId(),task.getActiveMetadataSnapshotId());
        if(!Objects.equals(catalog.activeMetadataSnapshotId(),task.getActiveMetadataSnapshotId())||!Objects.equals(catalog.permissionRevision(),task.getPermissionRevision()))
            throw new BusinessException(409,"查询来源或权限已变化");
        var allowed=new HashMap<String,Long>();
        for(var table:catalog.tables())for(var column:table.columns())
            if(!"HIDDEN".equals(column.protectionLevel()))allowed.put(table.tableName()+"."+column.columnName(),column.columnMetaId());
        var result=new ArrayList<Map<String,Object>>();
        for(String field:new LinkedHashSet<>(fields)) {
            Long columnId=allowed.get(field);if(columnId==null)throw new BusinessException(403,"字段档案包含无权或无效资源");
            var existing=usages.selectOne(new LambdaQueryWrapper<GovernanceFieldUsage>().eq(GovernanceFieldUsage::getTaskId,taskId)
                    .eq(GovernanceFieldUsage::getColumnMetaId,columnId));
            var evaluation=semantic.evaluate(columnId,task.getActiveMetadataSnapshotId());
            boolean exact=evaluation.usable()&&pinned(task,evaluation);
            if(existing!=null){
                if(existing.getRevisionId()!=null&&(!exact||!Objects.equals(existing.getRevisionId(),evaluation.revisionId())))
                    throw new BusinessException(409,"任务引用的字段知识已失效，请重新发起问数");
                result.add(readMap(existing.getProfileJson()));continue;
            }
            Map<String,Object> profile=new LinkedHashMap<>();profile.put("columnMetaId",columnId);profile.put("fieldName",field);
            profile.put("snapshotId",task.getActiveMetadataSnapshotId());profile.put("definitionId",exact?evaluation.definitionId():null);
            profile.put("revisionId",exact?evaluation.revisionId():null);profile.put("reviewStatus",exact?"APPROVED":"UNREVIEWED");
            profile.put("tags",tags.selectList(new LambdaQueryWrapper<FieldTag>().eq(FieldTag::getColumnMetaId,columnId)).stream().map(FieldTag::getTagName).toList());
            long positive=0,negative=0;
            if(exact){var counts=GovernanceConfidencePolicy.summarize(events.selectList(new LambdaQueryWrapper<GovernanceConfidenceEvent>()
                    .eq(GovernanceConfidenceEvent::getDefinitionId,evaluation.definitionId()).eq(GovernanceConfidenceEvent::getRevisionId,evaluation.revisionId())
                    .eq(GovernanceConfidenceEvent::getActive,1).eq(GovernanceConfidenceEvent::getEvidencePolicyVersion,2)));positive=counts.positive();negative=counts.negative();}
            profile.put("policyVersion",2);profile.put("evidenceBasis","NORMALIZED_SQL_CASES");profile.put("score",GovernanceConfidencePolicy.score(positive,negative));profile.put("evidenceCount",positive+negative);
            profile.put("evidenceStatus",positive+negative==0?"INSUFFICIENT_EVIDENCE":"HAS_EVIDENCE");profile.put("readAt",LocalDateTime.now().toString());
            var usage=new GovernanceFieldUsage();usage.setTaskId(taskId);usage.setDatasourceId(task.getDatasourceId());usage.setSnapshotId(task.getActiveMetadataSnapshotId());
            usage.setColumnMetaId(columnId);usage.setDefinitionId(exact?evaluation.definitionId():null);usage.setRevisionId(exact?evaluation.revisionId():null);
            usage.setFieldName(field);usage.setProfileJson(write(profile));usage.setCreatedAt(LocalDateTime.now());
            try{usages.insert(usage);}catch(org.springframework.dao.DuplicateKeyException exception){
                var frozen=usages.selectOne(new LambdaQueryWrapper<GovernanceFieldUsage>().eq(GovernanceFieldUsage::getTaskId,taskId).eq(GovernanceFieldUsage::getColumnMetaId,columnId));
                if(frozen==null)throw exception;profile=readMap(frozen.getProfileJson());
            }
            result.add(profile);
        }
        return Map.of("profiles",result);
    }
    private boolean pinned(QueryTask task,com.dataocean.module.fieldsemantic.service.FieldSemanticEvaluation evaluation) {
        try{
            if(task.getFieldSemanticRefsJson()==null)return false;
            List<Map<String,Object>> refs=json.readValue(task.getFieldSemanticRefsJson(),new TypeReference<>(){});
            for(var ref:refs){var ids=json.convertValue(ref.get("factSourceIds"),new TypeReference<List<String>>(){});
                if(ids!=null&&ids.size()==4&&ids.get(0).equals(String.valueOf(evaluation.definitionId()))&&ids.get(1).equals(String.valueOf(evaluation.revisionId()))
                        &&ids.get(2).equals(String.valueOf(evaluation.reviewId()))&&ids.get(3).equals(String.valueOf(evaluation.bindingId()))
                        &&semantic.membershipValid(task.getDatasourceId(),task.getActiveMetadataSnapshotId(),ids))return true;}
            return false;
        }catch(Exception exception){throw new BusinessException(409,"字段知识引用无效，不能读取其他版本分数");}
    }
    @Override public List<Map<String,Object>> feedbackTargets(String taskId,Long userId){
        queries.get(taskId,userId);
        var task=taskMapper.selectOne(new LambdaQueryWrapper<QueryTask>().eq(QueryTask::getTaskId,taskId));
        if(task==null||!Objects.equals(task.getUserId(),userId)||!"COMPLETED".equals(task.getStatus()))throw new BusinessException(403,"没有可反馈的完成结果");
        Set<String> used=usedFields(task);
        return usages.selectList(new LambdaQueryWrapper<GovernanceFieldUsage>().eq(GovernanceFieldUsage::getTaskId,taskId)).stream()
                .filter(u->used.contains(u.getFieldName())).map(u->readMap(u.getProfileJson())).toList();
    }
    public static Set<String> usedFields(QueryTask task){
        try{
            var json=new ObjectMapper();Set<String> result=new HashSet<>();
            if(task.getIamResourceRequest()!=null){List<Map<String,Object>> tables=json.readValue(task.getIamResourceRequest(),new TypeReference<>(){});
                for(var table:tables){String name=String.valueOf(table.get("tableName"));
                    Object referenced=table.get("referencedColumns");if(referenced instanceof List<?> cols)for(Object c:cols)result.add(name+"."+c);
                    Object usages=table.get("columnUsages");if(usages instanceof Map<?,?> cols)for(Object c:cols.keySet())result.add(name+"."+c);}}
            return result;
        }catch(Exception exception){throw new BusinessException(409,"实际使用字段证据无效");}
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception exception){throw new BusinessException("字段档案序列化失败");}}
    private Map<String,Object> readMap(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(Exception exception){throw new BusinessException("字段档案记录无效");}}
}
