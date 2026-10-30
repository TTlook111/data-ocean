"""Chromium acceptance of the real admin UI with synthetic API responses only.
No real datasource, backend, credentials or paid infrastructure is used.
"""
import asyncio,json,re
from pathlib import Path
from urllib.parse import urlparse,parse_qs
from playwright.async_api import async_playwright
OUT=Path('output/playwright/admin-v2-20261029')
SOURCES=[{'id':1,'name':'销售经营','description':'订单、商品与渠道的经营分析数据','dbType':'MYSQL','host':'sales.internal','port':3306,'databaseName':'sales_analytics','charset':'utf8mb4','username':'fixture_readonly','status':1,'healthStatus':'HEALTHY'},{'id':2,'name':'供应链','description':'供应商、采购与库存；待补业务知识','dbType':'MYSQL','host':'supply.internal','port':3306,'databaseName':'supply_chain','charset':'utf8mb4','username':'fixture_readonly','status':1,'healthStatus':'HEALTHY'},{'id':3,'name':'客户服务','description':'尚未采集元数据','dbType':'MYSQL','host':'service.internal','port':3306,'databaseName':'customer_service','charset':'utf8mb4','username':'fixture_readonly','status':1,'healthStatus':'UNKNOWN'}]
READY={'datasourceId':1,'datasourceName':'销售经营','askable':True,'stage':'ASKABLE','stageLabel':'可问数','progress':100,'publishedSnapshotId':11,'snapshotVersion':3,'latestCollectedSnapshotId':12,'latestCollectedSnapshotVersion':4,'ragBuildId':'fixture-active-build','ragSourceSnapshotId':11,'ragSourceSnapshotVersion':3,'ragStatus':'ACTIVE','ragStale':False,'publishedKnowledgeDocId':21,'knowledgeVersion':2,'connectionReady':True,'metadataReady':True,'governanceReady':True,'knowledgeReady':True,'permissionReady':True,'blockReasons':[]}
READINESS={1:READY,2:{**READY,'datasourceId':2,'datasourceName':'供应链','askable':False,'stage':'KNOWLEDGE_REQUIRED','stageLabel':'知识待准备','progress':70,'latestCollectedSnapshotId':22,'latestCollectedSnapshotVersion':2,'publishedSnapshotId':22,'snapshotVersion':2,'knowledgeReady':False,'publishedKnowledgeDocId':None,'ragBuildId':None,'ragStatus':'NOT_BUILT','ragSourceSnapshotId':None,'ragSourceSnapshotVersion':None,'blockReasons':[{'code':'KNOWLEDGE_NOT_PUBLISHED','message':'知识文档尚未发布或索引未就绪','ownerRole':'知识维护者','actionText':'完善业务知识并发布'}]},3:{**READY,'datasourceId':3,'datasourceName':'客户服务','askable':False,'stage':'CONNECTION_REQUIRED','stageLabel':'连接待检查','progress':0,'connectionReady':False,'metadataReady':False,'governanceReady':False,'knowledgeReady':False,'permissionReady':False,'publishedSnapshotId':None,'snapshotVersion':None,'latestCollectedSnapshotId':None,'latestCollectedSnapshotVersion':None,'ragBuildId':None,'ragStatus':None,'ragSourceSnapshotId':None,'ragSourceSnapshotVersion':None,'blockReasons':[{'code':'CONNECTION_NOT_HEALTHY','message':'连接状态尚未确认','ownerRole':'数据源维护者','actionText':'测试连接'}]}}
READINESS[1].update(ragStale=True,ragNotice='RAG 来源快照 v3 落后于最新采集/发布 v4；仅用于规划，最终 SQL 仍按当前快照和权限校验。')
READINESS[2].update(stage='GOVERNANCE_BLOCKED',stageLabel='治理阻塞',governanceReady=False,ragStatus='NONE',ragNotice='尚无生效 RAG build；问数仍可使用当前快照和 S1 权限校验。',blockReasons=[{'code':'BLOCKING_GOVERNANCE_ISSUES','message':'存在 3 条高危未解决治理问题','ownerRole':'数据管理员','actionText':'处理治理问题'}])
READINESS[3].update(stage='CONNECTION_CHECK_REQUIRED',stageLabel='连接待处理',governanceReady=True,ragStatus='NONE',ragNotice='尚无生效 RAG build；问数仍可使用当前快照和 S1 权限校验。',blockReasons=[{'code':'CONNECTION_NOT_HEALTHY','message':'数据源连接未通过健康检查','ownerRole':'数据管理员','actionText':'测试连接'},{'code':'SNAPSHOT_NOT_PUBLISHED','message':'尚未发布元数据快照','ownerRole':'治理负责人','actionText':'发布快照'},{'code':'QUERY_PERMISSION_NOT_CONFIGURED','message':'尚未配置有效查询授权','ownerRole':'数据安全管理员','actionText':'配置数据源权限'}])
class Fixture:
 def __init__(self):self.calls=[];self.mode='normal'
 async def route(self,route):
  req=route.request;url=urlparse(req.url);p=url.path;self.calls.append({'path':p,'method':req.method});data={};status=200
  if p=='/api/auth/me':data={'id':1,'username':'fixture_manager','realName':'演示管理员','passwordChanged':True}
  elif p=='/api/iam-s1/capabilities':data={'protocolVersion':'IAM-SIMPLE-1','userId':1,'systemAdmin':True,'globalFunctions':[],'datasourceCapabilities':[],'queryUse':True,'viewSql':True,'export':True,'permissionRevision':12}
  elif p=='/api/notifications/unread-count':data={'count':0}
  elif p=='/api/notifications':data={'records':[],'total':0}
  elif p=='/api/admin/datasources':
   query=parse_qs(url.query);rows=[r for r in SOURCES if query.get('name',[''])[0] in r['name']];data={'records':rows,'total':len(rows)}
  elif re.fullmatch(r'/api/admin/datasources/\d+',p):data=next(s for s in SOURCES if s['id']==int(p.split('/')[-1]))
  elif p.endswith('/readiness'):data=READINESS[int(p.split('/')[-2])]
  elif p=='/api/admin/metadata/snapshots':
   source=int(parse_qs(url.query).get('datasourceId',['1'])[0]);data={'records':[] if source==3 else [{'id':READINESS[source]['publishedSnapshotId'],'datasourceId':source,'snapshotVersion':READINESS[source]['snapshotVersion'],'status':'PUBLISHED','tableCount':8,'columnCount':64,'createdAt':'2026-10-29T09:10:00'},{'id':12,'datasourceId':source,'snapshotVersion':4,'status':'DRAFT','tableCount':8,'columnCount':66,'createdAt':'2026-10-29T10:10:00'}],'total':2}
  elif p=='/api/admin/metadata/sync-tasks':data={'records':[{'id':1,'status':'SUCCESS','datasourceName':'销售经营','startedAt':'2026-10-29T10:10:00','finishedAt':'2026-10-29T10:11:00'}],'total':1}
  elif p=='/api/admin/knowledge-docs':data={'records':[],'total':0}
  elif p.endswith('/quality-issues'):data={'records':[],'total':0}
  elif p.endswith('/test-connection'):data={'success':True,'responseTimeMs':12,'message':'合成测试成功'}
  else:status=404
  await route.fulfill(status=status,content_type='application/json',body=json.dumps({'code':status,'message':'合成接口范围外' if status==404 else 'ok','data':data},ensure_ascii=False))

