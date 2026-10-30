package com.dataocean.module.fieldsemantic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.fieldsemantic.entity.po.GovernanceTask;
import com.dataocean.module.fieldsemantic.mapper.GovernanceTaskMapper;
import com.dataocean.module.fieldsemantic.service.*;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor @Slf4j
public class GovernanceTaskServiceImpl implements GovernanceTaskService {
    private final GovernanceTaskMapper taskMapper;
    private final MetadataSnapshotMapper snapshotMapper;
    private final DbColumnMetaMapper columnMapper;
    private final IamS1AdminGuard guard;
    private final FieldSemanticService semantic;
    private final FieldGovernanceProgressService progress;
    private final BusinessKnowledgeService business;
    private final GovernanceAuditService audit;

    private Long scope(Long snapshotId, Long userId, String function) {
        var snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null || snapshot.getDatasourceId() == null) throw new BusinessException(404, "快照不存在或来源无效");
        guard.requireDatasourceFunction(userId, function, snapshot.getDatasourceId());
        return snapshot.getDatasourceId();
    }
    @Override @Transactional
    public Map<String, Object> discover(Long snapshotId, Long userId, boolean aiFirst) {
        Long datasourceId = scope(snapshotId, userId, "governance:check");
        guard.requireDatasourceFunction(userId, "governance:field:manage", datasourceId);
        guard.requireDatasourceFunction(userId,"knowledge:manage",datasourceId);
        guard.requireDatasourceFunction(userId,"knowledge:view",datasourceId);
        long fieldCount=columnMapper.selectCount(new LambdaQueryWrapper<DbColumnMeta>().eq(DbColumnMeta::getDatasourceId,datasourceId).eq(DbColumnMeta::getSnapshotId,snapshotId));
        if(fieldCount>200)throw new BusinessException("当前快照包含 "+fieldCount+" 个字段，超过一键治理的 200 字段预算；请在字段解释或业务知识中按对象治理，本次未创建 AI 任务");
        var columns = columnMapper.selectList(new LambdaQueryWrapper<DbColumnMeta>()
                .eq(DbColumnMeta::getDatasourceId, datasourceId).eq(DbColumnMeta::getSnapshotId, snapshotId)
                .orderByAsc(DbColumnMeta::getId));
        int created = 0;
        for (var column : columns) {
            var evaluation = semantic.evaluateForGovernance(column.getId(), snapshotId);
            if ("NOT_APPLICABLE".equals(evaluation.outcome())) continue;
            if ("ROUND_COMPLETED".equals(progress.progress(column.getId(), snapshotId, userId).stage())) continue;
            if (taskMapper.selectCount(new LambdaQueryWrapper<GovernanceTask>().eq(GovernanceTask::getSnapshotId, snapshotId)
                    .eq(GovernanceTask::getColumnMetaId, column.getId())) > 0) continue;
            var workspace = semantic.workspace(column.getId(), snapshotId, userId);
            GovernanceTask task = new GovernanceTask(); task.setDatasourceId(datasourceId); task.setSnapshotId(snapshotId);
            task.setColumnMetaId(column.getId()); task.setTableName(column.getTableName()); task.setColumnName(column.getColumnName());
            task.setTargetKey("column:"+column.getId());
            task.setRoundNo(1);task.setOrigin("DISCOVERY"); task.setBaseRevisionId(evaluation.revisionId()); task.setExpectedVersion(workspace.getRowVersion());
            boolean draft = Set.of("DRAFT", "PENDING_REVIEW").contains(Objects.toString(workspace.getWorkingStatus(), ""));
            task.setStatus(aiFirst && !draft && !evaluation.usable() ? "QUEUED" : "HUMAN_READY"); task.setReason(evaluation.reason());
            task.setCreatedBy(userId); task.setAttempts(0); task.setUpdatedAt(LocalDateTime.now());
            try { taskMapper.insert(task); created++; }
            catch (org.springframework.dao.DuplicateKeyException exception) { throw new BusinessException(409, "治理范围正在被其他人扫描，请刷新查看已有任务"); }
        }
        for(var draft:business.starterDrafts(snapshotId,userId)){
            if(created>=200)break;
            if("VALID".equals(draft.get("effectiveStatus")))continue;
            String targetKey="business:"+draft.get("knowledgeKey");
            if(taskMapper.selectCount(new LambdaQueryWrapper<GovernanceTask>().eq(GovernanceTask::getSnapshotId,snapshotId).eq(GovernanceTask::getTargetKey,targetKey))>0)continue;
            var task=new GovernanceTask();task.setDatasourceId(datasourceId);task.setSnapshotId(snapshotId);task.setTargetKey(targetKey);
            task.setBusinessRevisionId(((Number)draft.get("id")).longValue());task.setTableName(String.valueOf(draft.get("title")));task.setColumnName(String.valueOf(draft.get("kind")));
            task.setRoundNo(1);task.setOrigin("DISCOVERY");task.setStatus(aiFirst&&"DRAFT".equals(draft.get("status"))&&"SYSTEM".equals(draft.get("origin"))?"QUEUED":"HUMAN_READY");
            task.setCreatedBy(userId);task.setAttempts(0);task.setReason("业务用途、粒度或关系知识需要确认");task.setUpdatedAt(LocalDateTime.now());taskMapper.insert(task);created++;
        }
        return Map.of("created", created, "scanned", columns.size(), "snapshotId", snapshotId);
    }
    @Override
    public Map<String, Object> list(Long snapshotId, Long userId, int page, int size) {
        scope(snapshotId, userId, "governance:field:view");
        var records = taskMapper.selectPage(new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))),
                new LambdaQueryWrapper<GovernanceTask>().eq(GovernanceTask::getSnapshotId, snapshotId).orderByDesc(GovernanceTask::getId));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var task : records.getRecords()) {
            if(task.getBusinessRevisionId()!=null){
                Map<String,Object> stage;
                try{stage=business.progress(task.getBusinessRevisionId(),userId);}catch(BusinessException exception){stage=Map.of("stage","UNAVAILABLE","reason","业务知识不可读取或权限已变化");}
                Map<String,Object> row=new LinkedHashMap<>();row.put("id",task.getId());row.put("businessRevisionId",task.getBusinessRevisionId());row.put("targetType","BUSINESS");
                row.put("tableName","UNAVAILABLE".equals(stage.get("stage"))?"业务知识任务":task.getTableName());row.put("columnName",task.getColumnName());row.put("origin",task.getOrigin());
                row.put("aiStatus",task.getStatus());row.put("stage",stage.get("stage"));row.put("reason","AI_FAILED".equals(task.getStatus())?task.getReason():stage.get("reason"));row.put("nextAction","OPEN_BUSINESS");rows.add(row);continue;
            }
            com.dataocean.module.fieldsemantic.entity.vo.FieldGovernanceProgressVO current;
            try{current=progress.progress(task.getColumnMetaId(),snapshotId,userId);}
            catch(BusinessException exception){current=new com.dataocean.module.fieldsemantic.entity.vo.FieldGovernanceProgressVO(task.getColumnMetaId(),snapshotId,"UNAVAILABLE","VIEW_REASON","字段来源或当前权限已变化，请核对后处理",null,null);}
            boolean completed = GovernanceTaskPolicy.canFinish(task.getOrigin(), task.getBaseRevisionId(), current.revisionId(), current.stage());
            Map<String, Object> row = new LinkedHashMap<>(); row.put("id", task.getId()); row.put("columnMetaId", task.getColumnMetaId());
            row.put("tableName", task.getTableName()); row.put("columnName", task.getColumnName()); row.put("origin", task.getOrigin());row.put("roundNo",task.getRoundNo()==null?1:task.getRoundNo());
            row.put("aiStatus", task.getStatus()); row.put("stage", completed ? "ROUND_COMPLETED" :
                    "FEEDBACK".equals(task.getOrigin()) && "ROUND_COMPLETED".equals(current.stage()) ? "NEEDS_GOVERNANCE" : current.stage());
            row.put("reason", "AI_FAILED".equals(task.getStatus())?task.getReason():current.reason()); row.put("nextAction", current.nextAction()); row.put("candidateId", task.getCandidateId());
            rows.add(row);
        }
        return Map.of("records", rows, "total", records.getTotal(),"aiSummary",Map.of(
                "queued",count(snapshotId,"QUEUED"),"running",count(snapshotId,"AI_RUNNING"),"failed",count(snapshotId,"AI_FAILED")));
    }
    private long count(Long snapshotId,String status){return taskMapper.selectCount(new LambdaQueryWrapper<GovernanceTask>().eq(GovernanceTask::getSnapshotId,snapshotId).eq(GovernanceTask::getStatus,status));}
    @Override @Transactional
    public void retry(Long id, Long userId) {
        var task = taskMapper.selectById(id);
        if (task == null) throw new BusinessException(404, "治理任务不存在");
        if(task.getAttempts()!=null&&task.getAttempts()>=3)throw new BusinessException("本轮 AI 已达到三次执行上限，请人工处理，不会继续消耗调用额度");
        scope(task.getSnapshotId(), userId, "governance:field:manage");
        if(task.getBusinessRevisionId()!=null){
            scope(task.getSnapshotId(),userId,"knowledge:manage");
            int changed=taskMapper.update(null,new LambdaUpdateWrapper<GovernanceTask>().eq(GovernanceTask::getId,id).in(GovernanceTask::getStatus,"AI_FAILED","AI_READY","HUMAN_READY")
                    .set(GovernanceTask::getStatus,"QUEUED").set(GovernanceTask::getCreatedBy,userId).set(GovernanceTask::getUpdatedAt,LocalDateTime.now()));
            if(changed!=1)throw new BusinessException(409,"业务知识任务正在处理");return;
        }
        var current = semantic.workspace(task.getColumnMetaId(), task.getSnapshotId(), userId);
        if (Set.of("DRAFT", "PENDING_REVIEW").contains(Objects.toString(current.getWorkingStatus(), "")))
            throw new BusinessException(409, "已有人工草稿或待审核修订，请先处理，不覆盖为新 AI 内容");
        int changed = taskMapper.update(null, new LambdaUpdateWrapper<GovernanceTask>().eq(GovernanceTask::getId, id)
                .in(GovernanceTask::getStatus, "AI_FAILED", "HUMAN_READY", "AI_READY")
                .set(GovernanceTask::getStatus, "QUEUED").set(GovernanceTask::getCreatedBy, userId)
                .set(GovernanceTask::getExpectedVersion, current.getRowVersion()).set(GovernanceTask::getAttempts, task.getAttempts()==null?0:task.getAttempts())
                .set(GovernanceTask::getExecutionToken,null).set(GovernanceTask::getLockedUntil,null)
                .set(GovernanceTask::getReason, "已请求重新生成候选").set(GovernanceTask::getUpdatedAt, LocalDateTime.now()));
        if (changed != 1) throw new BusinessException(409, "任务正在处理，请刷新后重试");
    }
    @Override @Transactional
    public void queueFeedback(Long columnId, Long snapshotId, Long revisionId, Long userId) {
        var column = columnMapper.selectById(columnId);
        if (column == null || !Objects.equals(column.getSnapshotId(), snapshotId)) throw new BusinessException(409, "反馈字段来源已变化");
        scope(snapshotId, userId, "governance:field:manage");
        var task = taskMapper.selectOne(new LambdaQueryWrapper<GovernanceTask>().eq(GovernanceTask::getSnapshotId, snapshotId)
                .eq(GovernanceTask::getColumnMetaId, columnId));
        if (task != null && "AI_RUNNING".equals(task.getStatus())) throw new BusinessException(409, "字段治理正在运行，请稍后审核反馈");
        var workspace = semantic.workspace(columnId, snapshotId, userId);
        if (task == null) { task = new GovernanceTask(); task.setColumnMetaId(columnId); task.setDatasourceId(column.getDatasourceId());
            task.setTargetKey("column:"+columnId);
            task.setSnapshotId(snapshotId); task.setTableName(column.getTableName()); task.setColumnName(column.getColumnName()); }
        if(task.getId()!=null)audit.record("GOVERNANCE_TASK",task.getId(),"ROUND_SUPERSEDED",task,userId);
        task.setRoundNo(task.getRoundNo()==null?1:task.getRoundNo()+1);task.setOrigin("FEEDBACK"); task.setBaseRevisionId(revisionId); task.setCreatedBy(userId);
        task.setExpectedVersion(workspace.getRowVersion()); task.setStatus("HUMAN_READY"); task.setAttempts(0);
        task.setReason("已确认的问数反馈提示字段知识存在问题，需新修订及复查。"); task.setUpdatedAt(LocalDateTime.now());
        if (task.getId() == null) taskMapper.insert(task); else taskMapper.updateById(task);
    }
    @Override
    @org.springframework.scheduling.annotation.Async("governanceExecutor")
    public void processQueued() {
        // 崩溃后的不确定模型调用转人工/显式重试，不在后台无限消耗额度。
        taskMapper.update(null, new LambdaUpdateWrapper<GovernanceTask>().eq(GovernanceTask::getStatus, "AI_RUNNING")
                .lt(GovernanceTask::getLockedUntil, LocalDateTime.now()).set(GovernanceTask::getStatus, "AI_FAILED")
                .set(GovernanceTask::getReason, "后台执行中断或超时，可人工接管或重试"));
        var tasks = taskMapper.selectList(new LambdaQueryWrapper<GovernanceTask>().eq(GovernanceTask::getStatus, "QUEUED")
                .orderByAsc(GovernanceTask::getId).last("LIMIT 2"));
        for (var task : tasks) {
            String executionToken=UUID.randomUUID().toString();
            int claimed = taskMapper.update(null, new LambdaUpdateWrapper<GovernanceTask>().eq(GovernanceTask::getId, task.getId())
                    .eq(GovernanceTask::getStatus, "QUEUED").set(GovernanceTask::getStatus, "AI_RUNNING").set(GovernanceTask::getExecutionToken,executionToken)
                    .set(GovernanceTask::getAttempts,task.getAttempts()==null?1:task.getAttempts()+1)
                    .set(GovernanceTask::getLockedUntil, LocalDateTime.now().plusMinutes(10)));
            if (claimed != 1) continue;
            try {
                if(task.getBusinessRevisionId()!=null){
                    var generated=business.generateFromRevision(task.getBusinessRevisionId(),task.getCreatedBy(),task.getId(),executionToken);
                    taskMapper.update(null,new LambdaUpdateWrapper<GovernanceTask>().eq(GovernanceTask::getId,task.getId()).eq(GovernanceTask::getStatus,"AI_RUNNING").eq(GovernanceTask::getExecutionToken,executionToken)
                            .set(GovernanceTask::getStatus,"AI_READY").set(GovernanceTask::getBusinessRevisionId,((Number)generated.get("id")).longValue())
                            .set(GovernanceTask::getReason,"业务知识候选已就绪，请人工补充并审核").set(GovernanceTask::getUpdatedAt,LocalDateTime.now()));
                    continue;
                }
                var workspace = semantic.workspace(task.getColumnMetaId(), task.getSnapshotId(), task.getCreatedBy());
                if(Set.of("PENDING_CONFIRMATION","NEEDS_REVIEW","UNAVAILABLE").contains(Objects.toString(workspace.getBindingStatus(),""))){
                    taskMapper.update(null,new LambdaUpdateWrapper<GovernanceTask>().eq(GovernanceTask::getId,task.getId()).eq(GovernanceTask::getStatus,"AI_RUNNING").eq(GovernanceTask::getExecutionToken,executionToken)
                            .set(GovernanceTask::getStatus,"HUMAN_READY").set(GovernanceTask::getReason,"当前来源绑定需要人工确认，AI 尚未执行；请先核对来源后再重试")
                            .set(GovernanceTask::getUpdatedAt,LocalDateTime.now()));continue;
                }
                if (!Objects.equals(workspace.getRowVersion(), task.getExpectedVersion())
                        || Set.of("DRAFT", "PENDING_REVIEW").contains(Objects.toString(workspace.getWorkingStatus(), "")))
                    throw new BusinessException(409, "字段已被人工修改，旧任务不再生成候选");
                var result = semantic.generateCandidate(task.getColumnMetaId(), task.getSnapshotId(), task.getExpectedVersion(), task.getCreatedBy(),task.getId(),executionToken);
                Long candidateId=result.getGeneratedCandidateId();
                if(candidateId==null)throw new BusinessException(409,"AI 结果缺少生成记录标识，请在字段工作面核对候选后再处理");
                taskMapper.update(null, new LambdaUpdateWrapper<GovernanceTask>().eq(GovernanceTask::getId, task.getId())
                        .eq(GovernanceTask::getStatus, "AI_RUNNING").eq(GovernanceTask::getExecutionToken,executionToken).set(GovernanceTask::getStatus, "AI_READY")
                        .set(GovernanceTask::getExpectedVersion, result.getRowVersion())
                        .set(GovernanceTask::getCandidateId,candidateId)
                        .set(GovernanceTask::getReason, "AI 首轮候选已保存，等待人工采纳、补充并审核")
                        .set(GovernanceTask::getUpdatedAt, LocalDateTime.now()));
            } catch (Exception exception) {
                log.warn("字段治理首轮失败 taskId={} type={}", task.getId(), exception.getClass().getSimpleName());
                taskMapper.update(null, new LambdaUpdateWrapper<GovernanceTask>().eq(GovernanceTask::getId, task.getId())
                        .eq(GovernanceTask::getStatus, "AI_RUNNING").eq(GovernanceTask::getExecutionToken,executionToken).set(GovernanceTask::getStatus, "AI_FAILED")
                        .set(GovernanceTask::getReason, exception instanceof BusinessException ? exception.getMessage() : "AI 服务暂不可用，可人工接管或重试")
                        .set(GovernanceTask::getUpdatedAt, LocalDateTime.now()));
            }
        }
    }
    @Override @Transactional public void pause(Long snapshotId,Long userId){
        scope(snapshotId,userId,"governance:field:manage");
        int changed=taskMapper.update(null,new LambdaUpdateWrapper<GovernanceTask>().eq(GovernanceTask::getSnapshotId,snapshotId)
                .in(GovernanceTask::getStatus,"QUEUED","AI_RUNNING").set(GovernanceTask::getStatus,"HUMAN_READY")
                .set(GovernanceTask::getExecutionToken,null).set(GovernanceTask::getLockedUntil,null)
                .set(GovernanceTask::getReason,"本轮 AI 已暂停，晚到结果不再写入，可人工处理或逐项重试"));
        audit.record("GOVERNANCE_SCOPE",snapshotId,"AI_PAUSED",Map.of("changed",changed),userId);
    }
}
