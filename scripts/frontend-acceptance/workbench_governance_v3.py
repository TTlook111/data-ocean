"""Real Vue/Chromium; every API is intercepted with synthetic data. Never real integration."""
import asyncio,json,re,argparse
from pathlib import Path
from urllib.parse import urlparse,parse_qs
from playwright.async_api import async_playwright
BASE='http://127.0.0.1:5193'
OUT=Path('output/playwright/workbench-governance-v3-20261031')
SOURCES=[dict(id=i,name=n,databaseName='synthetic') for i,n in [(2,'演示交易库'),(9,'演示供应链库'),(5,'演示商品库')]]
CODES=['datasource:view','metadata:view','metadata:release:view','governance:field:view','knowledge:view','governance:view','governance:issue:view','lineage:view']
READINESS=[dict(datasourceId=2,datasourceName='演示交易库',askable=False,stage='SNAPSHOT_PENDING',stageLabel='快照待发布',latestCollectedSnapshotId=31,latestCollectedSnapshotVersion=4,connectionReady=True,metadataReady=False,governanceReady=True,knowledgeReady=False,permissionReady=False,blockReasons=[dict(code='SNAPSHOT_NOT_PUBLISHED',message='尚未发布元数据快照',ownerRole='治理负责人',actionText='发布快照',actionPath='/untrusted')]),dict(datasourceId=9,datasourceName='演示供应链库',askable=False,stage='CONNECTION_CHECK_REQUIRED',stageLabel='连接待处理',connectionReady=False,metadataReady=False,governanceReady=True,knowledgeReady=False,permissionReady=False,blockReasons=[dict(code='CONNECTION_NOT_HEALTHY',message='连接未通过健康检查',ownerRole='数据管理员',actionText='测试连接')]),dict(datasourceId=5,datasourceName='演示商品库',askable=True,stage='ASKABLE',stageLabel='可询问',latestCollectedSnapshotId=41,latestCollectedSnapshotVersion=1,publishedSnapshotId=41,snapshotVersion=1,connectionReady=True,metadataReady=True,governanceReady=True,knowledgeReady=False,permissionReady=True,blockReasons=[])]
QUEUE=[dict(id=101,requesterId=7,datasourceId=2,datasourceName='演示交易库',metadataSnapshotId=30,tableName='orders',requestedColumns=['order_id','amount'],rowScope='ALL',purpose='合成：核对订单字段与汇总范围',status='PENDING')]
class Fixture:
 def __init__(self,mode='normal'):self.mode=mode;self.calls=[];self.unhandled=[];self.stats_calls=0
 def codes(self):return [] if self.mode=='approval' else [x for x in CODES if not(self.mode=='limited' and x=='metadata:release:view') and not(self.mode=='field-only' and x=='datasource:view')]
 async def route(self,r):
  u=urlparse(r.request.url);p=u.path;q=parse_qs(u.query);self.calls.append(dict(path=p,query=q,method=r.request.method))
  async def send(data,status=200,message='ok'):await r.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=message,data=data),ensure_ascii=False))
  if r.request.method!='GET':self.unhandled.append(p+' '+r.request.method);return await send(None,405,'合成：本轮只读')
  if p=='/api/auth/me':return await send(dict(id=1,username='synthetic-v3',realName='合成检查',passwordChanged=True))
  if p=='/api/iam-s1/capabilities':return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=False,globalFunctions=['admin:workbench:view']+(['security:approval:view'] if self.mode=='approval' else []),datasourceCapabilities=[dict(datasourceId=s['id'],datasourceName=s['name'],functionCodes=self.codes(),functionNames=[]) for s in SOURCES],queryUse=False,viewSql=False,export=False,permissionRevision=1))
  if p=='/api/notifications/unread-count':return await send(dict(count=0))
  if p=='/api/notifications':return await send(dict(records=[],total=0))
  if p=='/api/admin/dashboard/stats':
   self.stats_calls+=1
   if self.mode=='403':return await send(None,403,'合成：工作台访问被拒绝')
   if self.mode=='late-stat' and self.stats_calls==1:
    await asyncio.sleep(.8);return await send(dict(openIssues=901,recentActivities=[]))
   if self.mode=='late-stat':return await send(dict(openIssues=7,recentActivities=[]))
   return await send(dict(openIssues=0 if self.mode=='empty' else 15,recentActivities=[]))
  if p=='/api/admin/datasources/simple':return await send([] if self.mode=='empty' else SOURCES)
  if p=='/api/admin/datasources/readiness/batch':
   if self.mode=='readiness-error':return await send(None,503,'合成：准备回执读取失败')
   return await send(READINESS[:1] if self.mode=='partial' else READINESS)
  if p=='/api/iam-s1/access-requests/queue':return await send(dict(records=[] if self.mode=='empty' else QUEUE,total=0 if self.mode=='empty' else len(QUEUE)))
  if p=='/api/admin/metadata/snapshots':
   ds=int(q.get('datasourceId',['2'])[0]);snap=31 if ds==2 else 41
   return await send(dict(records=[dict(id=snap,datasourceId=ds,snapshotVersion=4 if ds==2 else 1,status='DRAFT' if ds==2 else 'PUBLISHED',tableCount=4,columnCount=8,createdAt='2026-10-31T10:00:00')],total=1))
  if p=='/api/admin/knowledge/docs':return await send(dict(records=[],total=0))
  if p.startswith('/api/admin/datasources/') and p.endswith('/published-snapshot'):return await send(None)
  # Auxiliary read-only lists on an observed navigation destination are explicit empty fixtures.
  if p.startswith('/api/admin/metadata/snapshots/') and p.endswith(('/review-records','/version-history','/diff')):return await send([])
  if p.startswith('/api/admin/snapshots/') and p.endswith(('/quality-check-batches/latest','/quality-rules/summary')):return await send(None)
  self.unhandled.append(p);return await send(None,503,'合成：未提供此辅助读取，未连接真实服务')