class AcceptanceFixture(Fixture):
 def __init__(self,mode='normal'):
  super().__init__();self.mode=mode;self.sources=json.loads(json.dumps(SOURCES));self.readiness=json.loads(json.dumps(READINESS));self.test_calls=0;self.created=0
 async def route(self,route):
  req=route.request;url=urlparse(req.url);p=url.path;q=parse_qs(url.query)
  self.calls.append({'method':req.method,'path':p})
  async def respond(data,status=200,message='ok'):
   await route.fulfill(status=status,content_type='application/json',body=json.dumps({'code':status,'message':message,'data':data},ensure_ascii=False))
  if p=='/api/iam-s1/capabilities':
   limited=self.mode in ['view-only','other-manager','no-view']
   functions=['audit:view'] if self.mode=='no-view' else ['datasource:view']+(['datasource:manage'] if self.mode=='other-manager' else [])
   scopes=[{'datasourceId':s['id'],'datasourceName':s['name'],'functionCodes':['datasource:view']+(['datasource:manage'] if self.mode=='other-manager' and s['id']==2 else []),'functionNames':[]} for s in self.sources]
   return await respond({'protocolVersion':'IAM-SIMPLE-1','userId':1,'systemAdmin':not limited,'globalFunctions':functions if limited else [],'datasourceCapabilities':scopes if limited and self.mode!='no-view' else [],'queryUse':not limited,'viewSql':not limited,'export':not limited,'permissionRevision':12})
  if p=='/api/admin/datasources/test-connection':
   self.test_calls+=1
   await asyncio.sleep(.3)
   return await respond({'success':True,'responseTimeMs':12,'message':'合成连接测试成功'})
  if p=='/api/admin/datasources' and req.method=='POST':
   self.created+=1;payload=req.post_data_json
   row={k:v for k,v in payload.items() if k!='password'};row.update(id=4,dbType='MYSQL',status=1,healthStatus='HEALTHY');self.sources.append(row)
   self.readiness['4']={**self.readiness['3'],'datasourceId':4,'datasourceName':row['name'],'connectionReady':True,'stage':'SNAPSHOT_PENDING','stageLabel':'快照待发布','blockReasons':[{'code':'SNAPSHOT_NOT_PUBLISHED','message':'尚未发布元数据快照','ownerRole':'治理负责人','actionText':'发布快照'}]}
   return await respond(row)
  if p=='/api/admin/datasources':
   if self.mode=='list-error':return await respond(None,503,'合成列表读取失败')
   if self.mode=='loading':await asyncio.sleep(.5)
   rows=[s for s in self.sources if q.get('name',[''])[0] in s['name']]
   if self.mode=='empty':rows=[]
   return await respond({'records':rows,'total':len(rows)})
  found=re.fullmatch(r'/api/admin/datasources/(\d+)(/readiness)?',p)
  if found:
   source=int(found.group(1));key=str(source)
   if found.group(2):
    if self.mode=='readiness-error' and source==2:return await respond(None,503,'合成准备状态读取失败')
    r=self.readiness[key].copy()
    if self.mode=='no-index' and source==2:r.update(askable=True,governanceReady=True,stage='ASKABLE',stageLabel='可询问',blockReasons=[])
    if self.mode=='no-snapshot' and source==3:r.update(connectionReady=True,stage='SNAPSHOT_PENDING',stageLabel='快照待发布',blockReasons=[{'code':'SNAPSHOT_NOT_PUBLISHED','message':'尚未发布元数据快照','ownerRole':'治理负责人','actionText':'发布快照'}])
    return await respond(r)
   if source==21:return await respond({**self.sources[0],'id':21,'name':'精确编辑目标二十一'})
   return await respond(next(s for s in self.sources if s['id']==source))
  if p=='/api/admin/metadata/snapshots':
   if self.mode=='aux-error':return await respond(None,503,'合成快照读取失败')
   source=q.get('datasourceId',['1'])[0];r=self.readiness[source];rows=[]
   if r.get('publishedSnapshotId'):rows.append({'id':r['publishedSnapshotId'],'datasourceId':int(source),'snapshotVersion':r['snapshotVersion'],'status':'PUBLISHED','tableCount':8,'columnCount':64,'createdAt':'2026-10-29T09:10:00'})
   if r.get('latestCollectedSnapshotId') and r['latestCollectedSnapshotId']!=r.get('publishedSnapshotId'):rows.append({'id':r['latestCollectedSnapshotId'],'datasourceId':int(source),'snapshotVersion':r['latestCollectedSnapshotVersion'],'status':'DRAFT','tableCount':8,'columnCount':66,'createdAt':'2026-10-29T10:10:00'})
   return await respond({'records':rows,'total':len(rows)})
  if p=='/api/admin/metadata/sync-tasks':
   source=q.get('datasourceId',['1'])[0];row=next(s for s in self.sources if s['id']==int(source));records=[{'id':1,'datasourceId':int(source),'status':'SUCCESS','datasourceName':row['name'],'startedAt':'2026-10-29T10:10:00','finishedAt':'2026-10-29T10:11:00'}] if self.readiness[source].get('latestCollectedSnapshotId') else []
   return await respond({'records':records,'total':len(records)})
  if p=='/api/admin/knowledge-docs':
   rows=[{'id':21,'title':'销售经营：指标与字段口径','currentVersion':2,'status':'PUBLISHED','updatedAt':'2026-10-29T09:20:00'}] if q.get('datasourceId')==['1'] else []
   return await respond({'records':rows,'total':len(rows)})
  # The base fixture handles read-only notification, activity and issue fixtures.
  self.calls.pop()
  return await super().route(route)

