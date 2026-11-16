"""Real Vue/Chromium acceptance using synthetic API responses only; loopback only."""
import sys,asyncio,json,re,time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from datasource_workspace_v2 import Fixture
from urllib.parse import urlparse,parse_qs
from playwright.async_api import async_playwright
OUT=Path('output/playwright/collection-assets-v2-20261029')
ENTITIES=[dict(id=100,entityType='TABLE',entityUuid='fixture-table',name='orders',displayName='销售订单',fqn='sales.sales_analytics.orders',description='订单交易明细，含订单时间、渠道与成交金额',version=2)]+[dict(id=101+i,entityType='COLUMN',entityUuid='fixture-col-'+str(i),name=name,displayName=label,fqn='sales.sales_analytics.orders.'+name,description=comment,version=2,entityMetadata=json.dumps({'table_name':'orders'})) for i,(name,label,comment) in enumerate([('order_id','订单编号','每笔订单的唯一标识'),('order_date','下单日期','订单创建时间'),('channel','销售渠道','订单来源渠道'),('amount','成交金额','已扣除订单优惠，单位元'),('status','订单状态','当前履约状态')])]
SNAP=dict(id=11,datasourceId=1,datasourceName='销售经营',snapshotVersion=3,status='PUBLISHED',tableCount=1,columnCount=5,createdAt='2026-10-29T09:10:00')
COLS=[dict(entityId=e['id'],columnMetaId=e['id']+100,name=e['name'],displayName=e['displayName'],dataType='DECIMAL(12,2)' if e['name']=='amount' else 'BIGINT' if e['name']=='order_id' else 'VARCHAR(64)',nullable=False,primaryKey=e['name']=='order_id',comment=e['description'],governanceStatus='NORMAL',tableName='orders',ordinalPosition=i+1,usageLimits=[]) for i,e in enumerate(ENTITIES[1:])]
def overview(id):
 e=next(e for e in ENTITIES if e['id']==id);r=dict(entity=e,entityType=e['entityType'],datasource=dict(id=1,name='销售经营',databaseName='sales_analytics'),sourceSnapshot=dict(id=11,version=3,status='PUBLISHED'),publishedSnapshot=dict(id=11,version=3,status='PUBLISHED',entitySyncStatus='SYNCED'),sourceVerified=True,sourceMatchesPublished=True,table=dict(entityId=100,tableName='orders',displayName='销售订单',comment=ENTITIES[0]['description'],tableType='BASE TABLE',governanceStatus='NORMAL'),counts=dict(columns=5,structureRelations=0,terms=1,tags=0,fieldTags=0,lineage=0,downstream=0))
 for k in ['columns','structureRelations','terms','tags','fieldTags','lineage','downstream']:r[k]=dict(status='OK',items=COLS if k=='columns' else [])
 r['terms']['items']=[dict(termId=1,glossaryId=1,name='成交金额',description='订单实际成交的金额口径，退款及取消需在提问时明确。',status='APPROVED',currentlyValid=True,verificationCode='VALID',linkMode='VIA_COLUMNS',viaColumns=['amount'],revision=1)]
 if id!=100:r['column']=next(c for c in COLS if c['entityId']==id)
 return r
