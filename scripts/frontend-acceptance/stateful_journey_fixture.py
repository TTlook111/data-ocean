"""Single authoritative synthetic Java API state for the final frontend journey.
No real backend/model/database. Business transitions occur only in intercepted UI writes;
worker completion is simulated on reads. Faults/revocations are explicit external events.
"""
import asyncio, copy, hashlib, json, re, time
from urllib.parse import urlparse, parse_qs, quote
from knowledge_workspace_v2 import doc
from collection_assets_v2 import ENTITIES, COLS, overview
from governance_v2 import workspace, revision

NOW='2026-10-29T12:00:00'
SQL='SELECT SUM(amount) AS total_amount FROM orders WHERE order_id = 101 LIMIT 100'
SQL_HASH=hashlib.sha256(SQL.encode()).hexdigest()
SOURCE_CODES=['datasource:view','datasource:manage','metadata:view','metadata:collect:view','metadata:collect:run','metadata:release:view','governance:view','governance:issue:view','governance:field:view','governance:field:manage','governance:check','knowledge:view','knowledge:manage','lineage:view','audit:view','glossary:view']
# Current Java fixed catalog; system function rights still do not grant business data.
ALL_FUNCTION_CODES=['query:use', 'query:sql:view', 'query:export', 'admin:workbench:view', 'datasource:view', 'datasource:manage', 'metadata:collect:view', 'metadata:collect:run', 'metadata:view', 'metadata:release:view', 'metadata:release:review', 'metadata:release:publish', 'governance:view', 'governance:check', 'governance:issue:view', 'governance:issue:manage', 'governance:rule:view', 'governance:rule:manage', 'governance:field:view', 'governance:field:manage', 'glossary:view', 'glossary:manage', 'glossary:approve', 'knowledge:view', 'knowledge:manage', 'knowledge:approve', 'knowledge:publish', 'prompt:view', 'prompt:manage', 'prompt:approve', 'security:permission:view', 'security:permission:manage', 'security:mask:view', 'security:mask:manage', 'security:effective:view', 'security:approval:view', 'security:approval:review', 'organization:user:view', 'organization:user:manage', 'organization:user:export', 'organization:role:view', 'organization:role:manage', 'organization:department:view', 'organization:department:manage', 'organization:permission:view', 'audit:view', 'audit:export', 'lineage:view', 'lineage:manage', 'system:runtime:view', 'system:runtime:manage', 'operation-log:view', 'system:ai-config:view', 'system:ai-config:manage']
ROLES={
 'operator':dict(id=1,globalFunctions=['admin:workbench:view','glossary:view'],codes=SOURCE_CODES,query=False),
 'reviewer':dict(id=2,globalFunctions=['admin:workbench:view'],codes=['datasource:view','metadata:view','metadata:release:view','metadata:release:review','metadata:release:publish','governance:field:view','governance:check','knowledge:view','knowledge:approve','knowledge:publish','lineage:view','audit:view'],query=False),
 'reader':dict(id=3,globalFunctions=['query:use','query:sql:view','query:export'],codes=[],query=True),
 'readonly':dict(id=4,globalFunctions=['admin:workbench:view'],codes=['datasource:view','metadata:view','metadata:collect:view','metadata:release:view','governance:field:view','knowledge:view','lineage:view'],query=False),
 'metadata_only':dict(id=6,globalFunctions=[],codes=['metadata:view'],sources=[1],query=False),
 'scoped':dict(id=7,globalFunctions=[],codes=['metadata:view'],sources=[2],query=False),
 'system':dict(id=5,globalFunctions=ALL_FUNCTION_CODES,codes=ALL_FUNCTION_CODES,query=True,systemAdmin=True),
}
def clone(x):return copy.deepcopy(x)
def redact(x):
 if isinstance(x,dict):return {k:('[redacted]' if any(s in k.lower() for s in ['password','apikey','token','secret']) else redact(v)) for k,v in x.items()}
 if isinstance(x,list):return [redact(v) for v in x]
 return x

