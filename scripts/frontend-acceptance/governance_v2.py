import sys,asyncio,json,re
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from collection_assets_v2 import StageFixture,COLS,SNAP
from urllib.parse import urlparse,parse_qs
from playwright.async_api import async_playwright
OUT=Path('output/playwright/governance-v2-20261029')
def revision(id=301,status='DRAFT',number=2):return dict(id=id,definitionId=1,bindingId=1,revisionNo=number,status=status,businessName='订单成交金额',meaning='每笔订单实际成交的金额，扣除订单优惠；退款需按业务场景另行处理。',unit='元',currency='CNY',evidence='订单业务手册 v2 第3节；已与财务口径负责人核对',unresolvedItemsJson='[]',contentHash='synthetic-hash',dependencyHash='synthetic-dependency',createdBy=1,createdAt='2026-10-29 09:10:00')
def workspace(col=204,snapshot=11):return dict(datasourceId=1,snapshotId=snapshot,tableMetaId=1,columnMetaId=col,databaseName='sales_analytics',tableName='orders',columnName=next((c['name'] for c in COLS if c['columnMetaId']==col),'amount'),dataType='DECIMAL(12,2)',rawColumnComment='已扣除订单优惠，单位元',governanceStatus='NORMAL',definitionId=1,rowVersion=3,currentBindingId=1,currentApprovedRevisionId=300,workingRevisionId=301,workingStatus='DRAFT',definitionStatus='ACTIVE',bindingStatus='VALID',effectiveStatus='APPROVED',currentApproved=revision(300,'APPROVED',1),draft=revision(),revisions=[revision(),revision(300,'APPROVED',1)],reviews=[dict(id=1,action='APPROVE',reason='与业务资料一致',operatorId=1,createdAt='2026-10-29 09:00:00')],candidates=[dict(id=401,status='READY',businessName='订单成交金额',meaning='AI建议：订单折后成交金额；退款处理口径需人工确认。',evidence='参考字段注释和已审核术语，仍需人工核验',unresolvedItemsJson='["退款是否回冲成交月份"]',createdAt='2026-10-29 09:20:00')])
TASKS=[dict(id=1,columnMetaId=204,tableName='orders',columnName='amount',origin='DISCOVERY',aiStatus='AI_READY',stage='HUMAN_EDITING',reason='已生成候选；请核对单位、口径和原始依据',nextAction='EDIT'),dict(id=2,columnMetaId=205,tableName='orders',columnName='status',origin='FEEDBACK',aiStatus='AI_FAILED',stage='NEEDS_GOVERNANCE',reason='模型服务超时；人工草稿保持不变，可重试本轮',nextAction='EDIT'),dict(id=3,columnMetaId=201,tableName='orders',columnName='order_id',origin='DISCOVERY',aiStatus='HUMAN_READY',stage='AWAITING_REVIEW',reason='待人工核对原始资料及本修订',nextAction='REVIEW'),dict(id=4,businessRevisionId=50,targetType='BUSINESS',tableName='销售订单用途与粒度',columnName='TABLE',origin='DISCOVERY',aiStatus='HUMAN_READY',stage='AWAITING_RECHECK',reason='审核已通过；等待同修订语义复查',nextAction='OPEN_BUSINESS')]
class GovernanceFixture(StageFixture):
 def __init__(self,mode='normal'):
  super().__init__(mode);self.field=workspace();self.actions=[];self.fail_field_read=False
  if mode in ['reviewer','review-conflict']:self.field.update(workingStatus='PENDING_REVIEW',draft=revision(301,'PENDING_REVIEW'))
 async def route(self,route):
  req=route.request;u=urlparse(req.url);p=u.path;q=parse_qs(u.query)
  async def send(data,status=200,message='ok'):
   self.calls.append(dict(path=p,method=req.method,query=q));await route.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=message,data=data),ensure_ascii=False))
  if p=='/api/iam-s1/capabilities' and self.mode in ['field-read','minimal','manager-no-details','reviewer','review-conflict','pauser']:
   codes=['governance:field:view']+([] if self.mode=='minimal' else ['metadata:view'])
   codes+=['governance:field:manage'] if self.mode=='pauser' else ['knowledge:manage','governance:field:manage','glossary:view'] if self.mode=='manager-no-details' else ['knowledge:view','knowledge:approve'] if self.mode in ['reviewer','review-conflict'] else []
   return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=False,globalFunctions=codes,datasourceCapabilities=[dict(datasourceId=1,datasourceName='销售经营',functionCodes=codes,functionNames=[])],queryUse=False,viewSql=False,exportResult=False,permissionRevision=1))
  if p=='/api/iam-s1/capabilities':return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=True,globalFunctions=[],datasourceCapabilities=[dict(datasourceId=1,datasourceName='销售经营',functionCodes=[],functionNames=[]),dict(datasourceId=2,datasourceName='供应链',functionCodes=[],functionNames=[])],queryUse=True,viewSql=True,exportResult=True,permissionRevision=1))
  if self.mode=='minimal' and p.startswith('/api/admin/metadata/snapshots'):return await send(None,403,'合成：没有元数据查看权限')
  if p=='/api/admin/governance-tasks/snapshots/11':
   if self.mode=='task-error':return await send(None,503,'合成任务读取失败')
   rows=[{**r,'tableName':'业务知识任务','stage':'UNAVAILABLE','reason':'业务知识不可读取或权限已变化'} if r.get('targetType')=='BUSINESS' and self.mode in ['field-read','minimal','pauser','manager-no-details'] else r for r in TASKS]
   return await send(dict(records=[] if self.mode=='empty' else rows,total=0 if self.mode=='empty' else len(rows),aiSummary=dict(queued=1 if self.mode=='pauser' else 0,running=0,failed=1)))
  if p.endswith('/discover') or p.endswith('/pause') or re.fullmatch(r'/api/admin/governance-tasks/\d+/retry',p):
   self.actions.append(dict(path=p,method=req.method))
   await asyncio.sleep(.3)
   if self.mode=='conflict':return await send(None,409,'合成：此范围已有并发任务，请刷新')
   return await send(dict(scanned=5,created=2))
  if p=='/api/admin/semantic-checks/snapshots/11':
   self.actions.append(dict(path=p,method=req.method));return await send(dict(id=7,datasourceId=1,snapshotId=11,status='PARTIAL' if self.mode=='partial' else 'SUCCESS',applicableCount=5,validCount=3,missingCount=1,staleCount=1,notApplicableCount=0,errorSummary='合成：部分语义复查失败' if self.mode=='partial' else None,startedAt='2026-10-29 10:00:00'))
  if p=='/api/admin/governance-tasks/rules':return await send([dict(code='FIELD_MEANING',name='业务解释完整性',target='字段',requirement='业务名称、含义及人工依据',version=1)])
  if p=='/api/admin/metadata/snapshots/11/tables':return await send([dict(id=1,tableName='orders',tableComment='销售订单',tableType='BASE TABLE',governanceStatus='NORMAL')])
  if p=='/api/admin/metadata/snapshots/11/tables/orders/columns':return await send([dict(id=c['columnMetaId'],tableMetaId=1,tableName='orders',columnName=c['name'],columnComment=c['comment'],dataType=c['dataType'],isNullable=0,isPrimaryKey=int(c['primaryKey']),ordinalPosition=c['ordinalPosition'],governanceStatus='NORMAL') for c in COLS])
  if p=='/api/admin/field-semantics/snapshots/11/bindings/pending-confirmations':return await send([dict(columnMetaId=204,expectedVersion=3,tableName='orders',columnName='amount',eligible=True),dict(columnMetaId=205,expectedVersion=3,tableName='orders',columnName='status',eligible=True)])
  if p=='/api/admin/field-semantics/snapshots/11/bindings/confirmations':
   self.actions.append(dict(path=p,method=req.method,payload=req.post_data_json));await asyncio.sleep(.25)
   return await send(dict(snapshotId=11,totalCount=2,successCount=1,failedCount=1,items=[dict(columnMetaId=204,success=True,message='已确认'),dict(columnMetaId=205,success=False,message='合成：版本已变化，请逐项核对')]))
  found=re.fullmatch(r'/api/admin/field-semantics/columns/(\d+)(.*)',p)
  if found:
   col=int(found[1]);suffix=found[2];snapshot=int(q.get('snapshotId',['11'])[0])
   if req.method in ['POST','PUT']:
    payload=req.post_data_json if req.post_data else {};self.actions.append(dict(path=p,method=req.method,payload=payload));await asyncio.sleep(.35)
    if suffix=='/review' and self.mode=='review-conflict':return await send(None,409,'合成：待审核修订已变化，审核意见已保留')
    if suffix=='/draft':
     self.field.update(rowVersion=self.field['rowVersion']+1,workingStatus='DRAFT',draft={**revision(),**payload})
    elif suffix=='/submit':self.field.update(rowVersion=self.field['rowVersion']+1,workingStatus='PENDING_REVIEW',draft={**self.field['draft'],'status':'PENDING_REVIEW'})
    elif suffix=='/withdraw':self.field.update(rowVersion=self.field['rowVersion']+1,workingStatus='DRAFT',draft={**self.field['draft'],'status':'DRAFT'})
    elif suffix=='/review':
     if payload.get('action')=='APPROVE':self.field.update(currentApproved={**self.field['draft'],'status':'APPROVED'},currentApprovedRevisionId=self.field['draft']['id'])
     self.field.update(rowVersion=self.field['rowVersion']+1,workingRevisionId=None,workingStatus=None,draft=None)
    elif suffix=='/revoke':self.field.update(rowVersion=self.field['rowVersion']+1,effectiveStatus='NO_APPROVED_EXPLANATION')
    elif suffix=='/candidates':
     self.field['candidates']=[dict(id=402,status='READY',businessName='新AI候选',meaning='合成：新的AI建议，仍需人工确认',createdAt='2026-10-29 10:00:00')]
     if self.mode=='read-recovery':self.fail_field_read=True
    elif suffix.endswith('/adopt'):self.field.update(rowVersion=self.field['rowVersion']+1,workingStatus='DRAFT',draft={**revision(),'meaning':'已采纳候选仍需人审'})
   data={**self.field,'columnMetaId':col,'snapshotId':snapshot,'columnName':next((c['name'] for c in COLS if c['columnMetaId']==col),'amount')}
   if suffix=='/governance-progress':return await send(dict(columnMetaId=col,snapshotId=snapshot,stage='AWAITING_RECHECK' if not self.field.get('draft') else 'AWAITING_REVIEW' if self.field.get('workingStatus')=='PENDING_REVIEW' else 'HUMAN_EDITING',nextAction='EDIT',reason='当前修订仍需人工审核与同版本复查，不代表已发布或已收录',revisionId=301))
   if suffix in ['/details','/usage'] and self.mode in ['field-read','minimal','manager-no-details','pauser']:return await send(None,403,'合成：没有知识明细查看权限')
   if suffix=='/usage':return await send(dict(status='PENDING_UPDATE',message='当前有效解释尚未进入活动构建，需在知识文档中确认发布和构建。'))
   if suffix=='/glossary-options':return await send([dict(termId=1,reviewId=51,name='成交金额',description='订单实际成交口径',synonyms='',dependencies=['column:orders.amount'])])
   if suffix=='':
    if self.fail_field_read:self.fail_field_read=False;return await send(None,503,'合成：AI保存后工作面读取失败')
    data={k:v for k,v in data.items() if k not in ['draft','reviews','revisions','bindings','candidates']}
   return await send(data)
  if p=='/api/admin/semantic-checks/snapshots/11/latest':return await send(None)
  if p=='/api/admin/governance-feedback/datasources/1':return await send(dict(records=[],total=0))
  if p=='/api/admin/business-knowledge/snapshots/11':return await send([dict(id=50,kind='TABLE',knowledgeKey='table:orders',title='销售订单用途与粒度',body=dict(description='订单经营分析',grain='一行一笔订单'),dependencies=['table:orders'],evidence='订单业务手册第2节',unresolvedItems=[],status='APPROVED',effectiveStatus='APPROVED',rowVersion=1,origin='MANUAL')])
  return await super().route(route)
