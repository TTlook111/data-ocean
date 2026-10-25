package com.dataocean.module.fieldsemantic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.fieldsemantic.entity.dto.BusinessKnowledgeDraftDTO;
import com.dataocean.module.fieldsemantic.entity.po.BusinessKnowledgeRevision;
import com.dataocean.module.fieldsemantic.mapper.BusinessKnowledgeRevisionMapper;
import com.dataocean.module.fieldsemantic.service.BusinessKnowledgeService;
import com.dataocean.module.knowledge.client.PythonKnowledgeClient;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import com.dataocean.module.metadata.mapper.*;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class BusinessKnowledgeServiceImpl implements BusinessKnowledgeService {
    private final BusinessKnowledgeRevisionMapper mapper;
    private final GovernanceExecutionFence executionFence;
    private final MetadataSnapshotMapper snapshots;
    private final DbTableMetaMapper tables;
    private final DbColumnMetaMapper columns;
    private final IamS1AdminGuard guard;
    private final PythonKnowledgeClient python;
    private final ObjectMapper json;
    private final TableRelationMapper relations;
    private final org.springframework.transaction.support.TransactionTemplate transaction;
    private final com.dataocean.module.fieldsemantic.service.GovernanceAuditService audit;
    private final com.dataocean.module.fieldsemantic.service.FieldSemanticService semantic;
    @Override public List<Map<String,Object>> history(Long id,Long userId){
        var row=mapper.selectById(id);if(row==null)throw new BusinessException(404,"业务知识不存在");
        scope(row.getSnapshotId(),userId,"knowledge:view");return audit.history("BUSINESS",id);
    }
    @Override public Map<String,Object> sourceStatus(Long id,Long userId){
        return sourceStatus(id,userId,null);
    }
    @Override public Map<String,Object> sourceStatus(Long id,Long userId,List<String> requestedDependencies){
        var row=mapper.selectById(id);if(row==null)throw new BusinessException(404,"业务知识不存在");scope(row.getSnapshotId(),userId,"knowledge:view");
        Map<String,Object> result=new LinkedHashMap<>();result.put("original",read(row.getSourceJson(),new TypeReference<List<Map<String,Object>>>(){}));
        result.put("sourceChanged",true);result.put("originalHash",row.getSourceHash());
        try{var current=sources(row.getDatasourceId(),row.getSnapshotId(),requestedDependencies==null?dependencies(row):requestedDependencies);String currentHash=hash(write(current));
            result.put("current",current);result.put("currentHash",currentHash);result.put("sourceChanged",!row.getSourceHash().equals(currentHash));}
        catch(BusinessException exception){result.put("current",List.of());result.put("reason",exception.getMessage());}
        return result;
    }

    private BusinessKnowledgeRevision latest(Long ds,Long snapshotId,String key){
        return mapper.selectOne(new LambdaQueryWrapper<BusinessKnowledgeRevision>().eq(BusinessKnowledgeRevision::getDatasourceId,ds)
                .eq(BusinessKnowledgeRevision::getSnapshotId,snapshotId).eq(BusinessKnowledgeRevision::getKnowledgeKey,key)
                .orderByDesc(BusinessKnowledgeRevision::getId).last("LIMIT 1"));
    }
    private BusinessKnowledgeRevision expected(Long ds,Long snapshotId,BusinessKnowledgeDraftDTO request){
        if(request.getKind()==null||!BusinessKnowledgePolicy.KINDS.contains(request.getKind()))throw new BusinessException("业务知识类型无效");
        var row=latest(ds,snapshotId,request.getKind()+":"+request.getKnowledgeKey());
        if(row==null){if(request.getExpectedRevisionId()!=null||request.getExpectedVersion()!=null)throw new BusinessException(409,"基准修订已更新或不属于当前范围，请刷新");}
        else if(!Objects.equals(row.getId(),request.getExpectedRevisionId())||!Objects.equals(row.getRowVersion(),request.getExpectedVersion()))
            throw new BusinessException(409,"已有更新的业务修订或审核阶段，请刷新后再保存");
        return row;
    }
    @Override @Transactional public List<Map<String,Object>> starterDrafts(Long snapshotId,Long userId){
        Long ds=scope(snapshotId,userId,"knowledge:manage");mapper.lockScope(snapshotId);
        if(tables.selectCount(new LambdaQueryWrapper<DbTableMeta>().eq(DbTableMeta::getDatasourceId,ds).eq(DbTableMeta::getSnapshotId,snapshotId))>100
                ||relations.selectCount(new LambdaQueryWrapper<com.dataocean.module.metadata.entity.po.TableRelation>().eq(com.dataocean.module.metadata.entity.po.TableRelation::getDatasourceId,ds).eq(com.dataocean.module.metadata.entity.po.TableRelation::getSnapshotId,snapshotId))>100)
            throw new BusinessException("当前快照超过一键业务治理的 100 表或 100 关系预算，请按业务主题人工补充；本次不启动批量 AI");
        var tableRows=tables.selectList(new LambdaQueryWrapper<DbTableMeta>().eq(DbTableMeta::getDatasourceId,ds).eq(DbTableMeta::getSnapshotId,snapshotId));
        var eligible=tableRows.stream().filter(t->!forbidden(t.getGovernanceStatus())).toList();
        var result=new ArrayList<Map<String,Object>>();
        if(!eligible.isEmpty())result.add(starter(ds,snapshotId,"DATASOURCE","datasource",eligible.size()>300?"数据源业务用途 · 分批资料待确认":"数据源业务用途",
                eligible.stream().limit(300).map(t->"table:"+t.getTableName()).toList(),eligible.size()>300?Map.of("scope","本批结构资料涉及前 300 张表，共 "+eligible.size()+" 张表，整体业务用途需结合业务资料人工确认"):Map.of(),userId));
        for(var table:eligible)result.add(starter(ds,snapshotId,"TABLE","table."+hash(table.getTableName()).substring(0,24),table.getTableName()+" · 用途与粒度",
                List.of("table:"+table.getTableName()),Map.of("description",Objects.toString(table.getTableComment(),"")),userId));
        for(var relation:relations.selectList(new LambdaQueryWrapper<com.dataocean.module.metadata.entity.po.TableRelation>().eq(com.dataocean.module.metadata.entity.po.TableRelation::getDatasourceId,ds)
                .eq(com.dataocean.module.metadata.entity.po.TableRelation::getSnapshotId,snapshotId))){
            String left=relation.getSourceTable()+"."+relation.getSourceColumn(),right=relation.getTargetTable()+"."+relation.getTargetColumn();
            var deps=completeDependencies(ds,snapshotId,List.of("table:"+relation.getSourceTable(),"column:"+left,"table:"+relation.getTargetTable(),"column:"+right));
            try{sources(ds,snapshotId,deps);}catch(BusinessException exception){continue;}
            result.add(starter(ds,snapshotId,"RELATION","relation."+hash(left+"="+right).substring(0,24),relation.getSourceTable()+" ↔ "+relation.getTargetTable(),deps,
                    Map.of("joinCondition",left+" = "+right),userId));
        }
        return result;
    }
    private Map<String,Object> starter(Long ds,Long snapshotId,String kind,String key,String title,List<String> deps,Map<String,String> body,Long userId){
        var existing=latest(ds,snapshotId,kind+":"+key);if(existing!=null)return view(existing);
        var request=new BusinessKnowledgeDraftDTO();request.setKind(kind);request.setKnowledgeKey(key);request.setTitle(title);request.setDependencies(deps);
        var previous=mapper.selectOne(new LambdaQueryWrapper<BusinessKnowledgeRevision>().eq(BusinessKnowledgeRevision::getDatasourceId,ds)
                .eq(BusinessKnowledgeRevision::getKnowledgeKey,kind+":"+key).eq(BusinessKnowledgeRevision::getStatus,"VALID")
                .orderByDesc(BusinessKnowledgeRevision::getId).last("LIMIT 1"));
        request.setBody(previous==null?body:read(previous.getBodyJson(),new TypeReference<Map<String,String>>(){}));
        request.setEvidence(previous==null?"来源快照元数据，仅作候选资料，业务用途和条件需人工确认":previous.getEvidence());
        request.setUnresolvedItems(List.of(previous==null?"请依据业务资料确认用途、粒度及适用条件":"来源快照已变化，请重新确认继承知识的适用性"));
        return insert(snapshotId,request,userId,"SYSTEM");
    }
    @Override public Map<String,Object> generateFromRevision(Long id,Long userId){return generateFromRevision(id,userId,null,null);}
    @Override public Map<String,Object> generateFromRevision(Long id,Long userId,Long governanceTaskId,String executionToken){
        var row=mapper.selectById(id);if(row==null)throw new BusinessException(404,"业务候选不存在");
        scope(row.getSnapshotId(),userId,"knowledge:manage");
        if(!Objects.equals(latest(row.getDatasourceId(),row.getSnapshotId(),row.getKnowledgeKey()).getId(),id)||!"DRAFT".equals(row.getStatus()))
            throw new BusinessException(409,"业务候选已被人工处理，不再执行旧 AI 任务");
        var request=new BusinessKnowledgeDraftDTO();request.setExpectedRevisionId(row.getId());request.setExpectedVersion(row.getRowVersion());request.setKind(row.getKind());request.setKnowledgeKey(row.getKnowledgeKey().substring(row.getKind().length()+1));
        request.setTitle(row.getTitle());request.setBody(read(row.getBodyJson(),new TypeReference<Map<String,String>>(){}));request.setDependencies(dependencies(row));
        request.setEvidence(row.getEvidence());request.setUnresolvedItems(read(row.getUnresolvedItemsJson(),new TypeReference<List<String>>(){}));
        return generate(row.getSnapshotId(),request,userId,governanceTaskId,executionToken);
    }
    @Override public Map<String,Object> progress(Long id,Long userId){
        var row=mapper.selectById(id);if(row==null)throw new BusinessException(404,"业务知识不存在");
        scope(row.getSnapshotId(),userId,"knowledge:view");row=latest(row.getDatasourceId(),row.getSnapshotId(),row.getKnowledgeKey());
        String stage=switch(row.getStatus()){case "DRAFT"->"HUMAN_EDITING";case "PENDING_REVIEW"->"AWAITING_REVIEW";
            case "APPROVED"->"AWAITING_RECHECK";case "NOT_APPLICABLE"->"NOT_APPLICABLE";case "VALID"->valid(row)?"ROUND_COMPLETED":"NEEDS_GOVERNANCE";default->"NEEDS_GOVERNANCE";};
        return Map.of("stage",stage,"revisionId",row.getId(),"reason", "ROUND_COMPLETED".equals(stage)?"业务知识已审核复查，文档和构建状态请在语义知识中分别查看":
                "NOT_APPLICABLE".equals(stage)?"已记录不适用原因，不作为通过或可检索事实":"请在业务知识工作面核对当前修订及待确认事项");
    }

    private Long scope(Long snapshotId, Long userId, String function) {
        var snapshot = snapshots.selectById(snapshotId);
        if (snapshot == null || snapshot.getDatasourceId() == null) throw new BusinessException(404, "快照来源无效");
        guard.requireDatasourceFunction(userId, function, snapshot.getDatasourceId());
        return snapshot.getDatasourceId();
    }
    /** 一次性解析完整依赖，禁止只裁剪受限端点后把其余部分当有效业务知识。 */
    private List<Map<String, Object>> sources(Long ds, Long snapshotId, List<String> dependencies) {
        if (dependencies == null || dependencies.isEmpty() || dependencies.size() > 2000) throw new BusinessException("业务知识缺少完整资源依赖，或单个主题依赖超过 2000 项，请拆分治理");
        var tableRows = tables.selectList(new LambdaQueryWrapper<DbTableMeta>().eq(DbTableMeta::getDatasourceId, ds).eq(DbTableMeta::getSnapshotId, snapshotId));
        var columnRows = columns.selectList(new LambdaQueryWrapper<DbColumnMeta>().eq(DbColumnMeta::getDatasourceId, ds).eq(DbColumnMeta::getSnapshotId, snapshotId));
        var tableByName = new HashMap<String, DbTableMeta>(); tableRows.forEach(t -> tableByName.put(t.getTableName(), t));
        var columnByName = new HashMap<String, DbColumnMeta>(); columnRows.forEach(c -> columnByName.put(c.getTableName()+"."+c.getColumnName(),c));
        var result = new ArrayList<Map<String,Object>>();
        for (String dependency : new TreeSet<>(dependencies)) {
            if (dependency == null) throw new BusinessException("知识依赖格式无效");
            String tableName;
            Map<String,Object> item = new TreeMap<>();
            if (dependency.startsWith("table:")) {
                tableName = dependency.substring(6);
            } else if (dependency.startsWith("column:")) {
                String key = dependency.substring(7); var column = columnByName.get(key);
                if (column == null || forbidden(column.getGovernanceStatus())) throw new BusinessException("业务知识字段不存在或不可用");
                tableName = column.getTableName();
                if (!dependencies.contains("table:"+tableName)) throw new BusinessException("字段依赖缺少所属表");
                item.put("columnId", column.getId()); item.put("columnName", column.getColumnName());
                item.put("dataType", Objects.toString(column.getDataType(), ""));
                item.put("comment", Objects.toString(column.getColumnComment(), ""));
                var context=semantic.reviewedContext(column.getId(),snapshotId);
                if(context==null)context=Map.of("qualified",false);
                if(Boolean.TRUE.equals(context.get("qualified"))){
                    var required=json.convertValue(context.get("dependencies"),new TypeReference<List<String>>(){});
                    if(required==null||!new HashSet<>(dependencies).containsAll(required))throw new BusinessException("字段解释存在其他完整依赖，请补齐关联表字段后再使用该业务知识");
                }
                item.put("reviewedSemantics",new TreeMap<>(context));
            } else throw new BusinessException("知识依赖格式无效");
            var table = tableByName.get(tableName);
            if (table == null || forbidden(table.getGovernanceStatus())) throw new BusinessException("业务知识表不存在或不可用");
            item.put("dependency", dependency); item.put("tableId", table.getId()); item.put("tableName", tableName);
            item.put("tableComment", Objects.toString(table.getTableComment(), "")); item.put("snapshotId", snapshotId);
            result.add(item);
        }
        return result;
    }
    private List<String> completeDependencies(Long ds,Long snapshotId,List<String> initial){
        var result=new TreeSet<>(initial);var pending=new ArrayList<>(initial);
        var byName=new HashMap<String,DbColumnMeta>();columns.selectList(new LambdaQueryWrapper<DbColumnMeta>().eq(DbColumnMeta::getDatasourceId,ds)
                .eq(DbColumnMeta::getSnapshotId,snapshotId)).forEach(c->byName.put("column:"+c.getTableName()+"."+c.getColumnName(),c));
        for(int i=0;i<pending.size();i++){var column=byName.get(pending.get(i));if(column==null)continue;
            var context=semantic.reviewedContext(column.getId(),snapshotId);if(context==null||!Boolean.TRUE.equals(context.get("qualified")))continue;
            var required=json.convertValue(context.get("dependencies"),new TypeReference<List<String>>(){});
            for(String dependency:required)if(result.add(dependency))pending.add(dependency);
            if(result.size()>2000)throw new BusinessException("单个业务关系依赖过大，请按业务主题拆分");
        }return List.copyOf(result);
    }
    private boolean forbidden(String status) { return "BLOCKED".equals(status) || "DEPRECATED".equals(status); }
    @Override public List<Map<String,Object>> list(Long snapshotId, Long userId) {
        Long ds = scope(snapshotId, userId, "knowledge:view");
        return mapper.selectList(new LambdaQueryWrapper<BusinessKnowledgeRevision>().eq(BusinessKnowledgeRevision::getDatasourceId,ds)
                .eq(BusinessKnowledgeRevision::getSnapshotId,snapshotId).orderByDesc(BusinessKnowledgeRevision::getId)).stream().map(this::view).toList();
    }
    private Map<String,Object> view(BusinessKnowledgeRevision row) {
        Map<String,Object> result = new LinkedHashMap<>(); result.put("id",row.getId()); result.put("kind",row.getKind());
        result.put("knowledgeKey",row.getKnowledgeKey()); result.put("title",row.getTitle()); result.put("body",read(row.getBodyJson(),new TypeReference<Map<String,String>>(){}));
        result.put("dependencies",dependencies(row)); result.put("unresolvedItems",read(row.getUnresolvedItemsJson(),new TypeReference<List<String>>(){}));
        result.put("evidence",row.getEvidence()); result.put("status",row.getStatus()); result.put("rowVersion",row.getRowVersion());
        result.put("evidenceConfirmation",row.getEvidenceJson()==null?Map.of():read(row.getEvidenceJson(),new TypeReference<Map<String,Object>>(){}));
        result.put("origin",row.getOrigin()); result.put("reviewNote",row.getReviewNote());
        result.put("effectiveStatus","VALID".equals(row.getStatus())&&!valid(row)?"NEEDS_REVIEW":row.getStatus());return result;
    }
    @Override @Transactional public Map<String,Object> save(Long snapshotId, BusinessKnowledgeDraftDTO request, Long userId) {
        Long ds=scope(snapshotId,userId,"knowledge:manage");mapper.lockScope(snapshotId);var previous=expected(ds,snapshotId,request);
        String currentHash=hash(write(sources(ds,snapshotId,request.getDependencies())));
        if(previous!=null&&!Objects.equals(previous.getSourceHash(),currentHash)&&!Objects.equals(request.getConfirmedSourceHash(),currentHash))
            throw new BusinessException(409,"依赖字段或来源已变化，请先核对来源并明确确认后再保存");
        return insert(snapshotId,request,userId,"MANUAL");
    }
    private Map<String,Object> insert(Long snapshotId, BusinessKnowledgeDraftDTO request, Long userId, String origin) {
        return insert(snapshotId,request,userId,origin,null);
    }
    private Map<String,Object> insert(Long snapshotId, BusinessKnowledgeDraftDTO request, Long userId, String origin,String expectedSourceHash) {
        Long ds = scope(snapshotId,userId,"knowledge:manage");
        if (!BusinessKnowledgePolicy.KINDS.contains(request.getKind())) throw new BusinessException("业务知识类型无效");
        var normalized = new TreeMap<>(request.getBody());
        if(!Set.of("description","scope","grain","joinCondition","cardinality","aggregationNotes","formula","conditions","limitations")
                .containsAll(normalized.keySet()))throw new BusinessException("业务知识包含不支持的内容项");
        if (normalized.size()>20 || normalized.values().stream().anyMatch(v -> v==null || v.length()>16000)) throw new BusinessException("业务知识内容过长或无效");
        var deps = List.copyOf(new TreeSet<>(request.getDependencies()));
        BusinessKnowledgePolicy.requireDependencies(request.getKind(),normalized,deps);
        if(request.getUnresolvedItems()==null||request.getUnresolvedItems().size()>50||request.getUnresolvedItems().stream().anyMatch(i->i==null||i.length()>2000)
                ||request.getEvidence()!=null&&request.getEvidence().length()>8000)throw new BusinessException("业务依据或待确认事项长度无效");
        var source = sources(ds,snapshotId,deps);
        String sourceJson=write(source),sourceHash=hash(sourceJson);
        if(expectedSourceHash!=null&&!expectedSourceHash.equals(sourceHash))throw new BusinessException(409,"生成期间字段定义或来源已变化，旧 AI 候选不写入");
        var row = new BusinessKnowledgeRevision(); row.setDatasourceId(ds); row.setSnapshotId(snapshotId);
        row.setKind(request.getKind()); row.setKnowledgeKey(request.getKind()+":"+request.getKnowledgeKey()); row.setTitle(request.getTitle());
        row.setBodyJson(write(normalized)); row.setDependenciesJson(write(deps)); row.setSourceHash(sourceHash);row.setSourceJson(sourceJson);
        row.setEvidence(request.getEvidence()); row.setUnresolvedItemsJson(write(request.getUnresolvedItems())); row.setOrigin(origin);
        row.setStatus("DRAFT"); row.setCreatedBy(userId); row.setRowVersion(1); row.setCreatedAt(LocalDateTime.now());
        row.setContentHash(hash(render(row)));
        if("MANUAL".equals(origin)&&request.isEvidenceConfirmed()){
            if(request.getEvidenceReference()==null||request.getEvidenceReference().isBlank()||request.getEvidence()==null||request.getEvidence().isBlank())
                throw new BusinessException("依据确认需要提供可定位的资料引用和原始摘录");
            row.setEvidenceJson(write(Map.of("type","HUMAN_ATTESTATION","reference",request.getEvidenceReference().trim(),"excerpt",request.getEvidence(),
                    "confirmedBy",userId,"contentHash",row.getContentHash(),"sourceHash",row.getSourceHash())));
        }
        mapper.insert(row);audit.record("BUSINESS",row.getId(),"DRAFT_CREATED",Map.of("contentHash",row.getContentHash(),"sourceHash",row.getSourceHash(),"origin",origin,"baseRevisionId",Objects.toString(request.getExpectedRevisionId(),"")),userId);
        mapper.update(null,new LambdaUpdateWrapper<BusinessKnowledgeRevision>().eq(BusinessKnowledgeRevision::getDatasourceId,ds).eq(BusinessKnowledgeRevision::getSnapshotId,snapshotId)
                .eq(BusinessKnowledgeRevision::getKnowledgeKey,row.getKnowledgeKey()).ne(BusinessKnowledgeRevision::getId,row.getId()).in(BusinessKnowledgeRevision::getStatus,"DRAFT","PENDING_REVIEW")
                .set(BusinessKnowledgeRevision::getStatus,"SUPERSEDED_DRAFT"));return view(row);
    }
    @Override public Map<String,Object> generate(Long snapshotId, BusinessKnowledgeDraftDTO request, Long userId) {return generate(snapshotId,request,userId,null,null);}
    private Map<String,Object> generate(Long snapshotId,BusinessKnowledgeDraftDTO request,Long userId,Long governanceTaskId,String executionToken){
        Long ds = scope(snapshotId,userId,"knowledge:manage");
        var before=expected(ds,snapshotId,request);Long baseId=before==null?null:before.getId();Integer baseVersion=before==null?null:before.getRowVersion();
        var source = sources(ds,snapshotId,request.getDependencies()); String originalHash=hash(write(source));
        var payload = new LinkedHashMap<String,Object>(); payload.put("kind",request.getKind()); payload.put("title",request.getTitle());
        payload.put("schema",source); payload.put("businessMaterials",request.getBody()); payload.put("evidence",Objects.toString(request.getEvidence(),""));
        var originalUnknowns=new java.util.LinkedHashSet<>(request.getUnresolvedItems());
        if(before!=null&&!before.getSourceHash().equals(originalHash))originalUnknowns.add("字段定义或来源发生变化，请人工核对新的业务口径");
        payload.put("unresolvedItems",List.copyOf(originalUnknowns));
        if(write(payload).length()>200000)throw new BusinessException("AI 候选上下文过大，请缩小主题或人工补充，不会截掉必要依赖");
        var result = python.generateBusinessCandidate(payload);
        if (!originalHash.equals(hash(write(sources(ds,snapshotId,request.getDependencies()))))) throw new BusinessException(409,"生成期间来源已变化，候选未写入");
        request.setBody(json.convertValue(result.get("body"),new TypeReference<Map<String,String>>(){}));
        request.setUnresolvedItems(json.convertValue(result.get("unresolvedItems"),new TypeReference<List<String>>(){}));
        // 模型的自述不具有证据资格。保留调用前的人工资料，AI 新版也不继承人工确认。
        if (request.getBody()==null || request.getUnresolvedItems()==null) throw new BusinessException("AI 候选格式无效");
        originalUnknowns.addAll(request.getUnresolvedItems());request.setUnresolvedItems(List.copyOf(originalUnknowns));
        return executionFence.persist(governanceTaskId,executionToken,userId,()->transaction.execute(status->{
            mapper.lockScope(snapshotId);var current=latest(ds,snapshotId,request.getKind()+":"+request.getKnowledgeKey());
            if(!Objects.equals(baseId,current==null?null:current.getId())||!Objects.equals(baseVersion,current==null?null:current.getRowVersion()))throw new BusinessException(409,"生成期间已有人工新修订或审核变化，旧候选未写入");
            return insert(snapshotId,request,userId,"AI",originalHash);
        }));
    }
    @Override @Transactional public void action(Long id,Integer version,String action,String note,Long userId) {
        var row = mapper.selectById(id); if(row==null) throw new BusinessException(404,"业务知识修订不存在");
        scope(row.getSnapshotId(),userId,"APPROVE".equals(action)||"REJECT".equals(action)?"knowledge:approve":"knowledge:manage");
        if("REVOKE".equals(action))scope(row.getSnapshotId(),userId,"knowledge:approve");
        mapper.lockScope(row.getSnapshotId());
        var latest=mapper.selectOne(new LambdaQueryWrapper<BusinessKnowledgeRevision>().eq(BusinessKnowledgeRevision::getDatasourceId,row.getDatasourceId())
                .eq(BusinessKnowledgeRevision::getSnapshotId,row.getSnapshotId()).eq(BusinessKnowledgeRevision::getKnowledgeKey,row.getKnowledgeKey())
                .orderByDesc(BusinessKnowledgeRevision::getId).last("LIMIT 1"));
        if(!"REVOKE".equals(action)&&!Objects.equals(latest.getId(),id)) throw new BusinessException(409,"已有更新修订，请处理最新版本");
        String next=switch(action) { case "SUBMIT" -> "PENDING_REVIEW"; case "APPROVE" -> "APPROVED";
            case "REJECT" -> "REJECTED"; case "RECHECK" -> "VALID"; case "REVOKE" -> "REVOKED";case "NOT_APPLICABLE"->"NOT_APPLICABLE"; default -> throw new BusinessException("不支持的操作"); };
        String required=switch(action) { case "SUBMIT" -> "DRAFT"; case "APPROVE","REJECT" -> "PENDING_REVIEW";
            case "RECHECK" -> "APPROVED";case "NOT_APPLICABLE"->Set.of("DRAFT","REJECTED").contains(row.getStatus())?row.getStatus():"DRAFT"; default -> "VALID"; };
        if(!required.equals(row.getStatus()) || !Objects.equals(row.getRowVersion(),version)) throw new BusinessException(409,"知识阶段或版本已变化");
        if(Set.of("SUBMIT","APPROVE","RECHECK").contains(action)) {
            requireEvidenceConfirmation(row);
            BusinessKnowledgePolicy.requireDependencies(row.getKind(),read(row.getBodyJson(),new TypeReference<Map<String,String>>(){}),dependencies(row));
            BusinessKnowledgePolicy.requireReady(row.getKind(),read(row.getBodyJson(),new TypeReference<Map<String,String>>(){}),read(row.getUnresolvedItemsJson(),new TypeReference<List<String>>(){}));
            if(row.getEvidence()==null||row.getEvidence().isBlank()) throw new BusinessException("请补充业务依据");
            var source=sources(row.getDatasourceId(),row.getSnapshotId(),dependencies(row));
            if(!row.getSourceHash().equals(hash(write(source)))) throw new BusinessException(409,"字段定义或来源已变化，需要重新修订");
            if(Set.of("RELATION","METRIC").contains(row.getKind()))for(var item:source)
                if(item.containsKey("columnId")&&(!(item.get("reviewedSemantics") instanceof Map<?,?> meaning)||!Boolean.TRUE.equals(meaning.get("qualified"))))
                    throw new BusinessException("请先确认依赖字段的解释及问数知识资格，再提交关系或指标口径");
            if("RELATION".equals(row.getKind())&&dependencies(row).stream().filter(d->d.startsWith("column:")).count()<2) throw new BusinessException("关系知识必须包含完整关联字段依赖");
        }
        if(Set.of("APPROVE","REJECT","REVOKE","NOT_APPLICABLE").contains(action)&&(note==null||note.isBlank())) throw new BusinessException("请填写审核、撤回或不适用原因");
        var update=new LambdaUpdateWrapper<BusinessKnowledgeRevision>().eq(BusinessKnowledgeRevision::getId,id).eq(BusinessKnowledgeRevision::getRowVersion,version)
                .eq(BusinessKnowledgeRevision::getStatus,required).set(BusinessKnowledgeRevision::getStatus,next).set(BusinessKnowledgeRevision::getRowVersion,version+1);
        if(Set.of("APPROVE","REJECT").contains(action)) update.set(BusinessKnowledgeRevision::getReviewedBy,userId).set(BusinessKnowledgeRevision::getReviewNote,note);
        if("NOT_APPLICABLE".equals(action))update.set(BusinessKnowledgeRevision::getReviewNote,note);
        if("RECHECK".equals(action)) update.set(BusinessKnowledgeRevision::getVerifiedHash,row.getContentHash());
        if(mapper.update(null,update)!=1) throw new BusinessException(409,"知识修订并发变化");
        audit.record("BUSINESS",id,action,Map.of("expectedVersion",version,"contentHash",row.getContentHash(),"sourceHash",row.getSourceHash(),"note",Objects.toString(note,"")),userId);
        if("RECHECK".equals(action)) mapper.update(null,new LambdaUpdateWrapper<BusinessKnowledgeRevision>()
                .eq(BusinessKnowledgeRevision::getDatasourceId,row.getDatasourceId()).eq(BusinessKnowledgeRevision::getSnapshotId,row.getSnapshotId())
                .eq(BusinessKnowledgeRevision::getKnowledgeKey,row.getKnowledgeKey()).ne(BusinessKnowledgeRevision::getId,id)
                .eq(BusinessKnowledgeRevision::getStatus,"VALID").set(BusinessKnowledgeRevision::getStatus,"SUPERSEDED"));
    }
    private List<String> dependencies(BusinessKnowledgeRevision row) { return read(row.getDependenciesJson(),new TypeReference<List<String>>(){}); }
    private void requireEvidenceConfirmation(BusinessKnowledgeRevision row){
        if(row.getEvidenceJson()==null)throw new BusinessException("当前修订缺少人工依据确认，请核对原始资料后保存新修订");
        var evidence=read(row.getEvidenceJson(),new TypeReference<Map<String,Object>>(){});
        if(!"HUMAN_ATTESTATION".equals(evidence.get("type"))||evidence.get("confirmedBy")==null
                ||Objects.toString(evidence.get("reference"),"").isBlank()||Objects.toString(evidence.get("excerpt"),"").isBlank()
                ||!Objects.equals(row.getContentHash(),evidence.get("contentHash"))||!Objects.equals(row.getSourceHash(),evidence.get("sourceHash")))
            throw new BusinessException("人工依据确认与当前内容或来源不一致，请重新核对");
    }
    private boolean valid(BusinessKnowledgeRevision row) {
        try { if(row==null)return false;requireEvidenceConfirmation(row);
            BusinessKnowledgePolicy.requireDependencies(row.getKind(),read(row.getBodyJson(),new TypeReference<Map<String,String>>(){}),dependencies(row));
            return "VALID".equals(row.getStatus()) && row.getReviewedBy()!=null
            && row.getContentHash().equals(row.getVerifiedHash()) && row.getContentHash().equals(hash(render(row)))
            && row.getSourceJson()!=null&&row.getSourceHash().equals(hash(write(read(row.getSourceJson(),new TypeReference<Object>(){}))))
            && row.getSourceHash().equals(hash(write(sources(row.getDatasourceId(),row.getSnapshotId(),dependencies(row))))); }
        catch(BusinessException exception) { return false; }
    }
    @Override public List<Map<String,Object>> facts(Long ds,Long snapshotId) {
        var result=new ArrayList<Map<String,Object>>();
        for(var row:mapper.selectList(new LambdaQueryWrapper<BusinessKnowledgeRevision>().eq(BusinessKnowledgeRevision::getDatasourceId,ds)
                .eq(BusinessKnowledgeRevision::getSnapshotId,snapshotId).eq(BusinessKnowledgeRevision::getStatus,"VALID").orderByAsc(BusinessKnowledgeRevision::getId))) {
            if(!valid(row))continue;
            result.add(Map.of("sourceIds",List.of(row.getId()),"factType","BUSINESS_"+row.getKind(),"title",row.getTitle(),
                    "body",render(row),"contentHash",row.getContentHash(),"dependencyHash",row.getSourceHash(),"dependencies",dependencies(row),
                    "reviewStatus","APPROVED","governanceStatus","NORMAL"));
        } return result;
    }
    @Override public void validateFact(Long ds,Long snapshotId,Map<String,Object> marker,String body) {
        var ids=json.convertValue(marker.get("sourceIds"),new TypeReference<List<Long>>(){});
        if(ids==null||ids.size()!=1)throw new BusinessException("业务知识来源无效");
        var row=mapper.selectById(ids.get(0));
        if(!valid(row)||!Objects.equals(row.getDatasourceId(),ds)||!Objects.equals(row.getSnapshotId(),snapshotId)
                ||!Objects.equals(marker.get("factType"),"BUSINESS_"+row.getKind())
                ||!Objects.equals(marker.get("contentHash"),row.getContentHash())
                ||!Objects.equals(marker.get("dependencyHash"),row.getSourceHash())
                ||!new HashSet<>(dependencies(row)).equals(new HashSet<>(json.convertValue(marker.get("dependencies"),new TypeReference<List<String>>(){})))
                ||!render(row).trim().equals(body.trim())) throw new BusinessException(409,"业务知识正文、来源或审核修订已变化");
    }
    @Override public boolean membershipValid(Long ds,Long snapshotId,List<String> ids) {
        if(ids==null||ids.size()!=1)return false;
        try { var row=mapper.selectById(Long.valueOf(ids.get(0)));return valid(row)&&Objects.equals(row.getDatasourceId(),ds)&&Objects.equals(row.getSnapshotId(),snapshotId); }
        catch(RuntimeException exception){return false;}
    }
    private String render(BusinessKnowledgeRevision row) {
        var body=read(row.getBodyJson(),new TypeReference<Map<String,String>>(){});
        var lines=new ArrayList<String>();new TreeMap<>(body).forEach((key,value)->lines.add("- "+key+": "+safeText(value)));
        lines.add("- Evidence: "+safeText(Objects.toString(row.getEvidence(),"")));
        return String.join("\n",lines);
    }
    private String safeText(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\r"," ").replace("\n"," ");}
    private String write(Object value){try{return json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value);}catch(Exception exception){throw new BusinessException("知识内容不能序列化");}}
    private <T>T read(String value,TypeReference<T> type){try{return json.readValue(value,type);}catch(Exception exception){throw new BusinessException("知识记录格式无效");}}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception exception){throw new IllegalStateException(exception);}}
}
