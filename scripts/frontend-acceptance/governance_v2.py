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
 def __init__(self,mode='normal'):super().__init__(mode);self.field=workspace();self.actions=[]
 async def route(self,route):
  req=route.request;u=urlparse(req.url);p=u.path;q=parse_qs(u.query)
  async def send(data,status=200,message='ok'):
   self.calls.append(dict(path=p,method=req.method,query=q));await route.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=message,data=data),ensure_ascii=False))
  if p=='/api/iam-s1/capabilities' and self.mode in ['field-read','minimal','manager-no-details','reviewer','pauser']:
   codes=['governance:field:view']+([] if self.mode=='minimal' else ['metadata:view'])
   codes+=['governance:field:manage'] if self.mode=='pauser' else ['knowledge:manage','governance:field:manage','glossary:view'] if self.mode=='manager-no-details' else ['knowledge:view','knowledge:approve'] if self.mode=='reviewer' else []
   return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=False,globalFunctions=codes,datasourceCapabilities=[dict(datasourceId=1,datasourceName='销售经营',functionCodes=codes,functionNames=[])],queryUse=False,viewSql=False,exportResult=False,permissionRevision=1))
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
  found=re.fullmatch(r'/api/admin/field-semantics/columns/(\d+)(.*)',p)
  if found:
   col=int(found[1]);suffix=found[2];snapshot=int(q.get('snapshotId',['11'])[0]);data={**self.field,'columnMetaId':col,'snapshotId':snapshot}
   if suffix=='/governance-progress':return await send(dict(columnMetaId=col,snapshotId=snapshot,stage='HUMAN_EDITING',nextAction='EDIT',reason='当前有人工草稿；请补充资料依据后提交审核',revisionId=301))
   if suffix=='/usage':return await send(dict(status='PENDING_UPDATE',message='当前有效解释尚未进入活动构建，需在知识文档中确认发布和构建。'))
   if suffix=='/glossary-options':return await send([dict(termId=1,reviewId=51,name='成交金额',description='订单实际成交口径',synonyms='',dependencies=['column:orders.amount'])])
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
   c=await browser.new_context(viewport=dict(width=1600,height=1050),device_scale_factor=1.5,reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-admin-fixture');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))");f=GovernanceFixture(mode);await c.route(re.compile('^'+re.escape(args.base_url.rstrip('/'))+'/api/'),f.route);page=await c.new_page();page.on('pageerror',lambda e:errors.append(str(e)));await page.goto(args.base_url+path);return c,page,f
  async def capture(page,name):
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
   assert not errors,errors;passed('无未捕获页面异常')
  finally:
   (out/(args.phase+'-browser-verification.json')).write_text(json.dumps(dict(kind='real Vue and Chromium; synthetic API only',checks=checks,browserErrors=errors,requestEvidence=evidence),ensure_ascii=False,indent=2));await browser.close()
 print(json.dumps(dict(passed=len(checks),errors=errors,output=str(out)),ensure_ascii=False))
if __name__=='__main__':asyncio.run(main())