class JourneyFixture:
 def __init__(self):
  self.journal=[];self.transitions=[];self.tokens={};self.token_no=0;self.revoked=set();self.grants={('reader',1)};self.invalid_sources=set();self.permission_revision=1;self.faults={};self.unhandled=[];self.fixture_errors=[]
  self.sources={1:dict(id=1,name='合成销售经营',description='合成订单金额业务旅程',dbType='MYSQL',host='sales.example.invalid',port=3306,databaseName='sales_analytics',charset='utf8mb4',username='synthetic_readonly',status=1,healthStatus='UNKNOWN'),2:dict(id=2,name='合成供应链',description='独立范围，禁止混用销售字段',dbType='MYSQL',host='supply.example.invalid',port=3306,databaseName='supply_chain',charset='utf8mb4',username='synthetic_readonly',status=1,healthStatus='HEALTHY')}
  self.connected={1:False,2:True};self.snapshots={22:self.snapshot(22,2,1,'PUBLISHED')};self.jobs=[];self.docs=[];self.builds=[];self.latest_check=None
  self.field=workspace(204,11);self.field.update(rowVersion=1,currentApproved=None,currentApprovedRevisionId=None,effectiveStatus='NO_APPROVED_EXPLANATION',workingStatus='DRAFT',workingRevisionId=301,draft=revision(301,'DRAFT',1),revisions=[],reviews=[],candidates=[])
  self.field['draft'].update(meaning='',evidence='');self.next_revision=302
  # Existing human field tag is a declared starting fact, not a query reward.
  self.field_tags={204:["金额"]}
  self.query_tasks={};self.conversations=[];self.messages={};self.feedback=[];self.projected=set();self.query_no=0;self.collect_fail_once=True;self.hold_queries=False;self.hold_builds=False
 def snapshot(self,id,source,version,status):return dict(id=id,snapshotId=id,datasourceId=source,datasourceName='合成销售经营' if source==1 else '合成供应链',snapshotVersion=version,status=status,tableCount=1,columnCount=5,createdAt=NOW,publishedAt=NOW if status=='PUBLISHED' else None)
 def actor(self,req):return self.tokens.get(req.headers.get('authorization','').removeprefix('Bearer '))
 def can(self,actor,code,source=1):return bool(actor) and (ROLES[actor].get('systemAdmin') or (code in ROLES[actor]['globalFunctions'] and code in ['admin:workbench:view','glossary:view']) or (code in ROLES[actor]['codes'] and source in ROLES[actor].get('sources',[1,2])))
 def query_allowed(self,actor,source=1):return bool(actor) and ROLES[actor]['query'] and (actor,source) in self.grants and (actor,source) not in self.revoked
 def record_transition(self,event,**facts):self.transitions.append(dict(sequence=len(self.transitions)+1,event=event,**clone(facts)))
 def published(self,source=1):return next((s for s in self.snapshots.values() if s['datasourceId']==source and s['status']=='PUBLISHED'),None)
 def readiness(self,source=1,actor=None):
  pub=self.published(source);snaps=[s for s in self.snapshots.values() if s['datasourceId']==source];latest=max(snaps,key=lambda s:s['snapshotVersion']) if snaps else None
  kd=next((d for d in self.docs if d['datasourceId']==source and d['status']=='PUBLISHED'),None);active=next((b for b in reversed(self.builds) if b['datasourceId']==source and b['status']=='ACTIVE'),None)
  permission=self.query_allowed(actor,source) if actor else any(s==source and (user,s) not in self.revoked for user,s in self.grants)
  governance=True # This scenario has no unresolved SOURCE HIGH issue; semantic hints do not create a gate.
  blocks=[]
  for ok,code,message in [(self.connected[source],'CONNECTION_NOT_HEALTHY','连接尚未核验'),(bool(pub),'SNAPSHOT_NOT_PUBLISHED','尚未发布元数据快照'),(governance,'BLOCKING_GOVERNANCE_ISSUES','存在未解决SOURCE高风险问题'),(permission,'CURRENT_USER_NOT_GRANTED' if actor else 'QUERY_PERMISSION_NOT_CONFIGURED','你尚未获得该数据源查询权限' if actor else '尚未配置有效查询授权')]:
   if not ok:blocks.append(dict(code=code,message=message,ownerRole='相应有权维护者',actionText='查看详情'))
  stage,label,progress=('ASKABLE','可询问',100) if not blocks else ('CONNECTION_CHECK_REQUIRED','连接待处理',15) if not self.connected[source] else ('SNAPSHOT_PENDING','快照待发布',35) if not pub else ('GOVERNANCE_BLOCKED','治理阻塞',55) if not governance else ('PERMISSION_PENDING','权限待配置',90)
  return dict(datasourceId=source,datasourceName=self.sources[source]['name'],askable=not blocks,stage=stage,stageLabel=label,progress=progress,publishedSnapshotId=pub['id'] if pub else None,snapshotVersion=pub['snapshotVersion'] if pub else None,latestCollectedSnapshotId=latest['id'] if latest else None,latestCollectedSnapshotVersion=latest['snapshotVersion'] if latest else None,connectionReady=self.connected[source],metadataReady=bool(pub),governanceReady=governance,knowledgeReady=bool(active),permissionReady=permission,blockReasons=blocks,ragBuildId=active['buildId'] if active else None,ragStatus=active['status'] if active else 'NONE',ragSourceSnapshotId=active['sourceSnapshotId'] if active else None,ragSourceSnapshotVersion=1 if active else None,ragStale=bool(active and pub and active['sourceSnapshotId']!=pub['id']))
 def full_field(self,snapshot=11):
  d=clone(self.field);d.update(snapshotId=snapshot,columnMetaId=204,columnName='amount')
  return d
 def make_result(self,task):
  return dict(protocolVersion='IAM-SIMPLE-1',taskId=task['taskId'],status=task['status'],question=task['question'],sqlExplanation='合成结果：按已核对字段含义汇总，仍需人工判断业务正确性。',data=[dict(total_amount=100)] if task['status']=='COMPLETED' else None,columns=[dict(name='total_amount',type='DECIMAL',comment='合成成交金额')],rowCount=1,usedTables=['orders'],usedColumns=['orders.amount'],activeMetadataSnapshotId=task['snapshotId'],ragBuildId=task['buildId'],ragSourceSnapshotId=task['buildSnapshotId'],sourceTrace=dict(attemptId=task['attemptId'],sqlHash=SQL_HASH,protectionStatus='PROTECTED',fieldRevisionId=task['revisionId']),permissionRevision=self.permission_revision,finalProtectionStatus='FINAL_PROTECTED',canViewSql=True,canExport=True,totalTimeMs=12,progressNode='sql_execution',progressMessage='合成：受保护执行中',errorMessage='用户已停止' if task['status']=='CANCELLED' else None,createdAt=NOW)
 async def route(self,r):
  try:return await self.handle(r)
  except Exception as e:
   self.fixture_errors.append(dict(path=urlparse(r.request.url).path,type=type(e).__name__,error=str(e)))
   if self.journal:self.journal[-1]["status"]="fixture_error"
   await r.fulfill(status=500,content_type="application/json",body=json.dumps(dict(code=500,message="合成场景合同断言失败",data=None)))
 async def handle(self,r):
  req=r.request;u=urlparse(req.url);p=u.path;q=parse_qs(u.query);method=req.method;actor=self.actor(req)
  body=req.post_data_json if req.post_data and req.headers.get('content-type','').startswith('application/json') else None
  entry=dict(sequence=len(self.journal)+1,actor=actor,method=method,path=p,query=q,payload=redact(body),at=time.monotonic())
  self.journal.append(entry)
  async def send(data,status=200,msg='合成API响应'):
   entry['status']=status
   await r.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=msg,data=data),ensure_ascii=False))
  async def deny(code,source=1):
   if self.can(actor,code,source):return False
   await send(None,403,'合成：当前角色或负责源无权');return True
  writes=[('POST',r'/api/auth/login'),('POST',r'/api/auth/logout'),('POST',r'/api/admin/datasources/test-connection'),('PUT',r'/api/admin/datasources/\d+'),('POST',r'/api/admin/datasources/\d+/test-connection'),('POST',r'/api/admin/metadata/sync'),('POST',r'/api/admin/field-semantics/columns/204/(candidates|submit|review)'),('PUT',r'/api/admin/field-semantics/columns/204/draft'),('POST',r'/api/admin/semantic-checks/snapshots/\d+'),('PATCH',r'/api/admin/snapshots/\d+/status'),('POST',r'/api/admin/snapshots/\d+/publish'),('POST',r'/api/admin/knowledge-docs/(generate-from-snapshot|rag-builds)'),('POST',r'/api/admin/knowledge-docs'),('PUT',r'/api/admin/knowledge-docs/\d+'),('POST',r'/api/admin/knowledge-docs/\d+/(submit-review|approve|publish)'),('POST',r'/api/iam-s1/query/ask'),('POST',r'/api/iam-s1/query/tasks/[^/]+/(cancel|resume|feedback)'),('POST',r'/api/admin/governance-feedback/81/(review|verification)')]
  if method!='GET' and not any(method==verb and re.fullmatch(pattern,p) for verb,pattern in writes):
   self.unhandled.append(dict(path=p,method=method));return await send(None,404,'本旅程未模拟此方法；写入拒绝')
  write_only=r'/api/auth/(login|logout)|/api/admin/datasources(?:/\d+)?/test-connection|/api/admin/metadata/sync|/api/admin/field-semantics/columns/\d+/(draft|submit|review)|/api/admin/snapshots/\d+/(status|publish|revoke)|/api/admin/knowledge-docs/generate-from-snapshot|/api/admin/knowledge-docs/\d+/(submit-review|approve|publish)|/api/iam-s1/query/ask|/api/iam-s1/query/tasks/[^/]+/(cancel|resume)|/api/admin/governance-feedback/\d+/(review|verification)'
  if method=='GET' and re.fullmatch(write_only,p):
   self.unhandled.append(dict(path=p,method=method));return await send(None,405,'合成：此路径不是读取接口')
  if p=='/api/auth/captcha':return await send(dict(captchaKey='synthetic-captcha',captchaImage='data:image/svg+xml,'+quote('<svg xmlns="http://www.w3.org/2000/svg" width="120" height="50"><text x="10" y="32" font-size="24">A1B2</text></svg>')))
  if p=='/api/auth/login':
   name=body.get('username');assert name in ROLES and body.get('captchaCode')=='A1B2'
   self.token_no+=1;token=f'synthetic-journey-{name}-{self.token_no}';self.tokens[token]=name;role=ROLES[name]
   self.record_transition('LOGIN',actor=name,userId=role['id'])
   return await send(dict(token=token,tokenType='Bearer',expiresIn=3600,userId=role['id'],username=name,realName='合成'+name,passwordChanged=True))
  if not actor:return await send(None,401,'合成：会话已失效')
  if p=='/api/auth/me':return await send(dict(id=ROLES[actor]['id'],username=actor,realName='合成'+actor,email=actor+'@example.test',phone='',passwordChanged=True))
  if p=='/api/auth/logout':self.tokens.pop(req.headers['authorization'].removeprefix('Bearer '),None);self.record_transition('LOGOUT',actor=actor);return await send(None)
  if p=='/api/iam-s1/capabilities':
   role=ROLES[actor];return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=role['id'],systemAdmin=role.get('systemAdmin',False),globalFunctions=list(dict.fromkeys(role['globalFunctions']+role['codes'])),datasourceCapabilities=[dict(datasourceId=i,datasourceName=s['name'],functionCodes=role['codes'],functionNames=[]) for i,s in self.sources.items() if i in role.get('sources',[1,2])] if role['codes'] else [],queryUse=role['query'],viewSql=role['query'],export=role['query'],permissionRevision=self.permission_revision))
  # Explicit external failure, copied response is taken before delay to model late packets.
  fault=self.faults.pop((method,p),None)
  if fault:entry['faultInjected']=fault
  if fault=='503':return await send(None,503,'合成：本次读取失败，可明确重试')
  if p.startswith('/api/notifications'):return await send(dict(count=0) if p.endswith('/unread-count') else dict(records=[],total=0))
  if p=='/api/admin/dashboard/stats':return await send(dict(openIssues=int(not self.field.get('currentApproved')),recentActivities=[dict(type=t['event'],description='合成：'+t['event'],time=NOW) for t in self.transitions[-3:]]))
  if p=='/api/admin/datasources/simple':return await send(list(self.sources.values()))
  if p=='/api/admin/datasources/readiness/batch':return await send([self.readiness(int(i)) for i in q.get('datasourceIds',['1'])[0].split(',') if i and int(i) in self.sources])
  if p=='/api/admin/datasources/test-connection':
   if await deny('datasource:manage'):return
   self.record_transition('CONNECTION_TEST',source=1);return await send(dict(success=True,responseTimeMs=1,message='合成只读连接测试成功'))
  m=re.fullmatch(r'/api/admin/datasources/(\d+)(?:/(readiness|test-connection|status|version-history|published-snapshot))?',p)
  if p=='/api/admin/datasources' or m:
   source=int(m[1]) if m else 1;suffix=m[2] if m else None
   if await deny('datasource:view' if suffix not in ['version-history','published-snapshot'] else 'metadata:release:view',source):return
   if method in ['PUT','PATCH']:
    if await deny('datasource:manage',source):return
    self.connected[source]=True;self.sources[source]['healthStatus']='HEALTHY'
    self.sources[source].update({k:v for k,v in body.items() if k not in ['password','id']});self.record_transition('SOURCE_UPDATED',source=source,fields=[k for k in body if k!='password']);return await send(self.sources[source])
   if suffix=='test-connection':
    if await deny('datasource:manage',source):return
    self.connected[source]=True;self.sources[source]['healthStatus']='HEALTHY';self.record_transition('CONNECTION_TEST',source=source);return await send(dict(success=True,responseTimeMs=1,message='合成连接测试成功'))
   if suffix=='readiness':
    data=self.readiness(source)
    if fault=='delay':await asyncio.sleep(.8)
    return await send(data)
   if suffix in ['version-history','published-snapshot']:
    rows=[s for s in self.snapshots.values() if s['datasourceId']==source];return await send(dict(records=rows,total=len(rows)) if suffix=='version-history' else self.published(source))
   if m:return await send(self.sources[source])
   rows=[s for s in self.sources.values() if self.can(actor,'datasource:view',s['id']) and q.get('name',[''])[0] in s['name']];return await send(dict(records=rows,total=len(rows)))
  if p=='/api/admin/metadata/sync':
   source=body['datasourceId']
   if await deny('metadata:collect:run',source):return
   failed=self.collect_fail_once;self.collect_fail_once=False
   snapid=11 if 11 not in self.snapshots else 12
   job=dict(id=len(self.jobs)+1,datasourceId=source,datasourceName=self.sources[source]['name'],triggerType='MANUAL',status='FAILED' if failed else 'SUCCESS',snapshotId=None if failed else snapid,progressTotal=5,progressCurrent=0 if failed else 5,errorMessage='合成：首次采集连接超时，请明确重试' if failed else None,startedAt=NOW,finishedAt=NOW)
   self.jobs.insert(0,job)
   if not failed:self.snapshots[snapid]=self.snapshot(snapid,source,1 if snapid==11 else 2,'DRAFT')
   self.record_transition('COLLECTION_FAILED' if failed else 'COLLECTED',source=source,snapshotId=job['snapshotId']);return await send(dict(taskId=job['id']))
  if p=='/api/admin/metadata/sync-tasks':
   if await deny('metadata:collect:view'):return
   rows=[j for j in self.jobs if not q.get('datasourceId') or j['datasourceId']==int(q['datasourceId'][0])];return await send(dict(records=rows,total=len(rows)))
  if p=='/api/admin/metadata/snapshots':
   if await deny('metadata:view'):return
   rows=[s for s in self.snapshots.values() if s['datasourceId']==int(q.get('datasourceId',['1'])[0])];return await send(dict(records=rows,total=len(rows)))
  m=re.fullmatch(r'/api/admin/metadata/snapshots/(\d+)(?:/tables(?:/([^/]+)/columns)?)?',p)
  if m:
   sid=int(m[1]);snap=self.snapshots.get(sid)
   if not snap:return await send(None,404,'合成：无此快照')
   if await deny('metadata:view',snap['datasourceId']):return
   source=snap['datasourceId'];table='orders' if source==1 else 'inventory';cols=[dict(id=c['columnMetaId']+(100 if sid==12 else 0) if source==1 else 2200+i,tableMetaId=1,tableName=table,columnName=c['name'] if source==1 else 'stock_'+c['name'],columnComment=c['comment'],dataType=c['dataType'],isNullable=0,isPrimaryKey=int(c['primaryKey']),ordinalPosition=i+1,governanceStatus='NORMAL') for i,c in enumerate(COLS)]
   tables=[dict(id=1,tableName=table,tableComment='合成销售订单' if source==1 else '合成库存',tableType='BASE TABLE',governanceStatus='NORMAL')]
   if m[2]:return await send(cols)
   if p.endswith('/tables'):return await send(tables)
   return await send(dict(snapshot=snap,tables=tables,columns=cols))
  if p=='/api/admin/catalog/published-entities':
   source=int(q.get('datasourceId',['1'])[0]);pub=self.published(source)
   if await deny('metadata:view',source):return
   return await send(dict(datasourceId=source,publishedSnapshotId=pub['id'] if pub else None,snapshotVersion=pub['snapshotVersion'] if pub else None,syncStatus=('SYNCED' if source==1 else 'SYNCING') if pub else 'NONE',tableCount=1,columnCount=5,entities=ENTITIES if pub and source==1 else []))
  if p=='/api/admin/catalog/entities':
   source=int(q.get('datasourceId',['1'])[0])
   if await deny('metadata:view',source):return
   return await send(ENTITIES if source==1 and self.published() else [])
  m=re.fullmatch(r'/api/admin/catalog/entities/(\d+)/(overview|lineage|column-lineage)',p)
  if m:
   if int(m[1]) not in [e['id'] for e in ENTITIES]:return await send(None,404,'合成：此对象目录尚未投影，不改用源1对象')
   if await deny('metadata:view' if m[2]=='overview' else 'lineage:view',1):return
   if m[2]=='overview':
    data=overview(int(m[1]));pub=self.published();data.update(sourceSnapshot=dict(id=11,version=1,status=self.snapshots[11]['status']),publishedSnapshot=dict(id=pub['id'],version=pub['snapshotVersion'],status='PUBLISHED',entitySyncStatus='SYNCED') if pub else None,sourceMatchesPublished=bool(pub),sourceVerified=True);data['terms']['items']=[];return await send(data)
   return await send(dict(nodes=ENTITIES[:1],edges=[],columnMappingsByEdge={},depth=1) if m[2]=='lineage' and 'lineageType' in q else [] if m[2]=='lineage' else dict(columnId=int(m[1]),columnName='amount',upstream=[],downstream=[]))
  m=re.fullmatch(r'/api/admin/field-semantics/columns/(\d+)(.*)',p)
  if m:
   if int(m[1])!=204:return await send(None,404,'合成：字段不在本场景')
   if await deny('governance:field:view'):return
   suffix=m[2];snap=int(q.get('snapshotId',['11'])[0])
   if snap!=11:return await send(None,404,'合成：204不属于所选快照')
   data=self.full_field(snap)
   if method in ['PUT','POST']:
    code='knowledge:approve' if suffix=='/review' else 'governance:field:manage'
    if await deny(code):return
    if code=='governance:field:manage' and await deny('knowledge:manage'):return
    request_version=body.get('expectedVersion') if body else int(q.get('expectedVersion',['-1'])[0])
    request_snapshot=body.get('snapshotId',snap) if body else snap
    if request_snapshot!=11:return await send(None,409,'合成：字段与快照不匹配')
    if request_version!=self.field['rowVersion']:return await send(None,409,'合成：精确字段版本已变化')
    if suffix=='/candidates':self.field['candidates']=[dict(id=401,status='READY',businessName='合成订单成交金额',meaning='合成AI候选：折后成交金额，退款口径须人核',evidence='合成资料',unresolvedItemsJson='[]',createdAt=NOW)];self.record_transition('AI_CANDIDATE',column=204)
    elif suffix=='/draft':
     self.field['rowVersion']+=1;self.field.update(workingRevisionId=self.next_revision,workingStatus='DRAFT',draft={**revision(self.next_revision,'DRAFT',self.next_revision-301),**body});self.field['draft']['contentHash']=hashlib.sha256(json.dumps(body,sort_keys=True,ensure_ascii=False).encode()).hexdigest();self.next_revision+=1;self.record_transition('FIELD_SAVED',revisionId=self.field['workingRevisionId'],meaning=self.field['draft']['meaning'])
    elif suffix=='/submit':self.field['rowVersion']+=1;self.field['workingStatus']='PENDING_REVIEW';self.field['draft']['status']='PENDING_REVIEW';self.record_transition('FIELD_SUBMITTED',revisionId=self.field['workingRevisionId'])
    elif suffix=='/review':
     assert body['revisionId']==self.field['workingRevisionId'] and self.field['workingStatus']=='PENDING_REVIEW'
     assert body.get('action')=='APPROVE' and body.get('evidenceConfirmed') and body.get('evidenceReference') and body.get('reason')
     self.field['currentApproved']=dict(self.field['draft'],status='APPROVED');self.field.update(rowVersion=self.field['rowVersion']+1,currentApprovedRevisionId=body['revisionId'],workingRevisionId=None,workingStatus=None,draft=None,effectiveStatus='APPROVED');self.record_transition('FIELD_APPROVED',revisionId=body['revisionId'],actor=actor)
    else:return await send(None,404,'本旅程未模拟该字段写入')
    return await send(self.full_field(snap))
   if suffix=='/glossary-options':return await send([])
   if suffix=='/usage':return await send(dict(status='INCLUDED' if self.builds else 'NOT_BUILT',message='合成：使用状态来自本次明确构建',buildId=self.builds[-1]['buildId'] if self.builds else None))
   if suffix=='/governance-progress':return await send(dict(columnMetaId=204,snapshotId=snap,stage='ROUND_COMPLETED' if self.latest_check and self.latest_check.get('revisionId')==self.field.get('currentApprovedRevisionId') and self.field_tags.get(204) else 'AWAITING_RECHECK' if self.field.get('currentApproved') else 'AWAITING_REVIEW' if self.field['workingStatus']=='PENDING_REVIEW' else 'HUMAN_EDITING',nextAction='EDIT',reason='合成：人审、发布、构建是独立事实',revisionId=self.field.get('currentApprovedRevisionId') or self.field.get('workingRevisionId')))
   if suffix=='':data={k:v for k,v in data.items() if k not in ['draft','reviews','revisions','candidates']}
   return await send(data)
  if re.fullmatch(r'/api/admin/semantic-checks/snapshots/\d+(?:/latest)?',p):
   if method=='POST':self.latest_check=dict(id=7,datasourceId=1,snapshotId=11,status='SUCCESS',applicableCount=1,validCount=int(bool(self.field.get('currentApproved'))),missingCount=int(not self.field.get('currentApproved')),staleCount=0,notApplicableCount=0,ruleVersion=1,startedAt=NOW,revisionId=self.field.get('currentApprovedRevisionId'));self.record_transition('SEMANTIC_RECHECK',revisionId=self.field.get('currentApprovedRevisionId'))
   if method=='POST':
    for f in self.feedback:
     if f.get('followupStatus')=='QUEUED' and self.field_tags.get(204) and self.field.get('currentApprovedRevisionId')!=f.get('revisionId'):
      f.update(followupStatus='AWAITING_VERIFICATION',currentRevisionId=self.field['currentApprovedRevisionId']);self.record_transition('FEEDBACK_AWAITING_VERIFICATION',feedbackId=f['id'],revisionId=f['currentRevisionId'],checkBatchId=7)
   return await send(self.latest_check)
  m=re.fullmatch(r'/api/admin/snapshots/(\d+)/(release-gate|audit-logs|status|publish|revoke)',p)
  if m:
   snap=self.snapshots.get(int(m[1]));action=m[2]
   if not snap:return await send(None,404,'合成：无此快照')
   if await deny('metadata:release:view',snap['datasourceId']):return
   if action=='release-gate':
    allowed=True;unresolved=not bool(self.field.get('currentApproved'));return await send(dict(snapshotId=snap['id'],datasourceId=1,snapshotStatus=snap['status'],approvable=allowed,publishable=allowed and snap['status']=='APPROVED',blockingCount=0,warningCount=int(unresolved),unresolvedSourceHigh=0,unresolvedSemanticHigh=int(unresolved),validTableCount=1,items=[dict(code='SEMANTIC',category='SEMANTIC',severity='HIGH',status='PASS' if self.field.get('currentApproved') else 'WARN',blocksApproval=False,blocksPublish=False,count=int(not self.field.get('currentApproved')),message='合成字段尚未人审（提示，不阻断发布）' if unresolved else '合成人审已完成')]))
   if action=='audit-logs':return await send(dict(records=[dict(id=i+1,action=t['event'],operatorName=t.get('actor','合成角色'),reason='同状态场景回执',createdAt=NOW) for i,t in enumerate(self.transitions) if t.get('snapshotId')==snap['id']],total=1))
   if await deny('metadata:release:publish' if action=='publish' else 'metadata:release:review'):return
   if action=='status':snap['status']=body['targetStatus']
   elif action=='publish':
    assert snap['status']=='APPROVED'
    for s in self.snapshots.values():
     if s['datasourceId']==snap['datasourceId'] and s['status']=='PUBLISHED':s['status']='EXPIRED'
    snap['status']='PUBLISHED'
   self.record_transition('SNAPSHOT_'+snap['status'],snapshotId=snap['id'],actor=actor);return await send(None)
  if re.fullmatch(r'/api/admin/snapshots/\d+/diff/\d+',p):return await send(dict(addedTables=[],removedTables=[],addedColumns=[],removedColumns=[],modifiedColumns=[dict(tableName='orders',columnName='amount',oldType='DECIMAL',newType='DECIMAL',oldComment='合成旧字段注释',newComment='合成新字段注释')]))
  if p=='/api/admin/knowledge-docs/generation-inputs':
   if await deny('knowledge:manage'):return
   return await send(dict(datasourceId=1,snapshotId=11,snapshotVersion=1,tableCount=1,columnCount=5,tableNames=['orders'],glossaryFacts=[],excludedTerms=[]))
  if p=='/api/admin/knowledge-docs/generate-from-snapshot':
   if await deny('knowledge:manage'):return
   assert body['snapshotId']==11 and int(q['datasourceId'][0])==1 and self.field['currentApproved'] and self.published()['id']==11
   d=doc(23);d.update(datasourceId=1,title='合成订单业务知识',content='# 合成订单业务知识\n'+self.field['currentApproved']['meaning'],version=1,currentVersion=1,sourceSnapshotId=11,tableNames='["orders"]')
   self.docs.append(d);self.record_transition('DOC_GENERATED',docId=23,snapshotId=11,fieldRevisionId=self.field['currentApprovedRevisionId'])
   return await send([dict(id=23,title=d['title'],tableNames=['orders'])])
  if p=='/api/admin/knowledge-docs' or re.fullmatch(r'/api/admin/knowledge-docs/\d+(?:/.*)?',p):
   m=re.fullmatch(r'/api/admin/knowledge-docs/(\d+)(?:/(.*))?',p);d=next((d for d in self.docs if d['id']==int(m[1])),None) if m else None;action=m[2] if m else None
   if await deny('knowledge:view'):return
   if not m:
    if method=='POST':
     if await deny('knowledge:manage'):return
     assert body['datasourceId']==1
     d=doc(23);d.update(datasourceId=1,title=body['title'],content=body.get('content') or '',version=1,currentVersion=1,sourceSnapshotId=None,tableNames='[]');self.docs.append(d);self.record_transition('DOC_CREATED',docId=23,snapshotId=d['sourceSnapshotId']);return await send(dict(id=23))
    rows=[d for d in self.docs if d['datasourceId']==int(q.get('datasourceId',['1'])[0]) and (not q.get('status') or d['status']==q['status'][0])];return await send(dict(records=rows,total=len(rows)))
   if not d:return await send(None,404,'合成：没有该知识文档')
   if method=='GET' and not action:return await send(d)
   if action=='source-status':return await send(dict(valid=d['sourceSnapshotId'] not in self.invalid_sources,snapshotId=d['sourceSnapshotId'],reasons=['合成：来源指纹已失效，需核对原来源修订'] if d['sourceSnapshotId'] in self.invalid_sources else []))
   if action=='source-snapshots':return await send([dict(docId=d['id'],versionNo=d['currentVersion'],snapshotId=d['sourceSnapshotId'],snapshotVersion=1,status=self.snapshots[d['sourceSnapshotId']]['status'],tableCount=1,columnCount=5)])
   if action=='versions':return await send([dict(id=d['currentVersion']+100,docId=d['id'],versionNo=d['currentVersion'],content=d['content'],generationSource='MANUAL',reviewStatus='APPROVED' if d['status'] in ['APPROVED','PUBLISHED'] else d['status'],metadataSnapshotId=d['sourceSnapshotId'],createdAt=NOW)])
   if action=='review-tasks':return await send([dict(id=300,docId=d['id'],versionNo=d['currentVersion'],reviewStatus='APPROVED',reviewerName='合成reviewer',reviewComment='合成资料逐项核对',createdAt=NOW,reviewedAt=NOW)] if d['status'] in ['APPROVED','PUBLISHED'] else [])
   if action=='vector-tasks':return await send([])
   if action=='preview-chunks':return await send([dict(chunk_type='FIELD_SEMANTIC',chunk_text=d['content'],source_snapshot_id=str(d['sourceSnapshotId']),revision_id=str(self.field['currentApprovedRevisionId']))])
   code='knowledge:approve' if action in ['approve','reject'] else 'knowledge:publish' if action=='publish' else 'knowledge:manage'
   if await deny(code):return
   if method=='PUT':
    if body['version']!=d['version']:return await send(None,409,'合成：文档版本冲突')
    d.update(title=body['title'],content=body.get('content',''),version=d['version']+1,currentVersion=d['currentVersion']+1,status='DRAFT')
   elif action in ['submit-review','approve','publish']:
    assert d['status']=={'submit-review':'DRAFT','approve':'PENDING_REVIEW','publish':'APPROVED'}[action]
    d['status']={'submit-review':'PENDING_REVIEW','approve':'APPROVED','publish':'PUBLISHED'}[action];d['version']+=1
   else:return await send(None,404,'本旅程未模拟该知识写')
   self.record_transition('DOC_'+d['status'],docId=d['id'],version=d['version'],revision=d['currentVersion'],snapshotId=d['sourceSnapshotId'],actor=actor);return await send(None)
  if p=='/api/admin/knowledge-docs/rag-builds':
   if await deny('knowledge:view'):return
   if method=='POST':
    if await deny('knowledge:publish'):return
    assert body==dict(datasourceId=1,snapshotId=11,confirmed=True) and self.published()['id']==11
    published=[d for d in self.docs if d['status']=='PUBLISHED'];assert published
    build=dict(buildId='journey-build-'+str(len(self.builds)+1),datasourceId=1,sourceSnapshotId=11,generation=len(self.builds)+1,status='QUEUED',expectedChunkCount=1,actualVectorCount=0,embeddingModel='synthetic-no-model',embeddingDimension=8,confirmedAt=NOW,members=[dict(docId=d['id'],version=d['version'],revision=d['currentVersion'],hash=hashlib.sha256(d['content'].encode()).hexdigest()) for d in published],fieldRevisionId=self.field['currentApprovedRevisionId'])
    self.builds.append(build);self.record_transition('BUILD_CONFIRMED',buildId=build['buildId'],snapshotId=11,members=build['members'],revisionId=build['fieldRevisionId'])
    if fault=='unknown':entry['status']='aborted_after_accept';return await r.abort('connectionfailed')
    return await send(build)
   if not self.hold_builds:
    for b in self.builds:
     if b['status']=='QUEUED':
      for old in self.builds:
       if old['status']=='ACTIVE':old['status']='RETIRED'
      b.update(status='ACTIVE',actualVectorCount=1,activatedAt=NOW);self.record_transition('BUILD_ACTIVE',buildId=b['buildId'])
   return await send(self.builds)
  if p=='/api/iam-s1/query-resources/datasources':return await send([dict(id=i,name=s['name'],enabled=True) for i,s in self.sources.items() if self.query_allowed(actor,i)])
  if re.fullmatch(r'/api/iam-s1/query-resources/datasources/\d+/readiness',p):
   source=int(p.split('/')[-2])
   if not self.query_allowed(actor,source):return await send(None,403,'合成：查询数据源已撤权')
   return await send(self.readiness(source,actor))
  if p=='/api/iam-s1/query/conversations':return await send([c for c in self.conversations if c['userId']==ROLES[actor]['id'] and c['datasourceId']==int(q.get('datasourceId',['1'])[0])])
  m=re.fullmatch(r'/api/iam-s1/query/conversations/(\d+)/messages',p)
  if m:
   c=next((c for c in self.conversations if c['id']==int(m[1])),None)
   if not c or c['userId']!=ROLES[actor]['id'] or not self.query_allowed(actor,c['datasourceId']):return await send(None,403,'合成：历史结果权限已撤回')
   return await send(dict(items=self.messages.get(c['id'],[]),hasMore=False))
  if p=='/api/iam-s1/query/ask':
   source=body['datasourceId']
   if not self.query_allowed(actor,source):return await send(None,403,'合成：业务查询权已撤回')
   assert self.readiness(source,actor)['askable']
   self.query_no+=1;tid='journey-query-'+str(self.query_no);cid=body.get('conversationId') or 10
   if not self.conversations:self.conversations.append(dict(id=cid,datasourceId=source,userId=ROLES[actor]['id'],title='合成订单金额核验',createdAt=NOW,updatedAt=NOW))
   pub=self.published(source);build=next((b for b in self.builds if b['status']=='ACTIVE'),None)
   task=dict(id=700+self.query_no,taskId=tid,conversationId=cid,userId=ROLES[actor]['id'],source=source,snapshotId=pub['id'],buildId=build['buildId'] if build else None,buildSnapshotId=build['sourceSnapshotId'] if build else None,attemptId='journey-attempt-'+str(self.query_no),revisionId=build['fieldRevisionId'] if build else self.field['currentApprovedRevisionId'],question=body['question'],status='PROCESSING' if self.hold_queries else 'COMPLETED');self.query_tasks[tid]=task
   self.messages.setdefault(cid,[]).append(dict(id=len(self.messages.get(cid,[]))+1,role='user',content=body['question'],createdAt=NOW))
   if task['status']=='COMPLETED':self.messages[cid].append(dict(id=len(self.messages[cid])+1,role='assistant',content='合成订单金额100元',taskId=tid,metadata=json.dumps(self.make_result(task),ensure_ascii=False),createdAt=NOW));self.projected.add(tid)
   self.record_transition('QUERY_CREATED',taskId=tid,snapshotId=pub['id'],buildId=task['buildId'],revisionId=task['revisionId'],attemptId=task['attemptId'],sqlHash=SQL_HASH);return await send(dict(taskId=tid,conversationId=cid,protocolVersion='IAM-SIMPLE-1'))
  m=re.fullmatch(r'/api/iam-s1/query/tasks/([^/]+)(?:/(.*))?',p)
  if m:
   task=self.query_tasks.get(m[1]);suffix=m[2]
   if not task:return await send(None,404,'合成：无此查询任务')
   if not self.query_allowed(actor,task['source']) or task['userId']!=ROLES[actor]['id']:return await send(None,403,'合成：当前业务结果读取权限已撤回')
   if method=='GET' and suffix is None and task['status']=='PROCESSING' and not self.hold_queries:
    task['status']='COMPLETED';self.projected.add(task['taskId']);self.messages[task['conversationId']].append(dict(id=len(self.messages[task['conversationId']])+1,role='assistant',content='合成订单金额100元',taskId=task['taskId'],metadata=json.dumps(self.make_result(task),ensure_ascii=False),createdAt=NOW));self.record_transition('QUERY_COMPLETED',taskId=task['taskId'])
   if suffix in ['stream','events']:
    entry['status']=200
    return await r.fulfill(status=200,content_type='text/event-stream',body='event: progress\ndata: '+json.dumps(dict(taskId=task['taskId'],status=task['status'],progressNode='sql_execution',progressMessage='合成：只读执行'))+'\n\n')
   if suffix=='sql':return await send(dict(sql=SQL))
   if suffix=='export.csv':entry['status']=200;return await r.fulfill(status=200,content_type='text/csv',body='total_amount\n100\n')
   if suffix=='cancel':task['status']='CANCELLED';self.record_transition('QUERY_CANCELLED',taskId=task['taskId']);return await send(None)
   if suffix=='resume':task['status']='PROCESSING';self.record_transition('QUERY_RESUMED',taskId=task['taskId']);return await send(None)
   if suffix=='feedback':
    row=next((f for f in self.feedback if f['taskId']==task['taskId']),None)
    if method=='POST':
     row=dict(id=81,taskId=task['taskId'],feedbackType=body.get('feedbackType','DISLIKE'),reasonCode=body.get('reasonCode','UNKNOWN'),comment=body.get('comment','合成核验反馈'),status='PENDING_ATTRIBUTION',rowVersion=(row['rowVersion']+1 if row else 1),revisionId=None,targets=[dict(columnMetaId=204,fieldName='orders.amount')]);self.feedback[:]=[row];self.record_transition('FEEDBACK_CREATED',taskId=task['taskId'],revisionId=task['revisionId'])
    return await send(row or dict(status='NOT_SUBMITTED'))
   return await send(self.make_result(task))
  if p=='/api/admin/governance-feedback/datasources/1':return await send(dict(records=self.feedback,total=len(self.feedback)))
  if p=='/api/admin/governance-feedback/81/review':
   if await deny('governance:field:manage'):return
   row=self.feedback[0]
   if body['expectedVersion']!=row['rowVersion'] or row['status'] not in ['PENDING_ATTRIBUTION','PENDING_REVIEW']:return await send(None,409,'合成：反馈精确版本或处理阶段冲突')
   assert body.get('approved') and body.get('columnMetaId')==204 and body.get('reasonCode')=='FIELD_MEANING' and body.get('scenario') and body.get('expectedResult')
   row.update(status='APPLIED',reasonCode=body['reasonCode'],columnMetaId=204,definitionId=1,reviewNote=body['note'],revisionId=self.query_tasks[row['taskId']]['revisionId'],followupStatus='QUEUED',rowVersion=row['rowVersion']+1,resolution=dict(problem=body['note'],scenario=body['scenario'],expectedResult=body['expectedResult']));self.record_transition('FEEDBACK_ATTRIBUTED',feedbackId=81,columnId=body.get('columnMetaId'),taskId=row['taskId'],originalRevisionId=row['revisionId']);return await send(row)
  if p=='/api/admin/governance-feedback/81/verification':
   if await deny('governance:field:manage'):return
   f=self.feedback[0]
   if body['expectedVersion']!=f['rowVersion'] or f['status']!='APPLIED' or f.get('followupStatus')!='AWAITING_VERIFICATION' or body['targetRevisionId']!=f.get('currentRevisionId'):return await send(None,409,'合成：精确反馈或修复版本已变化')
   if not body.get('confirmed') or body.get('actualResult')!=f['resolution']['expectedResult'] or not body.get('evidenceReference'):return await send(None,400,'合成：实际结果不符合原验收标准，保持待验证')
   assert self.latest_check['revisionId']==f['currentRevisionId'] and f['currentRevisionId']!=f['revisionId']
   f.update(followupStatus='VERIFIED',rowVersion=f['rowVersion']+1);f['resolution']['verification']=clone(body);self.record_transition('FEEDBACK_VERIFIED',feedbackId=81,originalRevisionId=f['revisionId'],targetRevisionId=f['currentRevisionId']);return await send(None)
  if p.startswith('/api/lineage/'):
   if await deny('lineage:view'):return
   tasks=[t for tid,t in self.query_tasks.items() if tid in self.projected]
   if '/impact/' in p:return await send(dict(tableName='orders',columnName='amount',dependentQueryCount=len(tasks),pendingDatasourceQueryCount=len(self.query_tasks)-len(tasks),coverageStartAt=NOW,coverageNote='合成：仅本有状态旅程已投影的受保护任务',recentQueryTaskIds=[t['id'] for t in tasks]))
   return await send([dict(queryTaskId=t['id'],sourceTable='orders',sourceColumn='amount',aliasName='total_amount',expression='SUM(amount)',targetName='__USAGE__',relationType='USAGE',question=t['question'],createdAt=NOW) for t in tasks])
  if p.startswith('/api/admin/audit-logs'):
   if await deny('audit:view'):return
   tasks=list(self.query_tasks.values());rows=[dict(id=8100+t['id'],queryTaskId=t['id'],datasourceId=t['source'],datasourceName=self.sources[t['source']]['name'],userId=t['userId'],username='合成reader',question=t['question'],usedTables='["orders"]',usedFields='["orders.amount"]',isSuccess=t['status']=='COMPLETED',isSlow=False,executionTimeMs=12,rowCount=1,createdAt=NOW) for t in tasks]
   if p.endswith('/stats'):return await send(dict(totalQueries=len(tasks),successCount=sum(t['status']=='COMPLETED' for t in tasks),successRate=100 if tasks else 0,avgExecutionTimeMs=12,slowQueryCount=0,slowQueryRate=0))
   if p.endswith('/slow-queries'):return await send(dict(records=[],total=0))
   if re.fullmatch(r'/api/admin/audit-logs/\d+',p):
    row=next((x for x in rows if x['id']==int(p.split('/')[-1])),None)
    if not row:return await send(None,404,'合成：无此审计记录')
    return await send(dict(row,sqlText=SQL))
   return await send(dict(records=rows,total=len(rows)))
  if p=='/api/admin/operation-logs':
   if await deny('operation-log:view'):return
   rows=[dict(id=i+1,operatorId=ROLES.get(t.get('actor','operator'),ROLES['operator'])['id'],operatorName='合成'+t.get('actor','worker'),operationType=t['event'],targetResource='STATEFUL_JOURNEY',targetId=str(t.get('snapshotId',t.get('docId',t.get('feedbackId','')))),requestMethod='合成回执',requestPath='合成/同状态旅程',requestParams=json.dumps(redact(t),ensure_ascii=False),executionMs=1,isSuccess=True,createdAt=NOW) for i,t in enumerate(self.transitions)]
   return await send(dict(records=rows,total=len(rows)))
  # Own auxiliary facts for the journey, never borrow an unrelated version/issue.
  if p=='/api/admin/glossary':return await send([])
  if re.fullmatch(r'/api/admin/catalog/datasources/\d+/join-paths',p):return await send([])
  if p.endswith('/join-paths/options'):return await send(dict(tables=[]))
  if re.fullmatch(r'/api/admin/snapshots/\d+/quality-check-batches/latest',p):return await send(None)
  if re.fullmatch(r'/api/admin/snapshots/\d+/quality-issues/summary',p):return await send(dict(totalUnresolved=0,statusCounts=dict(OPEN=0,CONFIRMED=0,REOPENED=0),domainCounts=dict(SOURCE=0,SEMANTIC=0),recentIssues=[]))
  if re.fullmatch(r'/api/admin/snapshots/\d+/quality-issues',p):return await send(dict(records=[],total=0))
  # Only route reachability is checked for other modules. Missing auxiliary reads
  # fail explicitly; never borrow an unrelated fixture or fabricate an empty 200.
  if method in ['POST','PUT','PATCH','DELETE']:
   self.unhandled.append(dict(path=p,method=method));return await send(None,404,'本旅程未模拟该写入；禁止假成功')
  entry['auxiliary']=True
  return await send(None,501,'合成终验未覆盖此辅助读取；不是空数据或真实服务结果')