class StageFixture(Fixture):
 def __init__(self,mode='normal'):super().__init__();self.mode=mode;self.writes=0
 async def route(self,route):
  req=route.request;u=urlparse(req.url);p=u.path;q=parse_qs(u.query)
  async def send(data,status=200,message='ok'):
   self.calls.append(dict(path=p,method=req.method,query=q));await route.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=message,data=data),ensure_ascii=False))
  if p=='/api/iam-s1/capabilities' and self.mode in ['collect-view','collect-run','metadata-only','no-view']:
   codes={'collect-view':['metadata:collect:view'],'collect-run':['metadata:collect:view','metadata:collect:run'],'metadata-only':['metadata:view'],'no-view':['audit:view']}[self.mode]
   return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=False,globalFunctions=codes,datasourceCapabilities=[dict(datasourceId=1,datasourceName='销售经营',functionCodes=codes,functionNames=[])],queryUse=False,viewSql=False,exportResult=False))
  if p=='/api/admin/datasources/simple':return await send([dict(id=1,name='销售经营',databaseName='sales_analytics'),dict(id=2,name='供应链',databaseName='supply_chain')])
  if p=='/api/admin/metadata/sync-tasks':
   if self.mode=='task-error':return await send(None,503,'合成任务读取失败')
   rows=[dict(id=1,datasourceId=1,datasourceName='销售经营',triggerType='MANUAL',status='SUCCESS',progressTotal=6,progressCurrent=6,snapshotId=11,startedAt='2026-10-29 09:10:00',finishedAt='2026-10-29 09:10:12'),dict(id=2,datasourceId=1,datasourceName='销售经营',triggerType='SCHEDULED',status='FAILED',errorMessage='合成失败：只读连接超时，请检查连接后重新采集',startedAt='2026-10-28 02:00:00',finishedAt='2026-10-28 02:01:00')]
   if self.mode=='task-empty':rows=[]
   if self.mode=='task-running':
    if not hasattr(self,'task_started'):self.task_started=time.monotonic()
    if time.monotonic()-self.task_started<2.5:rows=[{**rows[0],'status':'RUNNING','snapshotId':None,'progressCurrent':2}]
   return await send(dict(records=rows,total=len(rows)))
  if p=='/api/admin/metadata/sync':self.writes+=1;await asyncio.sleep(.25);return await send(dict(taskId=3))
  if p=='/api/admin/system/sync-schedule':
   if req.method=='PUT':self.writes+=1;return await send({**req.post_data_json,'running':True})
   return await send(dict(cron='30 0 2 * 1 ?',enabled=False,running=False))
  if p=='/api/admin/catalog/published-entities':
   if self.mode=='catalog-error':return await send(None,503,'合成目录读取失败')
   if self.mode=='loading':await asyncio.sleep(.5)
   source=int(q.get('datasourceId',['1'])[0]);data=dict(datasourceId=source,publishedSnapshotId=11 if source==1 else 22,snapshotVersion=3,syncStatus='FAILED' if self.mode=='sync-failed' else 'SYNCED',tableCount=1,columnCount=5,entities=[] if self.mode in ['empty','unpublished'] else ENTITIES if source==1 else [])
   if self.mode=='unpublished':data.update(publishedSnapshotId=None,snapshotVersion=None)
   return await send(data)
  if re.fullmatch(r'/api/admin/catalog/entities/\d+/overview',p):
   if self.mode=='entity-error':return await send(None,503,'合成对象读取失败')
   data=overview(int(p.split('/')[-2]))
   if self.mode=='partial':data['terms']=dict(status='FORBIDDEN',message='合成：没有术语查看权限',items=[]);data['lineage']=dict(status='ERROR',message='合成：血缘读取失败',items=[])
   if self.mode=='source-diff':data.update(sourceMatchesPublished=False,publishedSnapshot=dict(id=12,version=4,status='PUBLISHED',entitySyncStatus='SYNCING'))
   return await send(data)
  if p=='/api/admin/metadata/snapshots':
   if self.mode=='snapshot-error':return await send(None,503,'合成快照列表读取失败')
   source=int(q.get('datasourceId',['1'])[0]);snap={**SNAP,'datasourceId':source,'id':11 if source==1 else 22}
   return await send(dict(records=[snap,{**snap,'id':12 if source==1 else 23,'snapshotVersion':4,'status':'DRAFT'}],total=2))
  if re.fullmatch(r'/api/admin/metadata/snapshots/\d+',p):
   source=2 if int(p.split('/')[-1]) in [22,23] else 1
   return await send(dict(snapshot={**SNAP,'datasourceId':source,'id':int(p.split('/')[-1]),'snapshotVersion':4 if p.endswith('/12') else 3},tables=[dict(id=1,tableName='orders' if source==1 else 'inventory',tableComment=ENTITIES[0]['description'],tableType='BASE TABLE',governanceStatus='NORMAL')],columns=[dict(id=c['columnMetaId'],tableMetaId=1,tableName='orders' if source==1 else 'inventory',columnName=c['name'],columnComment=c['comment'],dataType=c['dataType'],isNullable=0,isPrimaryKey=int(c['primaryKey']),ordinalPosition=c['ordinalPosition'],governanceStatus='NORMAL') for c in COLS]))
  return await super().route(route)
