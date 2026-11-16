"""Real Vue/Chromium with synthetic API responses; loopback only."""
import asyncio,json,re,sys
from pathlib import Path
from urllib.parse import urlparse,parse_qs
sys.path.insert(0,str(Path(__file__).resolve().parent))
from knowledge_workspace_v2 import KnowledgeFixture
from playwright.async_api import async_playwright
OUT=Path('output/playwright/lineage-audit-v2-20261029')
def entity(id,name,type='TABLE',source=1,table=None):return dict(id=id,name=name,displayName={'orders':'销售订单','customers':'客户主档','monthly_sales':'月度销售汇总','amount':'成交金额'}.get(name,name),fqn='sales.sales_analytics.'+(table+'.' if table else '')+name,entityType=type,entityUuid='synthetic-'+str(id),version=2,entityMetadata=json.dumps(dict(datasource_id=source,table_name=table or name,column_name=name if type=='COLUMN' else None)))
NODES=[entity(100,'orders'),entity(110,'customers'),entity(120,'monthly_sales'),entity(101,'amount','COLUMN',table='orders'),entity(121,'amount','COLUMN',table='monthly_sales')]
EDGES=[dict(id=50,sourceId=100,targetId=120,sourceType='TABLE',targetType='TABLE',relationType='LINEAGE',relationMetadata=json.dumps(dict(lineage_type='ETL',description='合成加工关系，人工录入'))),dict(id=51,sourceId=100,targetId=110,sourceType='TABLE',targetType='TABLE',relationType='FOREIGN_KEY'),dict(id=52,sourceId=101,targetId=121,sourceType='COLUMN',targetType='COLUMN',relationType='DERIVED_FROM',relationMetadata=json.dumps(dict(expression='SUM(amount)',expression_type='AGGREGATION',lineage_type='MANUAL')))]
class Fixture(KnowledgeFixture):
 def __init__(self,mode='normal'):super().__init__(mode);self.lineage_writes=[];self.edges=[dict(x) for x in EDGES]
 async def route(self,route):
  req=route.request;u=urlparse(req.url);p=u.path;q=parse_qs(u.query)
  async def send(data,status=200,msg='ok'):
   self.calls.append(dict(path=p,method=req.method,query=q));await route.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=msg,data=data),ensure_ascii=False))
  if p=='/api/iam-s1/capabilities':
   codes=['lineage:view'] if self.mode=='lineage-only' else ['lineage:view','metadata:view'] if self.mode=='readonly' else ['audit:view'] if self.mode=='audit-only' else ['operation-log:view'] if self.mode=='operation-only' else []
   return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=self.mode not in ['lineage-only','readonly','audit-only','operation-only','no-access'],globalFunctions=['operation-log:view'] if self.mode=='operation-only' else [],datasourceCapabilities=[dict(datasourceId=i,datasourceName='销售经营' if i==1 else '供应链',functionCodes=codes,functionNames=[]) for i in [1,2]],queryUse=False,viewSql=False,export=False))
  if p=='/api/admin/catalog/entities':return await send([] if self.mode=='empty' else NODES)
  m=re.fullmatch(r'/api/admin/catalog/entities/(\d+)(?:/(lineage|column-lineage))?',p)
  if m:
   id=int(m[1]);kind=m[2]
   if self.mode=='graph-error':return await send(None,503,'合成图谱读取失败')
   if kind=='lineage':return await send(dict(nodes=NODES,edges=self.edges,columnMappingsByEdge={},depth=3) if 'lineageType' in q else self.edges)
   if kind=='column-lineage':return await send(dict(columnId=id,columnName='amount',fqn='sales.sales_analytics.orders.amount',upstream=[],downstream=[dict(entity=NODES[4],relationship=EDGES[2])]))
   return await send(dict(entity=next(n for n in NODES if n['id']==id),outgoingRelations=[],incomingRelations=[]))
  if p=='/api/admin/catalog/search':
   ty=q.get('type',['TABLE'])[0];term=q.get('q',[''])[0];return await send([n for n in NODES if n['entityType']==ty and (term in n['fqn'] or term in n['displayName'])])
  if p.startswith('/api/lineage/impact/'):
   if self.mode=='impact-error':return await send(None,503,'合成使用证据读取失败')
   return await send(dict(tableName='orders',columnName='amount',dependentQueryCount=125,coverageStartAt='2026-10-20T08:00:00',coverageNote='合成：只覆盖已完成投影的受保护查询，跨历史快照，不代表所有业务使用。',pendingDatasourceQueryCount=2,recentQueryTaskIds=[701,702]))
  if p.startswith('/api/lineage/table/'):return await send([dict(queryTaskId=701,sourceTable='orders',targetName='__USAGE__',relationType='USAGE',question='合成：订单总数',createdAt='2026-10-29T10:00:00')])
  if p.startswith('/api/lineage/column/'):return await send([dict(queryTaskId=701,sourceTable='orders',sourceColumn='amount',aliasName='total_amount',expression='SUM(amount)',question='合成：订单成交金额',createdAt='2026-10-29T10:00:00')])
  if p=='/api/admin/catalog/lineage' or p=='/api/admin/catalog/lineage/batch' or re.fullmatch(r'/api/admin/catalog/lineage/\d+',p):
   self.lineage_writes.append(dict(path=p,method=req.method,query=q));await asyncio.sleep(.35)
   if req.method=='DELETE':self.edges=[edge for edge in self.edges if edge['id']!=int(p.split('/')[-1])]
   return await send(dict(relationshipId=50,deleted=True,derivedCount=1) if req.method=='DELETE' else [] if p.endswith('/batch') else dict(relationshipId=60))
  audit=dict(id=81,queryTaskId=701,userId=1,username='合成分析员',datasourceId=1,datasourceName='销售经营',question='合成：本月订单成交金额是多少',sqlText='SELECT SUM(amount) FROM orders LIMIT 100',usedTables='["orders"]',usedFields='["orders.amount"]',executionTimeMs=6800,rowCount=1,isSuccess=True,isSlow=True,createdAt='2026-10-29T10:00:00')
  if p=='/api/admin/audit-logs/stats':
   return await send(dict(totalQueries=125,successCount=120,successRate=96.0,avgExecutionTimeMs=840,slowQueryCount=4,slowQueryRate=3.2))
  if p in ['/api/admin/audit-logs','/api/admin/audit-logs/slow-queries']:
   if self.mode=='audit-error':return await send(None,503,'合成审计读取失败')
   rows=[] if self.mode=='empty' else [audit] if p.endswith('/slow-queries') else [audit,{**audit,'id':82,'question':'合成：筛选与连接字段使用','isSuccess':None,'isSlow':False,'executionTimeMs':None}]
   return await send(dict(records=rows,total=0 if self.mode=='empty' else 42 if p.endswith('/slow-queries') else 142))
  if re.fullmatch(r'/api/admin/audit-logs/\d+',p):
   if self.mode=='detail-error':self.mode='normal';return await send(None,503,'合成详情首次失败')
   return await send({**audit,'id':int(p.split('/')[-1])})
  if p=='/api/admin/operation-logs':
   if self.mode=='operation-error':return await send(None,503,'合成操作日志读取失败')
   row=dict(id=91,operatorId=1,operatorName='合成管理员',operationType='UPDATE',targetResource='metadata',targetId='11',requestMethod='PUT',requestPath='/api/admin/synthetic/11',requestParams='{"example":"合成历史文本"}',executionMs=124,isSuccess=True,ipAddress='127.0.0.1',createdAt='2026-10-29T10:00:00')
   return await send(dict(records=[] if self.mode=='empty' else [row,{**row,'id':92,'isSuccess':False,'errorMessage':'合成请求失败'},{**row,'id':93,'isSuccess':None,'executionMs':None}],total=0 if self.mode=='empty' else 42))
  return await super().route(route)