async def main():
 import argparse
 parser=argparse.ArgumentParser();parser.add_argument('--base-url',default='http://127.0.0.1:5173');parser.add_argument('--chromium',default='/usr/bin/chromium');parser.add_argument('--output',default=str(OUT));args=parser.parse_args()
 if urlparse(args.base_url).hostname not in ['127.0.0.1','localhost','::1']:raise SystemExit('Only a verified loopback frontend is allowed')
 out=Path(args.output);out.mkdir(parents=True,exist_ok=True);checks=[];errors=[];evidence=[]
 def passed(name):checks.append({'name':name,'status':'passed'})
 async with async_playwright() as p:
  browser=await p.chromium.launch(executable_path=args.chromium,headless=True)
  async def open_page(path='/admin/data-sources',mode='normal',width=1600):
   context=await browser.new_context(viewport={'width':width,'height':1050},device_scale_factor=1.5,reduced_motion='reduce')
   await context.add_init_script("localStorage.setItem('dataocean_token','synthetic-admin-fixture');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))")
   fixture=AcceptanceFixture(mode);await context.route(re.compile('^'+re.escape(args.base_url.rstrip('/'))+'/api/'),fixture.route)
   page=await context.new_page();page.on('pageerror',lambda error:errors.append(str(error)))
   page.on('console',lambda message:errors.append(message.text) if message.type=='error' and '[全局错误]' in message.text else None)
   await page.goto(args.base_url+path)
   return context,page,fixture
  async def capture(page,name):
   await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
   await page.wait_for_function('''() => [...document.querySelectorAll('.el-dialog')].every(dialog => {
    if (dialog.getBoundingClientRect().width===0) return true;
    for(let node=dialog;node&&node!==document.body;node=node.parentElement) {
     if(Number(getComputedStyle(node).opacity)<.999||node.getAnimations().some(a=>a.playState==='running'))return false;
    } return true;
   })''')
   await page.evaluate("document.getElementById('fixture-label')?.remove();document.body.insertAdjacentHTML('beforeend','<div id=fixture-label style=\"position:fixed;bottom:4px;right:8px;z-index:99999;color:#676d67;font:11px system-ui;pointer-events:none\">第二阶段浏览器验收 · 合成 API 响应</div>')")
   await page.screenshot(path=str(out/name),full_page=True)
  try:
   context,page,f=await open_page();await page.wait_for_selector('.el-table__row')
   assert await page.locator('.source-journey li').count()==6
   assert '索引：活动版本来源落后' in await page.locator('.el-table').inner_text()
   passed('列表分开说明六项分工、正式/采集版本与索引')
   await capture(page,'03-list-mock-desktop.png')
   search=page.get_by_role('textbox',name='搜索数据源名称',exact=True)
   await search.fill('供应链');await search.press('Enter');await page.wait_for_function("new URLSearchParams(location.search).get('name')==='供应链'")
   await page.wait_for_timeout(100);assert await page.locator('.el-table__row').count()==1
   await search.fill('');await search.press('Enter');await page.wait_for_function("!new URLSearchParams(location.search).has('name')")
   await page.wait_for_timeout(100);assert await page.locator('.el-table__row').count()==3
   passed('搜索请求与URL同步')
   await page.get_by_role('button',name='新增数据源',exact=True).click()
   dialog=page.locator('.data-source-dialog')
   await dialog.wait_for();assert await dialog.get_by_role('button',name='创建数据源',exact=True).is_disabled()
   await capture(page,'04-connect-mock-desktop.png')
   for label,value in [('名称','合成接入演示'),('主机','fixture.internal'),('数据库','new_fixture'),('只读账号','fixture_readonly'),('密码','synthetic-not-a-real-secret')]:await dialog.get_by_label(label,exact=True).fill(value)
   await dialog.get_by_role('button',name='测试连接',exact=True).click();await dialog.get_by_role('button',name='测试连接',exact=True).click(force=True)
   await page.get_by_text('连接测试已通过，可以保存。',exact=True).wait_for()
   assert f.test_calls==1
   assert await dialog.get_by_role('button',name='创建数据源',exact=True).is_enabled()
   passed('有效表单测试后可创建；重复连接测试不重复请求')
   await dialog.get_by_label('主机',exact=True).fill('changed.synthetic')
   assert await dialog.get_by_role('button',name='创建数据源',exact=True).is_disabled()
   await dialog.get_by_role('button',name='测试连接',exact=True).click();await page.get_by_text('连接测试已通过，可以保存。',exact=True).wait_for()
   await dialog.get_by_role('button',name='创建数据源',exact=True).click()
   await page.wait_for_function("location.pathname==='/admin/data-sources/4'");await page.wait_for_selector('.preparation-path')
   assert f.created==1
   assert '尚无采集快照' in await page.locator('.preparation-path').inner_text()
   assert 'synthetic-not-a-real-secret' not in await page.evaluate('JSON.stringify(localStorage)')
   passed('改配置后重新测试；创建后读当前能力，凭据不持久化到浏览器存储')
   await page.goto(args.base_url+'/admin/data-sources/2');await page.wait_for_selector('.preparation-path')
   assert '存在 3 条高危未解决治理问题' in await page.locator('.source-outcome').inner_text()
   assert await page.get_by_role('button',name='处理治理问题',exact=True).is_visible()
   assert '尚无活动索引' in await page.locator('[data-stage="index"]').inner_text()
   await capture(page,'05-readiness-mock-desktop.png')
   passed('当前首要阻断与角色在前，准备事实与下一步有权入口分别展示')
   await page.get_by_role('button',name='编辑连接',exact=True).click();await page.wait_for_selector('.data-source-dialog')
   assert await page.locator('.data-source-dialog').get_by_label('名称',exact=True).input_value()=='供应链'
   assert await page.locator('.data-source-dialog').get_by_role('button',name='保存',exact=True).is_enabled()
   await page.locator('.data-source-dialog').get_by_role('button',name='取消',exact=True).click();await page.wait_for_selector('.data-source-dialog',state='hidden')
   passed('详情编辑入口保持精确目标，未改连接可沿用原密码保存')
   # Back returns to detail; leaving the list must close only its confirmation dialog.
   await page.get_by_role('button',name='销售经营的更多操作',exact=True).click();await page.get_by_role('menuitem',name='禁用数据源',exact=True).click()
   await page.get_by_role('button',name='确认禁用',exact=True).wait_for();await page.go_back()
   await page.wait_for_selector('.el-message-box',state='hidden')
   assert not any(call['path'].endswith('/status') for call in f.calls)
   passed('后退卸载时取消所属禁用确认，无迟到写入')
   evidence.append({'scenario':'main','requests':f.calls});await context.close()
   for mode in ['view-only','no-index','no-snapshot','aux-error','readiness-error']:
    path='/admin/data-sources/3' if mode=='no-snapshot' else '/admin/data-sources/2'
    context,page,f=await open_page(path,mode)
    if mode=='readiness-error':await page.get_by_text('合成准备状态读取失败',exact=True).wait_for()
    else:await page.wait_for_selector('.preparation-path')
    text=await page.locator('.datasource-cockpit').inner_text()
    if mode=='view-only':
     assert not any(call['path'] in ['/api/admin/metadata/snapshots','/api/admin/metadata/sync-tasks','/api/admin/knowledge-docs'] or call['path'].endswith('/quality-issues') for call in f.calls)
     assert '没有快照详情查看权限' in text and '快照读取失败' not in text
     assert await page.get_by_role('button',name='处理治理问题',exact=True).count()==0
    elif mode=='no-index':assert '后台判定可问数' in text and '尚无活动索引' in text
    elif mode=='no-snapshot':assert await page.get_by_role('button',name='开始采集',exact=True).is_visible() and '尚无发布范围' in text
    elif mode=='aux-error':assert '快照读取失败' in text and '没有快照详情查看权限' not in text
    passed(mode+'状态和权限合同');evidence.append({'scenario':mode,'requests':f.calls});await context.close()
   for mode in ['empty','list-error','no-view','loading','other-manager']:
    context,page,f=await open_page(mode=mode)
    if mode=='empty':await page.get_by_text('还没有数据源，请先完成连接测试并创建一个数据源。',exact=True).wait_for()
    elif mode=='list-error':await page.get_by_text('合成列表读取失败',exact=True).wait_for()
    elif mode=='no-view':
     await page.get_by_text('没有查看数据源的权限。',exact=True).wait_for()
     assert not any(call['path']=='/api/admin/datasources' for call in f.calls)
    elif mode=='loading':
     await page.wait_for_selector('.source-journey');await page.wait_for_selector('.loading-state');await page.wait_for_selector('.el-table__row')
    else:
     await page.wait_for_selector('.el-table__row')
     assert await page.get_by_role('button',name='销售经营的更多操作',exact=True).count()==0
     assert await page.get_by_role('button',name='供应链的更多操作',exact=True).count()==1
    passed(mode+'列表分支');evidence.append({'scenario':mode,'requests':f.calls});await context.close()
   context,page,f=await open_page('/admin/data-sources?edit=21')
   await page.wait_for_selector('.data-source-dialog')
   assert await page.locator('.data-source-dialog').get_by_label('名称',exact=True).input_value()=='精确编辑目标二十一'
   passed('非首屏编辑深链精确读取与目标核对')
   await page.locator('.data-source-dialog').get_by_role('button',name='取消',exact=True).click();await context.close()
   context,page,f=await open_page(width=390)
   await page.wait_for_selector('.el-table__row');await page.set_viewport_size({'width':390,'height':844})
   await page.get_by_role('button',name='新增数据源',exact=True).click();await page.wait_for_selector('.data-source-dialog')
   await page.wait_for_timeout(250)
   assert await page.locator('.admin-shell__content').evaluate("element=>element.style.overflowY==='hidden'")
   footer=await page.locator('.data-source-dialog .el-dialog__footer').bounding_box()
   assert footer and footer['y']+footer['height']<=845
   await capture(page,'07-connect-mock-mobile.png')
   passed('窄屏连接表单正文内滚动，底部操作可见且外层锁定')
   await context.close()
   context,page,f=await open_page('/admin/data-sources/2')
   await page.wait_for_selector('.preparation-path')
   for width,height in [(390,844),(768,1024),(1024,768),(1440,900),(1600,1050),(844,390)]:
    await page.set_viewport_size({'width':width,'height':height});await page.wait_for_timeout(120)
    assert await page.evaluate('document.documentElement.scrollWidth <= innerWidth+1')
    if width==390:await capture(page,'06-readiness-mock-mobile.png')
   passed('六个桌面/窄屏/横屏视口无整页横向溢出')
   await context.close()
   assert not errors,errors
   passed('无未捕获异常与应用全局错误')
  finally:
   (out/'datasource-browser-verification.json').write_text(json.dumps({'kind':'real Vue and Chromium, synthetic API responses only','checks':checks,'browserErrors':errors,'requestEvidence':evidence},ensure_ascii=False,indent=2))
   await browser.close()
 print(json.dumps({'passed':len(checks),'errors':errors,'output':str(out)},ensure_ascii=False))
if __name__=='__main__':asyncio.run(main())