async def main():
 import argparse
 parser=argparse.ArgumentParser();parser.add_argument('--base-url',default='http://127.0.0.1:5173');parser.add_argument('--chromium',default='/usr/bin/chromium');parser.add_argument('--phase',choices=['collection','assets','all'],default='collection');parser.add_argument('--output',default=str(OUT));args=parser.parse_args()
 if urlparse(args.base_url).hostname not in ['127.0.0.1','localhost','::1']:raise SystemExit('Only loopback is allowed')
 out=Path(args.output);out.mkdir(parents=True,exist_ok=True);checks=[];errors=[];evidence=[]
 def passed(name):checks.append(dict(name=name,status='passed'))
 async with async_playwright() as p:
  browser=await p.chromium.launch(executable_path=args.chromium,headless=True)
  async def open_page(path,mode='normal'):
   context=await browser.new_context(viewport=dict(width=1600,height=1050),device_scale_factor=1.5,reduced_motion='reduce');await context.add_init_script("localStorage.setItem('dataocean_token','synthetic-admin-fixture');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))")
   fixture=StageFixture(mode);await context.route(re.compile('^'+re.escape(args.base_url.rstrip('/'))+'/api/'),fixture.route);page=await context.new_page();page.on('pageerror',lambda e:errors.append(str(e)));await page.goto(args.base_url+path);return context,page,fixture
  async def capture(page,name):
   await page.wait_for_timeout(160)
   await page.evaluate("document.getElementById('fixture-label')?.remove();document.body.insertAdjacentHTML('beforeend','<div id=fixture-label style=\"position:fixed;bottom:4px;right:8px;z-index:99999;color:#676d67;font:11px system-ui;pointer-events:none\">第三阶段浏览器验收 · 合成 API 响应</div>')")
   await page.screenshot(path=str(out/name),full_page=True)
  try:
   if args.phase in ['collection','all']:
    c,page,f=await open_page('/admin/collections?datasourceId=1');await page.wait_for_selector('.el-table__row');assert await page.locator('.collection-purpose > div').count()==3
    assert '采集已完成，请核对生成快照' in await page.locator('.collections-task').inner_text();await capture(page,'01-collections-mock-desktop.png');passed('任务层级、精确快照和失败原因')
    await page.get_by_role('button',name='发起全量采集',exact=True).click();await page.locator('.collection-dialog').wait_for();assert await page.locator('.admin-shell__content').evaluate("e=>e.style.overflowY==='hidden'");await capture(page,'02-start-collection-mock-desktop.png')
    await page.get_by_role('button',name='开始采集',exact=True).click();await page.get_by_role('button',name='开始采集',exact=True).click(force=True);await page.wait_for_timeout(500);assert f.writes==1;passed('采集表单锁滚动、重复点击单次提交')
    await page.get_by_role('tab',name='同步计划',exact=True).click();await page.get_by_label('Cron 表达式',exact=True).wait_for();assert await page.get_by_label('Cron 表达式',exact=True).input_value()=='30 0 2 * 1 ?';assert '调度未注册' in await page.locator('.schedule-page').inner_text();await capture(page,'03-schedule-mock-desktop.png')
    await page.locator('.schedule-page .el-switch').click();await page.get_by_role('button',name='保存配置',exact=True).click();await page.wait_for_timeout(150);assert await page.get_by_label('Cron 表达式',exact=True).input_value()=='30 0 2 * 1 ?';passed('自定义Cron只改开关无损保存、调度状态准确')
    evidence.append(dict(scenario='collection-admin',requests=f.calls));await c.close()
    for mode in ['collect-view','collect-run']:
     c,page,f=await open_page('/admin/collections?datasourceId=1',mode);await page.wait_for_selector('.el-table__row');assert not any(x['path']=='/api/admin/datasources/simple' for x in f.calls)
     assert await page.get_by_role('link',name='检查连接',exact=True).count()==0;assert await page.get_by_role('link',name='查看生成快照',exact=True).count()==0
     if mode=='collect-view':assert await page.get_by_role('button',name='发起全量采集',exact=True).count()==0
     else:await page.get_by_role('button',name='发起全量采集',exact=True).click();await page.get_by_role('button',name='开始采集',exact=True).click();await page.wait_for_timeout(450);assert f.writes==1
     await page.get_by_role('tab',name='同步计划',exact=True).click();await page.get_by_text('只读计划。',exact=False).wait_for();assert await page.get_by_role('button',name='保存配置',exact=True).count()==0;passed(mode+'独立能力与全局计划只读');evidence.append(dict(scenario=mode,requests=f.calls));await c.close()
    for mode in ['task-empty','task-error','task-running']:
     c,page,f=await open_page('/admin/collections?datasourceId=1',mode)
     if mode=='task-empty':await page.get_by_text('当前数据源范围内暂无采集任务。',exact=True).wait_for()
     elif mode=='task-error':await page.get_by_text('合成任务读取失败',exact=True).wait_for()
     else:await page.get_by_text('2 / 6',exact=True).wait_for();await page.get_by_text('采集已完成，请核对生成快照',exact=True).wait_for(timeout=8000);assert len([x for x in f.calls if x['path']=='/api/admin/metadata/sync-tasks'])>=2
     passed(mode+'任务分支与运行任务轮询');await c.close()
    c,page,f=await open_page('/admin/collections?datasourceId=1');await page.wait_for_selector('.el-table__row')
    for width,height in [(390,844),(768,1024),(1024,768),(1440,900),(844,390)]:
     await page.set_viewport_size(dict(width=width,height=height));await page.wait_for_timeout(100);assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
     if width==390:await capture(page,'04-collections-mock-mobile.png')
    passed('采集页五个窄屏与桌面视口无整页溢出');await c.close()
   if args.phase in ['assets','all']:
    c,page,f=await open_page('/admin/assets?datasourceId=1','metadata-only');await page.wait_for_selector('.el-table__row');assert '已发布快照 v3' in await page.locator('.assets-panel__scope-note').inner_text();assert not any(x['path'].endswith('/readiness') or x['path']=='/api/admin/datasources/simple' for x in f.calls);assert await page.locator('.el-table__row td').nth(1).inner_text()=='5';await capture(page,'05-catalog-mock-desktop.png');passed('仅元数据能力可读正式目录，来源和同步状态可见')
    await page.get_by_label('搜索已发布对象',exact=True).fill('销售订单');await page.wait_for_function("new URLSearchParams(location.search).get('q')==='销售订单'");assert await page.locator('.el-table__row').count()==1
    await page.get_by_role('button',name='销售订单',exact=True).focus();await page.keyboard.press('Enter');await page.wait_for_selector('.entity-detail__panel');assert '与发布目标一致' in await page.locator('.entity-detail__meta').inner_text();assert await page.get_by_role('button',name='查看字段解释',exact=True).is_disabled();await capture(page,'06-table-object-mock-desktop.png');passed('目录搜索URL、对象深链、精确来源和受限维护入口')
    await page.get_by_role('button',name='查看字段',exact=True).nth(3).click();await page.wait_for_function("location.pathname.endsWith('/104')");await page.wait_for_selector('.entity-detail__summary');assert '成交金额' in await page.locator('h1').inner_text();await capture(page,'07-column-object-mock-desktop.png')
    await page.get_by_role('button',name='返回目录',exact=True).click();await page.wait_for_selector('.assets-panel');assert await page.get_by_label('搜索已发布对象',exact=True).input_value()=='销售订单';passed('表内打开字段、返回保留目录筛选');evidence.append(dict(scenario='metadata-only',requests=f.calls));await c.close()
    c,page,f=await open_page('/admin/assets?datasourceId=1&view=tables&snapshotId=99','metadata-only');await page.wait_for_selector('.table-item');assert any(x['path']=='/api/admin/metadata/snapshots/99' for x in f.calls);assert 'orders' in await page.locator('.column-detail').inner_text();await capture(page,'08-snapshot-structure-mock-desktop.png');passed('首批外显式快照精确读取、字段结构和未采集统计')
    await page.get_by_label('搜索快照数据表',exact=True).fill('不存在');assert await page.locator('.table-item').count()==0;await page.get_by_label('搜索快照数据表',exact=True).fill('orders');assert await page.locator('.table-item').count()==1;passed('快照表搜索保持精确选定结构');evidence.append(dict(scenario='snapshot-history',requests=f.calls));await c.close()
    c,page,f=await open_page('/admin/assets?datasourceId=2&view=tables&snapshotId=22');await page.wait_for_selector('.table-item');assert 'inventory' in await page.locator('.column-detail').inner_text();assert 'datasourceId=2' in page.url;assert any(x['path']=='/api/admin/metadata/snapshots/22' for x in f.calls);passed('双源有权时结构深链从偏好源同步到指定源');evidence.append(dict(scenario='authorized-source-deeplink',requests=f.calls));await c.close()
    for view in ['catalog','tables']:
     c,page,f=await open_page('/admin/assets?datasourceId=2&view='+view,'metadata-only');await page.get_by_text('没有指定数据源的元数据查看权限，未改用其他数据源。',exact=True).wait_for();assert not any(x['path']=='/api/admin/catalog/published-entities' for x in f.calls);assert await page.locator('.table-item').count()==0;passed('显式无权源不回退偏好源 '+view);await c.close()
    for mode in ['empty','unpublished','catalog-error','sync-failed','loading']:
     c,page,f=await open_page('/admin/assets?datasourceId=1',mode)
     if mode=='empty':await page.get_by_text('当前已发布范围内没有表对象。',exact=True).wait_for()
     elif mode=='unpublished':await page.get_by_text('当前数据源没有已发布快照，暂无可浏览的已发布对象。',exact=True).wait_for()
     elif mode=='catalog-error':await page.get_by_text('合成目录读取失败',exact=True).wait_for()
     elif mode=='sync-failed':await page.get_by_text(re.compile('目录同步失败，列表可能')).wait_for();await page.wait_for_selector('.el-table__row')
     else:await page.wait_for_selector('.assets-panel__body .loading-state');await page.wait_for_selector('.el-table__row')
     passed(mode+'目录状态分支');await c.close()
    c,page,f=await open_page('/admin/assets/entities/100?datasourceId=1','partial');await page.wait_for_selector('.entity-detail__panel');await page.get_by_role('tab',name=re.compile('业务术语')).click();await page.get_by_text('合成：没有术语查看权限',exact=True).wait_for();await page.get_by_role('tab',name=re.compile('^血缘')).click();await page.get_by_text('合成：血缘读取失败',exact=True).wait_for();passed('关联分区无权和读取失败不转换为零');await c.close()
    c,page,f=await open_page('/admin/assets/entities/100?datasourceId=1','source-diff');await page.get_by_text(re.compile('详情按来源快照 v3')).wait_for();await capture(page,'09-source-difference-mock-desktop.png');passed('来源快照与当前发布目标不一致明确说明');await c.close()
    c,page,f=await open_page('/admin/assets?datasourceId=1&view=tables','snapshot-error');await page.locator('.table-explorer-page .error-message').filter(has_text='合成快照列表读取失败').wait_for();f.mode='normal';await page.locator('.table-explorer-page .error-state button').click();await page.wait_for_selector('.table-item');assert await page.locator('.column-detail .el-table__row').count()==5;passed('快照列表失败后重试恢复字段结构');await c.close()
    for path,selector,filename in [('/admin/assets?datasourceId=1','.assets-panel','10-catalog-mock-mobile.png'),('/admin/assets/entities/100?datasourceId=1','.entity-detail__panel','11-object-mock-mobile.png'),('/admin/assets?datasourceId=1&view=tables&snapshotId=11','.table-item','12-structure-mock-mobile.png')]:
     c,page,f=await open_page(path);await page.wait_for_selector(selector)
     for width,height in [(390,844),(768,1024),(1024,768),(1440,900),(844,390)]:
      await page.set_viewport_size(dict(width=width,height=height));await page.wait_for_timeout(100);assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
      if width==390:await capture(page,filename)
     passed(path.split('?')[0]+'五个窄屏与桌面视口无整页溢出');await c.close()
   assert not errors,errors;passed('无未捕获页面异常')
  finally:
   (out/(args.phase+'-browser-verification.json')).write_text(json.dumps(dict(kind='real Vue and Chromium; synthetic API only',checks=checks,browserErrors=errors,requestEvidence=evidence),ensure_ascii=False,indent=2));await browser.close()
 print(json.dumps(dict(passed=len(checks),errors=errors,output=str(out)),ensure_ascii=False))
if __name__=='__main__':asyncio.run(main())