async def main():
 import argparse
 parser=argparse.ArgumentParser();parser.add_argument('--baseline',action='store_true');args=parser.parse_args();OUT.mkdir(parents=True,exist_ok=True)
 checks=[];errors=[]
 def passed(name):checks.append(dict(name=name,status='passed'))
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/usr/bin/chromium')
  async def open_page(path='/admin/assets/lineage?datasourceId=1&entityId=100',mode='normal',width=1600):
   c=await b.new_context(viewport=dict(width=width,height=1050),device_scale_factor=1.5,reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-admin-fixture');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))");f=Fixture(mode);await c.route('http://127.0.0.1:5173/api/**',f.route);page=await c.new_page();page.on('pageerror',lambda e:errors.append(str(e)));await page.goto('http://127.0.0.1:5173'+path);await page.wait_for_selector('h1');await page.evaluate("""() => {const mark=document.createElement('div');mark.textContent='合成 API · 设计验收 · 非真实联调';mark.style.cssText='position:fixed;bottom:8px;right:16px;z-index:99999;background:#fff6e9;border:1px solid #b9a48b;color:#725034;border-radius:5px;padding:5px 9px;font:11px sans-serif;pointer-events:none';document.body.append(mark)}""");return c,page,f
  c,p,f=await open_page();await p.wait_for_selector('.chart-area svg');await p.screenshot(path=str(OUT/'01-lineage-graph.png'),full_page=True);passed('关系图真实D3渲染与结构/加工/字段派生独立标签')
  assert await p.locator('.edge textPath').first.text_content();passed('关系标签存在真实引用路径')
  node=p.locator('g.node').first;before=await node.get_attribute('transform');box=await node.bounding_box();await p.mouse.move(box['x']+30,box['y']+20);await p.mouse.down();await p.mouse.move(box['x']+75,box['y']+45,steps=5);await p.mouse.up();assert before!=await node.get_attribute('transform');passed('普通拖动移动节点而非创建关系')
  await p.get_by_text('检查已记录关系',exact=False).click();await p.get_by_role('button',name='删除关系 #50',exact=True).click();assert len(f.lineage_writes)==0;await p.get_by_role('button',name='取消',exact=True).last.click();assert len(f.lineage_writes)==0;passed('取消确认零DELETE')
  await p.get_by_role('button',name='删除关系 #50',exact=True).click();await p.get_by_role('button',name='确认删除',exact=True).click();await p.wait_for_timeout(450);assert len(f.lineage_writes)==1 and f.lineage_writes[0]['query']['cascadeDerived']==['false'];await p.get_by_role('button',name='删除关系 #50',exact=True).wait_for(state='hidden');passed('确认一次DELETE、重新读取反映删除且不把预检查当读取')
  async with p.expect_download() as info:await p.get_by_role('button',name='导出当前图谱').click()
  download=await info.value;await download.save_as(str(OUT/'graph-export.png'))
  from PIL import Image
  image=Image.open(OUT/'graph-export.png');assert image.width>500 and image.height>500;passed('PNG导出非0尺寸')
  await p.get_by_role('button',name='查看查询使用',exact=True).click();await p.get_by_text('125',exact=True).wait_for();assert '整个数据源待投影' in await p.locator('.query-lineage-evidence').inner_text();assert not await p.locator('.query-lineage-evidence a').count();await p.locator('.el-message').wait_for(state='hidden');await p.screenshot(path=str(OUT/'02-query-source-evidence.png'),full_page=True);passed('查询来源显式source/对象、total与明细独立、无数字taskId伪链接');await c.close()
  c,p,f=await open_page(mode='lineage-only');await p.wait_for_selector('.chart-area svg');assert not any(x['path']=='/api/admin/catalog/entities' for x in f.calls);await p.screenshot(path=str(OUT/'03-lineage-minimal-role.png'),full_page=True);passed('最小lineage:view精确根不依赖目录权限');await c.close()
  for mode,name in [('graph-error','04-lineage-error.png'),('empty','05-lineage-empty.png')]:
   c,p,f=await open_page(path='/admin/assets/lineage?datasourceId=1' if mode=='empty' else '/admin/assets/lineage?datasourceId=1&entityId=100',mode=mode);await p.wait_for_timeout(450);await p.screenshot(path=str(OUT/name),full_page=True);assert ('合成图谱读取失败' if mode=='graph-error' else '对象目录来自已发布快照') in await p.locator('main.data-lineage-page').inner_text();passed('血缘'+mode+'不冒充成功空');await c.close()
  c,p,f=await open_page(width=620);await p.wait_for_selector('.chart-area svg');assert await p.evaluate('document.documentElement.scrollWidth<=window.innerWidth');await p.screenshot(path=str(OUT/'06-lineage-narrow.png'),full_page=True);passed('窄屏图谱/筛选自然换行无整页横向溢出');await c.close()
  c,p,f=await open_page(path='/admin/operations/queries',mode='audit-only');await p.get_by_text('合成：本月订单成交金额是多少',exact=True).wait_for();assert not any(x['path']=='/api/admin/audit-logs/slow-queries' for x in f.calls);assert not any(x['path'].endswith('/datasources/simple') for x in f.calls);await p.screenshot(path=str(OUT/'07-query-audit.png'),full_page=True);passed('最小audit:view可用，隐藏性能页不读、不请求无权源辅助接口')
  await p.get_by_role('button',name='详情',exact=True).first.click();await p.get_by_text('审计保存的 SQL',exact=True).wait_for();assert not await p.locator('.sql-section pre').count();await p.get_by_role('button',name='按当前权限读取 SQL',exact=True).click();await p.locator('.sql-section pre').wait_for();assert len([x for x in f.calls if x['path']=='/api/admin/audit-logs/81'])==2;await p.screenshot(path=str(OUT/'08-audit-on-demand-sql.png'),full_page=True);passed('SQL默认不展示，点击再次审计详情服务端重验');await p.keyboard.press('Escape');await p.locator('.el-pagination .btn-next').click();await p.wait_for_timeout(200);assert any(x['path']=='/api/admin/audit-logs' and x['query'].get('pageNo')==['2'] for x in f.calls);passed('审计分页pageNo/服务端total');await p.get_by_role('tab',name='性能分析').click();await p.get_by_text('定位耗时较长的查询记录',exact=True).wait_for();await p.wait_for_timeout(200);assert any(x['path'].endswith('/slow-queries') and 'datasourceId' not in x['query'] for x in f.calls);assert not await p.get_by_text('SQL',exact=True).count();await p.screenshot(path=str(OUT/'09-slow-query.png'),full_page=True);passed('性能有权源合并分页，无伪source参数和默认SQL');await p.get_by_role('button',name='详情',exact=True).first.click();await p.get_by_text('慢查询对应的审计记录',exact=True).wait_for();await p.get_by_role('button',name='按当前权限读取 SQL',exact=True).click();await p.locator('.record-evidence pre').wait_for();passed('慢查询保留逐行审计详情和按需SQL能力');await c.close()
  c,p,f=await open_page(path='/admin/operations/queries',mode='detail-error');await p.get_by_role('button',name='详情',exact=True).first.click();await p.get_by_text('合成详情首次失败',exact=True).wait_for();await p.get_by_role('button',name='重试',exact=True).click();await p.get_by_text('审计保存的 SQL',exact=True).wait_for();passed('详情失败按原审计ID可重试');await c.close()
  c,p,f=await open_page(path='/admin/operations/queries?datasourceId=invalid',mode='audit-only');await p.wait_for_timeout(300);assert not any(x['path']=='/api/admin/audit-logs' for x in f.calls);passed('非法源不退回全源审计');await c.close()
  c,p,f=await open_page(path='/admin/platform/operation-logs',mode='operation-only');await p.get_by_text('合成管理员',exact=True).first.wait_for();assert '未记录' in await p.locator('.log-table').inner_text();await p.screenshot(path=str(OUT/'10-operation-audit.png'),full_page=True);passed('独立operation-log:view与未知状态');await p.get_by_role('button',name='详情',exact=True).first.click();assert not await p.locator('.detail-block pre').count();await p.get_by_role('button',name='展开原记录',exact=True).click();await p.locator('.detail-block pre').wait_for();await p.screenshot(path=str(OUT/'11-operation-detail.png'),full_page=True);passed('原记录文本按需展开，无伪详情API');await c.close()
  c,p,f=await open_page(path='/admin/platform/operation-logs',mode='audit-only');await p.wait_for_timeout(300);assert not any(x['path']=='/api/admin/operation-logs' for x in f.calls);passed('audit:view不能借作操作日志权');await c.close()
  for path,mode,name in [('/admin/operations/queries','audit-error','12-audit-error.png'),('/admin/platform/operation-logs','operation-error','13-operation-error.png'),('/admin/operations/queries?keyword=合成无匹配','empty','14-audit-empty.png')]:
   c,p,f=await open_page(path=path,mode=mode);await p.wait_for_timeout(450);await p.screenshot(path=str(OUT/name),full_page=True);passed(mode+'实际页面状态');await c.close()
  c,p,f=await open_page(path='/admin/platform/operation-logs',width=620);await p.wait_for_timeout(450);assert await p.evaluate('document.documentElement.scrollWidth<=window.innerWidth');await p.screenshot(path=str(OUT/'15-operation-narrow.png'),full_page=True);passed('窄屏操作日志筛选换行表内滚动');await c.close()
  c,p,f=await open_page(path='/admin/operations/queries?datasourceId=1',mode='audit-only',width=620);await p.wait_for_timeout(350);assert await p.evaluate('document.documentElement.scrollWidth<=window.innerWidth');await p.screenshot(path=str(OUT/'16-audit-narrow.png'),full_page=True);passed('窄屏查询审计筛选自然换行');await c.close()
  c,p,f=await open_page(path='/admin/platform/operation-logs?start=bad&end=bad',mode='operation-only');await p.wait_for_timeout(300);assert not any(x['path']=='/api/admin/operation-logs' for x in f.calls);await p.get_by_role('button',name='清空时间范围').click();await p.get_by_text('合成管理员',exact=True).first.wait_for();passed('无效时间零请求，明确清空后恢复');await c.close()
  c,p,f=await open_page(path='/admin/operations/queries?tab=performance&slowPage=2',mode='audit-only');await p.get_by_text('定位耗时较长的查询记录',exact=True).wait_for();await p.wait_for_timeout(250);assert not any(x['path']=='/api/admin/audit-logs' for x in f.calls);assert any(x['path'].endswith('/slow-queries') and x['query'].get('page')==['2'] for x in f.calls);passed('直接性能URL仅加载活跃Tab且恢复慢分页');await c.close()
  await b.close()
 result=dict(synthetic=True,realBackendTested=False,checks=checks,browserErrors=errors)
 (OUT/'browser-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));assert not errors
if __name__=='__main__':asyncio.run(main())