async def main():
 import argparse
 parser=argparse.ArgumentParser();parser.add_argument('--base-url',default='http://127.0.0.1:5173');parser.add_argument('--chromium',default='/usr/bin/chromium');parser.add_argument('--output',default=str(OUT));parser.add_argument('--phase',choices=['tasks','field','all'],default='tasks');args=parser.parse_args()
 if urlparse(args.base_url).hostname not in ['127.0.0.1','localhost','::1']:raise SystemExit('Only loopback frontend is allowed')
 out=Path(args.output);out.mkdir(parents=True,exist_ok=True);checks=[];errors=[];evidence=[]
 def passed(name):checks.append(dict(name=name,status='passed'))
 async with async_playwright() as p:
  browser=await p.chromium.launch(executable_path=args.chromium,headless=True)
  async def open_page(path='/admin/governance/fields?datasourceId=1&snapshotId=11',mode='normal'):
   c=await browser.new_context(viewport=dict(width=1600,height=1050),device_scale_factor=1.5,reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-admin-fixture');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))");f=GovernanceFixture(mode);await c.route(re.compile('^'+re.escape(args.base_url.rstrip('/'))+'/api/'),f.route);page=await c.new_page();page.on('pageerror',lambda e:errors.append(str(e)));page.on('console',lambda m:errors.append(m.text) if m.type=='error' and '[全局错误]' in m.text else None);await page.goto(args.base_url+path);return c,page,f
  async def capture(page,name):
   if await page.locator('.el-message').count():await page.locator('.el-message').last.wait_for(state='hidden',timeout=7000)
   await page.wait_for_timeout(120)
   await page.evaluate("document.getElementById('fixture-label')?.remove();document.body.insertAdjacentHTML('beforeend','<div id=fixture-label style=\"position:fixed;bottom:4px;right:8px;z-index:99999;color:#676d67;font:11px system-ui;pointer-events:none\">第四阶段浏览器验收 · 合成 API 响应</div>')")
   await page.screenshot(path=str(out/name),full_page=True)
  try:
   if args.phase in ['tasks','all']:
    c,page,f=await open_page();await page.wait_for_selector('.workflow-table .el-table__row');assert await page.get_by_role('tab').count()==5;assert '共 4 项任务' in await page.locator('.workflow-status-line').inner_text();assert '待复查' in await page.locator('.workflow-table').inner_text();await capture(page,'01-governance-tasks-mock-desktop.png');passed('五个任务Tab保留，阶段/AI/下一步及服务端统计分开')
    await page.get_by_role('button',name='开始治理',exact=True).click();await page.get_by_role('button',name='开始治理',exact=True).click(force=True);await page.wait_for_timeout(450);assert len(f.actions)==1;passed('发现任务重复点击单次提交')
    await page.get_by_role('button',name='orders.amount',exact=True).focus();await page.keyboard.press('Enter');await page.wait_for_function("location.pathname.endsWith('/field-explanations')");assert 'columnMetaId=204' in page.url and 'snapshotId=11' in page.url;passed('任务以键盘进入精确字段和快照');evidence.append(dict(scenario='tasks',requests=f.calls));await c.close()
    for mode in ['field-read','minimal','pauser','empty','task-error','conflict','partial']:
     c,page,f=await open_page(mode=mode)
     if mode=='minimal':await page.locator('.workflow-scope-message').filter(has_text='当前账号没有 metadata:view').wait_for();assert not any(x['path'].startswith('/api/admin/governance-tasks/snapshots') for x in f.calls);await page.get_by_role('tab',name='反馈审核',exact=True).click();await page.get_by_role('heading',name='问数反馈归因',exact=True).wait_for();assert any(x['path']=='/api/admin/governance-feedback/datasources/1' for x in f.calls);assert not any(x['path'].startswith('/api/admin/metadata/snapshots') for x in f.calls)
     elif mode=='empty':await page.get_by_text(re.compile('当前快照还没有治理任务')).wait_for()
     elif mode=='task-error':await page.get_by_text('合成任务读取失败',exact=True).wait_for()
     else:
      await page.wait_for_selector('.workflow-table .el-table__row')
      if mode=='field-read':assert await page.get_by_role('button',name='开始治理',exact=True).is_disabled()
      elif mode=='pauser':await page.get_by_role('button',name='暂停 AI',exact=True).click();await page.wait_for_timeout(400);assert any(x['path'].endswith('/pause') for x in f.actions);assert await page.get_by_role('button',name='开始治理',exact=True).is_disabled()
      elif mode=='conflict':await page.get_by_role('button',name='开始治理',exact=True).click();await page.locator('.workflow-notice').filter(has_text='合成：此范围已有并发任务').wait_for();assert len(f.actions)==1
      elif mode=='partial':await page.get_by_role('button',name='复查治理结果',exact=True).click();await page.locator('.workflow-notice').filter(has_text='合成：部分语义复查失败').wait_for()
     passed(mode+'任务权限与状态分支');evidence.append(dict(scenario=mode,requests=f.calls));await c.close()
    c,page,f=await open_page();await page.wait_for_selector('.workflow-table .el-table__row')
    for width,height in [(390,844),(768,1024),(1024,768),(1440,900),(844,390)]:
     await page.set_viewport_size(dict(width=width,height=height));await page.wait_for_timeout(80);assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
     if width==390:await capture(page,'02-governance-tasks-mock-mobile.png')
    passed('任务页五个窄屏/桌面视口无整页横向溢出');await c.close()
   if args.phase in ['field','all']:
    path='/admin/semantics/field-explanations?datasourceId=1&snapshotId=11&tableName=orders&columnMetaId=204'
    c,page,f=await open_page(path);await page.get_by_label('字段含义',exact=True).wait_for();assert await page.get_by_role('tab',name='候选与草稿',exact=True).count()==1;await capture(page,'03-field-editor-mock-desktop.png');passed('来源/有效解释与候选草稿分区，三Tab完整')
    await page.get_by_role('button',name='生成 AI 候选',exact=True).click();await page.get_by_label('字段含义',exact=True).fill('合成：生成等待期间新增的人工口径');await page.get_by_role('button',name='生成 AI 候选',exact=True).click(force=True);await page.wait_for_timeout(600);assert await page.get_by_label('字段含义',exact=True).input_value()=='合成：生成等待期间新增的人工口径';assert len([a for a in f.actions if a['path'].endswith('/candidates')])==1;assert await page.get_by_role('button',name='提交审核',exact=True).is_disabled();passed('AI迟到保留人工输入，重复生成及未保存提交保护')
    await page.get_by_role('button',name='保存草稿',exact=True).click();await page.wait_for_timeout(500);assert await page.get_by_label('字段含义',exact=True).input_value()=='合成：生成等待期间新增的人工口径';assert await page.get_by_role('button',name='提交审核',exact=True).is_enabled();await page.get_by_role('button',name='提交审核',exact=True).click();await page.get_by_role('tab',name='人工审核',exact=True).click();await page.locator('.field-review-preview').wait_for();await page.get_by_label('字段解释审核依据',exact=True).wait_for();assert '合成：生成等待期间新增的人工口径' in await page.locator('.field-review-preview').inner_text();await capture(page,'04-field-review-mock-desktop.png');passed('保存后提交，审核展示精确已保存修订')
    await page.get_by_label('字段解释原始资料引用',exact=True).fill('合成业务手册v3第2节');await page.get_by_label('字段解释审核依据',exact=True).fill('已人工逐项核对字段含义与使用条件');await page.get_by_text('已人工核对原始资料；AI 自述不作为确认依据',exact=True).click();await page.get_by_role('button',name='审核通过',exact=True).click();await page.get_by_role('button',name='审核通过',exact=True).click(force=True);await page.wait_for_timeout(600);assert len([a for a in f.actions if a['path'].endswith('/review')])==1;assert not any('rag' in a['path'] or '/publish' in a['path'] for a in f.actions);passed('重复审核单次写入；通过不自动发布或构建')
    await page.get_by_role('button',name='复查当前快照',exact=True).click();await page.locator('.field-semantic-check-summary').wait_for();passed('语义复查单独执行，覆盖状态按响应展示')
    await page.get_by_role('button',name='批量确认来源',exact=True).click();dialog=page.locator('.field-binding-dialog');await dialog.wait_for();await dialog.locator('.el-checkbox').nth(0).click();await dialog.locator('.el-checkbox').nth(1).click();await dialog.get_by_label('本批逐项核验依据',exact=True).fill('合成：已逐字段核对结构与原审核资料');await dialog.get_by_role('button',name='确认所选来源',exact=True).click();await dialog.get_by_role('button',name='确认所选来源',exact=True).click(force=True);await dialog.get_by_text('已完成 1 项，未完成 1 项',exact=True).wait_for();assert '合成：版本已变化，请逐项核对' in await dialog.inner_text();assert len([a for a in f.actions if a['path'].endswith('/confirmations')])==1;assert await page.locator('.admin-shell__content').evaluate("e=>e.style.overflowY==='hidden'");await capture(page,'05-field-binding-partial-mock-desktop.png');await dialog.get_by_role('button',name='关闭',exact=True).click();passed('来源批量部分成功及逐项原因、重复保护与滚动锁');evidence.append(dict(scenario='field-main',requests=f.calls,actions=f.actions));await c.close()
    for mode in ['field-read','manager-no-details','reviewer','review-conflict','read-recovery','minimal']:
     c,page,f=await open_page(path,mode)
     if mode=='minimal':await page.locator('.field-semantic-workspace').get_by_text(re.compile('metadata:view')).last.wait_for();assert not any(re.fullmatch(r'/api/admin/metadata/snapshots/11/tables.*',x['path']) for x in f.calls)
     else:
      await page.get_by_label('字段含义',exact=True).wait_for(state='attached')
      if mode in ['field-read','manager-no-details']:
       assert not any(x['path'].endswith('/details') or x['path'].endswith('/usage') for x in f.calls)
       if mode=='manager-no-details':assert any(x['path'].endswith('/glossary-options') for x in f.calls)
       else:assert await page.get_by_role('button',name='生成 AI 候选',exact=True).is_disabled()
      elif mode=='reviewer':assert await page.get_by_label('字段含义',exact=True).is_disabled();await capture(page,'06-reviewer-readonly-mock-desktop.png')
      elif mode=='review-conflict':
       await page.get_by_label('字段解释原始资料引用',exact=True).fill('合成资料引用');await page.get_by_label('字段解释审核依据',exact=True).fill('需要保留的审核意见');await page.get_by_text('已人工核对原始资料；AI 自述不作为确认依据',exact=True).click();await page.get_by_role('button',name='审核通过',exact=True).click();await page.get_by_text(re.compile('当前版本已变化；人工输入和审核依据已保留')).wait_for();assert await page.get_by_label('字段解释审核依据',exact=True).input_value()=='需要保留的审核意见';assert len([a for a in f.actions if a['path'].endswith('/review')])==1
      elif mode=='read-recovery':
       await page.get_by_label('字段含义',exact=True).fill('合成：失败恢复必须保留');await page.get_by_role('button',name='生成 AI 候选',exact=True).click();await page.get_by_text('合成：AI保存后工作面读取失败',exact=True).wait_for();await page.locator('.field-semantic-workspace .error-state').get_by_role('button',name='重试',exact=True).click();await page.get_by_label('字段含义',exact=True).wait_for();assert await page.get_by_label('字段含义',exact=True).input_value()=='合成：失败恢复必须保留'
     passed(mode+'字段能力/失败恢复分支');evidence.append(dict(scenario=mode,requests=f.calls,actions=f.actions));await c.close()
    c,page,f=await open_page(path);await page.get_by_label('字段含义',exact=True).wait_for();await page.get_by_label('撤销原因',exact=True).fill('仅撤销原字段的原因');await page.get_by_role('button',name='撤销当前解释',exact=True).click();await page.get_by_role('button',name='确认继续',exact=True).wait_for();await page.evaluate("history.pushState({},'',location.href.replace('columnMetaId=204','columnMetaId=999'));dispatchEvent(new PopStateEvent('popstate'))");await page.get_by_text(re.compile('指定字段不属于当前表或无法读取')).wait_for();assert await page.locator('.field-semantic-workspace__content').count()==0;await page.wait_for_selector('.el-message-box',state='hidden');assert not any(a['path'].endswith('/revoke') for a in f.actions);passed('浏览器无效字段导航关闭所属确认并停止原撤销');await c.close()
    c,page,f=await open_page(path);await page.get_by_label('字段含义',exact=True).wait_for()
    for width,height in [(390,844),(768,1024),(1024,768),(1440,900),(844,390)]:
     await page.set_viewport_size(dict(width=width,height=height));await page.wait_for_timeout(100);assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
     if width==390:await capture(page,'07-field-editor-mock-mobile.png')
    passed('字段页五个窄屏/桌面视口无整页溢出');await c.close()
   assert not errors,errors;passed('无未捕获页面异常')
  finally:
   (out/(args.phase+'-browser-verification.json')).write_text(json.dumps(dict(kind='real Vue and Chromium; synthetic API only',checks=checks,browserErrors=errors,requestEvidence=evidence),ensure_ascii=False,indent=2));await browser.close()
 print(json.dumps(dict(passed=len(checks),errors=errors,output=str(out)),ensure_ascii=False))
if __name__=='__main__':asyncio.run(main())
