"""Real Vue/Chromium acceptance using synthetic API responses only; loopback only."""
import sys,asyncio,json,re
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
   rows=[dict(id=1,datasourceId=1,datasourceName='销售经营',triggerType='MANUAL',status='SUCCESS',progressTotal=6,progressCurrent=6,snapshotId=11,startedAt='2026-10-29 09:10:00',finishedAt='2026-10-29 09:10:12'),dict(id=2,datasourceId=1,datasourceName='销售经营',triggerType='SCHEDULED',status='FAILED',errorMessage='合成失败：只读连接超时，请检查连接后重新采集',startedAt='2026-10-28 02:00:00',finishedAt='2026-10-28 02:01:00')]
   return await send(dict(records=rows,total=len(rows)))
  if p=='/api/admin/metadata/sync':self.writes+=1;await asyncio.sleep(.25);return await send(dict(taskId=3))
  if p=='/api/admin/system/sync-schedule':
   if req.method=='PUT':self.writes+=1;return await send({**req.post_data_json,'running':True})
   return await send(dict(cron='30 0 2 * 1 ?',enabled=False,running=False))
  if p=='/api/admin/catalog/published-entities':return await send(dict(datasourceId=1,publishedSnapshotId=11,snapshotVersion=3,syncStatus='SYNCED',tableCount=1,columnCount=5,entities=ENTITIES))
  if re.fullmatch(r'/api/admin/catalog/entities/\d+/overview',p):return await send(overview(int(p.split('/')[-2])))
  if p=='/api/admin/metadata/snapshots':return await send(dict(records=[SNAP,{**SNAP,'id':12,'snapshotVersion':4,'status':'DRAFT'}],total=2))
  if re.fullmatch(r'/api/admin/metadata/snapshots/\d+',p):return await send(dict(snapshot={**SNAP,'id':int(p.split('/')[-1])},tables=[dict(id=1,tableName='orders',tableComment=ENTITIES[0]['description'],tableType='BASE TABLE',governanceStatus='NORMAL')],columns=[dict(id=c['columnMetaId'],tableMetaId=1,tableName='orders',columnName=c['name'],columnComment=c['comment'],dataType=c['dataType'],isNullable=0,isPrimaryKey=int(c['primaryKey']),ordinalPosition=c['ordinalPosition'],governanceStatus='NORMAL') for c in COLS]))
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
    c,page,f=await open_page('/admin/collections?datasourceId=1');await page.wait_for_selector('.el-table__row')
    for width,height in [(390,844),(768,1024),(1024,768),(1440,900),(844,390)]:
     await page.set_viewport_size(dict(width=width,height=height));await page.wait_for_timeout(100);assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
     if width==390:await capture(page,'04-collections-mock-mobile.png')
    passed('采集页五个窄屏与桌面视口无整页溢出');await c.close()
   assert not errors,errors;passed('无未捕获页面异常')
  finally:
   (out/(args.phase+'-browser-verification.json')).write_text(json.dumps(dict(kind='real Vue and Chromium; synthetic API only',checks=checks,browserErrors=errors,requestEvidence=evidence),ensure_ascii=False,indent=2));await browser.close()
 print(json.dumps(dict(passed=len(checks),errors=errors,output=str(out)),ensure_ascii=False))
if __name__=='__main__':asyncio.run(main())