async def run():
 OUT.mkdir(parents=True,exist_ok=True);checks=[];errors=[]
 def passed(name):checks.append(dict(name=name,status='PASS'))
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/usr/bin/chromium',args=['--no-sandbox','--disable-dev-shm-usage'])
  async def open_page(mode='normal',width=1280,path='/admin/workbench'):
   c=await b.new_context(viewport=dict(width=width,height=720 if width==1280 else 900),reduced_motion='reduce')
   await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-v3');localStorage.setItem('dataocean_user',JSON.stringify({username:'synthetic-v3',passwordChanged:true}));")
   f=Fixture(mode);await c.route(BASE+'/api/**',f.route);p=await c.new_page();p.on('pageerror',lambda e:errors.append(str(e)));await p.goto(BASE+path);await p.locator('.workbench-page').wait_for();await p.wait_for_timeout(300)
   await p.evaluate("()=>{const x=document.createElement('span');x.textContent='合成API · 非真实联调';x.style.cssText='position:fixed;bottom:3px;right:12px;font:10px sans-serif;color:#87452c;background:#f6ebe4;z-index:99999;padding:3px 7px;pointer-events:none';document.body.append(x)}")
   return c,p,f
  for width in [1280,1440]:
   for mode in ['normal','empty','limited','approval','field-only','partial','readiness-error','403']:
    c,p,f=await open_page(mode,width)
    assert await p.evaluate('document.documentElement.scrollWidth<=innerWidth')
    if mode=='normal':
     await p.screenshot(path=str(OUT/f'workbench-normal-{width}.png'),full_page=True)
     assert await p.locator('[data-source-choice]').count()==3
     await p.locator('[data-source-choice="5"]').click();await p.wait_for_url('**/admin/workbench?focusSourceId=5');assert '无活动索引' in await p.locator('[data-source-choice="5"]').inner_text();assert '基础条件满足' in await p.locator('[data-source-detail]').inner_text()
     await p.reload();await p.locator('[data-source-detail]').wait_for();assert '演示商品库' in await p.locator('[data-source-detail]').inner_text()
     passed(f'{width}来源选择/刷新保留、基础条件与索引独立')
     await p.locator('[data-source-choice="2"]').click();await p.wait_for_url('**focusSourceId=2');await p.get_by_role('button',name='准备条件说明',exact=True).focus();await p.keyboard.press('Enter');await p.get_by_role('dialog').wait_for();assert '不代表你本人有权问数' in await p.get_by_role('dialog').inner_text();await p.keyboard.press('Escape');await p.wait_for_timeout(200)
     assert await p.get_by_role('button',name='准备条件说明',exact=True).evaluate('(e)=>document.activeElement===e')
     passed(f'{width}说明键盘打开/取消/焦点返回')
    elif mode=='limited':assert await p.locator('[data-source-primary]').is_disabled() and '元数据版本查看' in await p.locator('.cap-hint').inner_text()
    elif mode=='approval':assert await p.locator('[data-approval-preview]').count()==1 and not any('/datasources' in x['path'] or '/metadata/' in x['path'] for x in f.calls)
    elif mode=='field-only':assert await p.locator('[data-source-choice]').count()==0 and not any(x['path'].endswith('/datasources/simple') for x in f.calls)
    elif mode=='partial':await p.locator('[data-source-choice="9"]').click();assert '#31' not in await p.locator('[data-source-detail]').inner_text() and '待确认' in await p.locator('[data-source-detail]').inner_text()
    elif mode=='readiness-error':assert await p.locator('.condition.unknown').count()==15 and '准备情况暂不可用' in await p.locator('.workbench-page').inner_text()
    elif mode=='403':assert await p.locator('.noaccess').is_visible() and await p.locator('[data-source-choice]').count()==0
    else:assert await p.locator('.empty-work').is_visible()
    await p.screenshot(path=str(OUT/f'workbench-{"keyboard" if mode=="normal" else mode}-{width}.png'),full_page=True)
    assert not f.unhandled,f.unhandled
    passed(f'{width}工作台{mode}实际Vue只读状态')
    await c.close()
  c,p,f=await open_page(path='/admin/workbench?focusSourceId=777')
  assert '指定来源不在当前有权列表中' in await p.locator('.detail-pane').inner_text() and await p.locator('[data-source-primary]').count()==0
  await p.locator('[data-source-choice="2"]').click();await p.wait_for_url('**focusSourceId=2');assert await p.locator('[data-source-primary]').is_enabled();passed('无权旧定位拒绝后显式重选恢复')
  await c.close()
  c,p,f=await open_page('403');f.mode='normal';await p.locator('.noaccess').get_by_role('button',name='重新核验',exact=True).click();await p.locator('[data-source-detail]').wait_for();assert await p.locator('[data-source-choice]').count()==3;passed('403明确拒绝后恢复可重读，不永远加载');await c.close()
  c,p,f=await open_page('late-stat');await p.evaluate("async()=>{const {useAuthStore}=await import('/src/stores/auth.ts');useAuthStore().token='synthetic-v3-B'}");await p.wait_for_timeout(1100);assert '901' not in await p.locator('.workbench-page').inner_text() and await p.locator('.board-bottom b').inner_text()=='7';passed('同范围换合成会话后旧统计不得回填');await c.close()
  await b.close()
 result=dict(synthetic=True,realBackendTested=False,checks=checks,browserErrors=errors);(OUT/'browser-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));assert not errors
if __name__=='__main__':asyncio.run(run())
