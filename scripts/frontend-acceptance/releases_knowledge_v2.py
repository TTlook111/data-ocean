"""Actual Vue/Chromium, loopback synthetic API only. No production backend."""
import asyncio,json,re,sys
from pathlib import Path
from urllib.parse import urlparse,parse_qs
sys.path.insert(0,str(Path(__file__).resolve().parent))
from collection_assets_v2 import StageFixture
from playwright.async_api import async_playwright
OUT=Path('output/playwright/releases-knowledge-v2-20261029')
def version(id,v,status,source=1):return dict(snapshotId=id,datasourceId=source,datasourceName='销售经营' if source==1 else '供应链',snapshotVersion=v,status=status,tableCount=8,columnCount=64,qualityScore=92,createdAt='2026-10-29T09:10:00',publishedAt='2026-10-29T10:00:00' if status=='PUBLISHED' else None)
class ReleaseFixture(StageFixture):
 def __init__(self,mode='normal'):
  super().__init__(mode);self.actions=[];self.rows=[version(14,6,'DRAFT'),version(13,5,'CHECKING'),version(12,4,'APPROVED'),version(11,3,'PUBLISHED'),version(10,2,'EXPIRED')]
 async def route(self,route):
  req=route.request;u=urlparse(req.url);p=u.path;q=parse_qs(u.query)
  async def send(data,status=200,msg='ok'):
   self.calls.append(dict(path=p,method=req.method,query=q));await route.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=msg,data=data),ensure_ascii=False))
  if p=='/api/iam-s1/capabilities':
   codes=['metadata:release:view']+([] if self.mode=='release-only' else ['metadata:view'])
   if self.mode=='no-view':codes=['audit:view']
   return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=self.mode not in ['release-only','readonly','no-view'],globalFunctions=[],datasourceCapabilities=[dict(datasourceId=i,datasourceName='销售经营' if i==1 else '供应链',functionCodes=codes,functionNames=[]) for i in [1,2]],queryUse=True,viewSql=True,export=True))
  m=re.fullmatch('/api/admin/datasources/(\\d+)/(version-history|published-snapshot)',p)
  if m:
   if self.mode=='list-error':return await send(None,503,'合成版本读取失败')
   rows=self.rows if m[1]=='1' else [version(22,1,'PUBLISHED',2)]
   return await send(dict(records=[] if self.mode=='empty' else rows,total=0 if self.mode=='empty' else len(rows)) if m[2]=='version-history' else next((r for r in rows if r['status']=='PUBLISHED'),None))
  m=re.fullmatch('/api/admin/snapshots/(\\d+)/(release-gate|audit-logs|publish|revoke|status)',p)
  if m:
   id=int(m[1]);row=next((r for r in self.rows if r['snapshotId']==id),version(id,1,'PUBLISHED',2));action=m[2]
   if action=='release-gate':
    if self.mode=='gate-error':return await send(None,503,'合成门禁读取失败')
    fail=self.mode=='blocked';return await send(dict(snapshotId=id,datasourceId=row['datasourceId'],snapshotStatus=row['status'],approvable=not fail,publishable=not fail,blockingCount=int(fail),warningCount=1,unresolvedSourceHigh=int(fail),unresolvedSemanticHigh=1,validTableCount=8,items=[dict(code='SOURCE_HIGH',category='SOURCE',severity='HIGH',status='FAIL' if fail else 'PASS',blocksApproval=fail,blocksPublish=fail,count=int(fail),message='存在来源高风险' if fail else '来源高风险已处理'),dict(code='SEMANTIC_HIGH',category='SEMANTIC',severity='HIGH',status='WARN',blocksApproval=False,blocksPublish=False,count=1,message='语义高风险提示，不阻断发布')]))
   if action=='audit-logs':return await send(dict(records=[dict(id=1,action='PUBLISH',operatorName='合成审核人员',reason='合成原始操作记录',createdAt='2026-10-29T10:00:00')],total=1))
   self.actions.append(dict(path=p,method=req.method,payload=req.post_data_json));await asyncio.sleep(.3)
   if self.mode=='conflict':return await send(None,409,'合成并发冲突，请重读')
   if self.mode=='unknown':return await route.abort('connectionfailed')
   if action=='publish':
    for r in self.rows:
     if r['status']=='PUBLISHED':r['status']='EXPIRED'
    row['status']='PUBLISHED'
   elif action=='revoke':row['status']='APPROVED'
   else:row['status']=req.post_data_json['targetStatus']
   return await send(None)
  if re.fullmatch('/api/admin/snapshots/\\d+/diff/\\d+',p):return await send(dict(addedTables=['order_adjustments'],removedTables=[],addedColumns=[],removedColumns=[],modifiedColumns=[dict(tableName='orders',columnName='amount',oldType='DECIMAL(12,2)',newType='DECIMAL(12,2)',oldComment='原始金额说明',newComment='明确扣除优惠的成交金额')]))
  if p.endswith('/quality-check'):self.actions.append(dict(path=p,method=req.method));return await send(dict(status='PARTIAL',complete=False,executedRuleCount=2,failedRuleCount=1,notApplicableRuleCount=0))
  if re.fullmatch('/api/admin/metadata/snapshots/\\d+',p):
   id=int(p.split('/')[-1]);r=next((r for r in self.rows if r['snapshotId']==id),version(id,1,'PUBLISHED',2))
   return await send(dict(snapshot=dict(id=id,datasourceId=r['datasourceId'],snapshotVersion=r['snapshotVersion'],status=r['status'],tableCount=1,columnCount=1,qualityScore=92,createdAt=r['createdAt']),tables=[dict(id=1,tableName='orders',tableComment='订单明细',governanceStatus='NORMAL')],columns=[dict(id=201,tableMetaId=1,columnName='amount',dataType='DECIMAL(12,2)',columnComment='成交金额',ordinalPosition=1,isNullable=0,isPrimaryKey=0,governanceStatus='NORMAL')]))
  return await super().route(route)
async def main():
 import argparse
 p=argparse.ArgumentParser();p.add_argument('--baseline',action='store_true');p.add_argument('--phase',choices=['releases'],default='releases');p.add_argument('--output',default=str(OUT));args=p.parse_args();out=Path(args.output);out.mkdir(parents=True,exist_ok=True)
 checks=[];errors=[];evidence=[]
 def passed(name):checks.append(dict(name=name,status='passed'))
 async with async_playwright() as pw:
  browser=await pw.chromium.launch(executable_path='/usr/bin/chromium')
  async def open_page(path='/admin/releases?datasourceId=1',mode='normal',width=1600,height=1050):
   c=await browser.new_context(viewport=dict(width=width,height=height),device_scale_factor=1.5,reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-admin-fixture');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))")
   f=ReleaseFixture(mode);await c.route('http://127.0.0.1:5173/api/**',f.route);page=await c.new_page();page.on('pageerror',lambda e:errors.append(str(e)));page.on('console',lambda msg:errors.append(msg.text) if '[全局错误]' in msg.text else None);await page.goto('http://127.0.0.1:5173'+path)
   await page.locator('h1').wait_for();return c,page,f
  async def capture(page,name):
   await page.wait_for_timeout(350)
   await page.evaluate("""()=>{const label=document.createElement('div');label.textContent='第五阶段浏览器验收 · 合成 API 响应';label.style='position:fixed;bottom:2px;right:8px;z-index:9999;font-size:10px;color:#676d67;background:#fff';document.body.appendChild(label)}""");await page.screenshot(path=str(out/name),full_page=True)
  c,page,f=await open_page();await page.wait_for_selector('.releases-page .el-table__row')
  if args.baseline:
   await capture(page,'00-baseline-releases.png');await browser.close();return
  rows=page.locator('.releases-page__table .el-table__row')
  assert await rows.count()==5;assert not any(x['path'].endswith('/release-gate') for x in f.calls);passed('版本分页与当前正式版，不逐行读门禁');await capture(page,'01-releases-mock-desktop.png')
  await page.get_by_role('button',name='查看版本 v4 详情',exact=True).click();dialog=page.get_by_role('dialog');await dialog.get_by_text('orders',exact=True).wait_for();await dialog.get_by_role('tab',name='操作记录').click();await dialog.get_by_text('合成原始操作记录').wait_for();passed('统一详情的结构、门禁及精确审计');await capture(page,'02-snapshot-audit-mock-desktop.png');await dialog.get_by_role('button',name='关闭',exact=True).click()
  approved=rows.filter(has=page.get_by_role('button',name='查看版本 v4 详情',exact=True));await approved.get_by_role('button',name='发布',exact=True).click();dialog=page.get_by_role('dialog');await dialog.get_by_text('语义高风险提示，不阻断发布',exact=True).wait_for();await capture(page,'03-snapshot-release-mock-desktop.png');await dialog.get_by_role('button',name='确认发布',exact=True).click(click_count=2);await page.wait_for_timeout(500);assert len([a for a in f.actions if a['path'].endswith('/publish')])==1;passed('显式快照发布、重复点击只写一次')
  assert not any('/rag-builds' in a['path'] for a in f.actions);passed('快照发布不自动构建RAG')
  evidence.append(dict(scenario='releases-normal',requests=f.calls,actions=f.actions));await c.close()
  for mode,text in [('blocked','存在来源高风险'),('gate-error','合成门禁读取失败'),('conflict','合成并发冲突'),('unknown','操作结果待确认')]:
   c,page,f=await open_page(mode=mode);await page.wait_for_selector('.releases-page .el-table__row');row=page.locator('.el-table__row').filter(has=page.get_by_role('button',name='查看版本 v4 详情',exact=True));await row.get_by_role('button',name='发布',exact=True).click();d=page.get_by_role('dialog')
   if mode=='blocked':await d.get_by_text(text,exact=True).wait_for();assert await d.get_by_role('button',name='确认发布',exact=True).is_disabled();assert not f.actions
   elif mode=='gate-error':await d.get_by_text(text,exact=True).wait_for();assert await d.get_by_role('button',name='确认发布',exact=True).is_enabled()
   else:
    await d.get_by_text('语义高风险提示，不阻断发布',exact=True).wait_for();await d.get_by_role('button',name='确认发布',exact=True).click();await page.wait_for_timeout(550);assert text in await page.locator('.releases-page').inner_text();assert len(f.actions)==1
    if mode=='unknown':
     await d.get_by_role('button',name='取消',exact=True).click();await d.wait_for(state='hidden')
     await page.get_by_role('button',name='核对该快照操作记录',exact=True).click();await page.get_by_text('已重读精确快照审计，尚不能确认原请求结果',exact=False).first.wait_for();assert len(f.actions)==1
   passed(mode+'发布门禁/恢复分支');evidence.append(dict(scenario=mode,requests=f.calls,actions=f.actions));await c.close()
  c,page,f=await open_page(mode='release-only');await page.wait_for_selector('.el-table__row');await page.get_by_role('button',name='查看版本 v4 详情',exact=True).click();d=page.get_by_role('dialog');await d.get_by_text('没有此源的 metadata:view',exact=False).wait_for();await d.get_by_role('tab',name='操作记录').click();await d.get_by_text('合成原始操作记录').wait_for();assert not any(re.fullmatch(r'/api/admin/metadata/snapshots/\d+',x['path']) for x in f.calls);passed('最小发布查看者不读结构，审计门禁仍可达');await c.close()
  c,page,f=await open_page('/admin/releases/snapshots/11/diff/12?datasourceId=1');await page.get_by_text('order_adjustments',exact=False).wait_for();assert '明确扣除优惠的成交金额' in await page.locator('.snapshot-diff-page').inner_text();await capture(page,'04-snapshot-diff-mock-desktop.png');passed('版本差异方向、表变化及纯注释变更');await c.close()
  c,page,f=await open_page('/admin/releases/snapshots/12?datasourceId=1');await page.wait_for_selector('.snapshot-detail-content');assert 'v4' in await page.locator('.snapshot-detail-page').inner_text();await capture(page,'05-snapshot-detail-mock-desktop.png');passed('独立详情精确版本及门禁');await c.close()
  for mode,selector in [('readonly','.el-table__row'),('empty','.empty-state'),('list-error','.error-state'),('no-view','.release-action-notice')]:
   c,page,f=await open_page(mode=mode);await page.wait_for_selector(selector)
   if mode=='readonly':assert await page.get_by_role('button',name='发布',exact=True).count()==0
   if mode=='no-view':assert not any(x['path'].endswith('/version-history') for x in f.calls)
   passed(mode+'加载/空/失败/能力分支');await c.close()
  for w,h in [(390,844),(768,1024),(1024,768),(1440,900),(844,390)]:
   c,page,f=await open_page(width=w,height=h);await page.wait_for_selector('.el-table__row');assert await page.evaluate('document.documentElement.scrollWidth <= innerWidth+1')
   if w==390:await capture(page,'06-releases-mock-mobile.png')
   await c.close()
  passed('五视口无整页横溢出');assert not errors;passed('无页面异常或应用全局错误')
  (out/'releases-browser-verification.json').write_text(json.dumps(dict(kind='real Vue and Chromium; synthetic API only',checks=checks,browserErrors=errors,requestEvidence=evidence),ensure_ascii=False,indent=2));print(json.dumps(dict(passed=len(checks),errors=errors)))
  await browser.close()
if __name__=='__main__':asyncio.run(main())
