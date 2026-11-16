"""Real Vue/Chromium acceptance. Synthetic API only; loopback only."""
import asyncio,json,re,sys
from pathlib import Path
from urllib.parse import urlparse,parse_qs
sys.path.insert(0,str(Path(__file__).resolve().parent))
from releases_knowledge_v2 import ReleaseFixture
from datasource_workspace_v2 import READINESS
from playwright.async_api import async_playwright
OUT=Path('output/playwright/releases-knowledge-v2-20261029')
def doc(id=23,status='DRAFT'):
 return dict(id=id,datasourceId=1,title='订单主题 · 完整业务知识',content='# 订单业务知识\n\n## 订单成交金额\n每笔订单扣除订单优惠后的实际成交金额。\n\n- 单位：元 / CNY\n- 退款与取消的处理取决于业务场景，需明确过滤条件。\n- 来源：合成订单手册 v2 §3，审核记录 #301\n\n<!-- 合成示例，非业务事实 -->',status=status,currentVersion=2,version=3,tableNames='["orders","order_items"]',createdAt='2026-10-28T09:00:00',updatedAt='2026-10-29T10:00:00')
class KnowledgeFixture(ReleaseFixture):
 def __init__(self,mode='normal'):
  super().__init__(mode);self.docs=[doc(21,'PUBLISHED'),{**doc(22,'PENDING_REVIEW'),'title':'订单指标 · 待审核'},doc(23)];self.build_count=0
 async def route(self,route):
  req=route.request;u=urlparse(req.url);p=u.path;q=parse_qs(u.query)
  async def send(data,status=200,msg='ok'):
   self.calls.append(dict(path=p,method=req.method,query=q));await route.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=msg,data=data),ensure_ascii=False))
  if p=='/api/iam-s1/capabilities':
   codes=['knowledge:view']+(['knowledge:manage','knowledge:approve','knowledge:publish'] if self.mode=='knowledge-only' else [])
   return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=self.mode not in ['knowledge-only','readonly'],globalFunctions=[],datasourceCapabilities=[dict(datasourceId=i,datasourceName='销售经营' if i==1 else '供应链',functionCodes=codes,functionNames=[]) for i in [1,2]],queryUse=True,viewSql=True,export=True))
  if p=='/api/admin/datasources/readiness/batch':return await send([READINESS[1],READINESS[2]])
  if p=='/api/admin/knowledge-docs/generation-inputs':return await send(dict(datasourceId=int(q['datasourceId'][0]),snapshotId=int(q['snapshotId'][0]),snapshotVersion=3,tableCount=8,columnCount=64,tableNames=['orders','order_items'],glossaryFacts=[],excludedTerms=[]))
  if re.fullmatch(r'/api/admin/catalog/datasources/\d+/join-paths',p):return await send([])
  if p.endswith('/join-paths/options'):return await send(dict(tables=[]))
  if p=='/api/admin/knowledge-docs/rag-builds':
   if req.method=='POST':
    self.build_count+=1;self.actions.append(dict(path=p,method=req.method,payload=req.post_data_json));await asyncio.sleep(.4)
    if self.mode=='unknown':return await route.abort('connectionfailed')
    return await send(dict(buildId='synthetic-build-new',datasourceId=1,sourceSnapshotId=req.post_data_json['snapshotId'],generation=2,status='QUEUED',expectedChunkCount=12,actualVectorCount=0,embeddingModel='合成模型',embeddingDimension=1024,confirmedAt='2026-10-29T10:20:00'))
   return await send([dict(buildId='synthetic-build-old',datasourceId=1,sourceSnapshotId=11,generation=1,status='ACTIVE',expectedChunkCount=12,actualVectorCount=12,embeddingModel='合成模型',embeddingDimension=1024,confirmedAt='2026-10-28T10:00:00',activatedAt='2026-10-28T10:01:00')]+([dict(buildId='synthetic-build-new',datasourceId=1,sourceSnapshotId=12,generation=2,status='BUILDING',expectedChunkCount=12,actualVectorCount=5,embeddingModel='合成模型',embeddingDimension=1024,confirmedAt='2026-10-29T10:20:00')] if self.build_count else []))
  if p=='/api/admin/knowledge-docs':
   if req.method=='POST':self.actions.append(dict(path=p,method=req.method,payload=req.post_data_json));return await send(dict(id=24))
   if self.mode=='list-error':return await send(None,503,'合成知识列表失败')
   rows=[] if self.mode=='empty' else [d for d in self.docs if not q.get('status') or d['status']==q['status'][0]]
   return await send(dict(records=rows,total=len(rows)))
  m=re.fullmatch('/api/admin/knowledge-docs/(\\d+)(?:/(.*))?',p)
  if m:
   id=int(m[1]);d=next((x for x in self.docs if x['id']==id),doc(id));action=m[2]
   if not action:
    if req.method=='PUT':
     self.actions.append(dict(path=p,method=req.method,payload=req.post_data_json));await asyncio.sleep(.35)
     if self.mode=='conflict':return await send(None,409,'合成版本冲突，请保留草稿')
     d.update(title=req.post_data_json['title'],content=req.post_data_json['content'],version=d['version']+1,currentVersion=d['currentVersion']+1,status='DRAFT');return await send(None)
    return await send(d)
   if action=='source-status':return await send(dict(valid=self.mode!='source-invalid',snapshotId=11,reasons=['合成来源修订已变化，需要重新审核'] if self.mode=='source-invalid' else []))
   if action=='versions':return await send([dict(id=102,docId=id,versionNo=d['currentVersion'],content=d['content'],generationSource='MANUAL',reviewStatus='APPROVED' if d['status']=='PUBLISHED' else 'PENDING' if d['status']=='PENDING_REVIEW' else 'DRAFT',metadataSnapshotId=11,createdAt=d['updatedAt']),dict(id=101,docId=id,versionNo=1,content='# 旧版合成知识',generationSource='SNAPSHOT',reviewStatus='APPROVED',metadataSnapshotId=11,createdAt=d['createdAt'])])
   if action=='source-snapshots':return await send([dict(docId=id,versionNo=d['currentVersion'],snapshotId=11,snapshotVersion=3,status='EXPIRED',tableCount=8,columnCount=64)])
   if action=='review-tasks':return await send([dict(id=301,docId=id,versionNo=1,reviewStatus='APPROVED',reviewerName='合成审核人',reviewComment='已核对原始合成资料',createdAt=d['updatedAt'],reviewedAt=d['updatedAt'])])
   if action=='vector-tasks':return await send([])
   if action=='preview-chunks':return await send([dict(chunk_type='FIELD_SEMANTIC',chunk_text='合成：订单成交金额，元 / CNY',source_snapshot_id='11',revision_id='301',evidence_hash='synthetic-evidence-hash')])
   if action=='versions/diff':return await send([dict(type='DELETE',content='旧合成口径'),dict(type='ADD',content='新合成口径')])
   if action in ['submit-review','approve','reject','publish','rollback']:
    self.actions.append(dict(path=p,method=req.method,payload=req.post_data_json));await asyncio.sleep(.3)
    if self.mode=='conflict':return await send(None,409,'合成版本冲突，请保留草稿')
    d['status']={'submit-review':'PENDING_REVIEW','approve':'APPROVED','reject':'DRAFT','publish':'PUBLISHED','rollback':'DRAFT'}[action];d['version']+=1;return await send(None)
  return await super().route(route)
async def main():
 import argparse
 p=argparse.ArgumentParser();p.add_argument('--baseline',action='store_true');p.add_argument('--output',default=str(OUT));args=p.parse_args();out=Path(args.output);out.mkdir(parents=True,exist_ok=True);checks=[];errors=[];evidence=[]
 def passed(name):checks.append(dict(name=name,status='passed'))
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/usr/bin/chromium')
  async def open_page(path='/admin/semantics/knowledge?datasourceId=1',mode='normal',width=1600,height=1050):
   c=await b.new_context(viewport=dict(width=width,height=height),device_scale_factor=1.5,reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-admin-fixture');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))");f=KnowledgeFixture(mode);await c.route('http://127.0.0.1:5173/api/**',f.route);page=await c.new_page();page.on('pageerror',lambda e:errors.append(str(e)));page.on('console',lambda msg:errors.append(msg.text) if '[全局错误]' in msg.text else None);await page.goto('http://127.0.0.1:5173'+path);await page.locator('h1').wait_for();return c,page,f
  async def capture(page,name):
   await page.wait_for_timeout(350);await page.evaluate("""()=>{const t=document.createElement('div');t.textContent='第五阶段浏览器验收 · 合成 API 响应';t.style='position:fixed;bottom:2px;right:8px;z-index:9999;font-size:10px;color:#676d67;background:#fff';document.body.appendChild(t)}""");await page.screenshot(path=str(out/name),full_page=True)
  c,page,f=await open_page();await page.wait_for_selector('.doc-link');await capture(page,'00-baseline-knowledge.png' if args.baseline else '07-knowledge-documents-mock-desktop.png');assert await page.locator('.doc-link').count()==3;passed('列表服务端统计与主题文档、来源准备按需展开');await c.close()
  c,page,f=await open_page('/admin/semantics/knowledge/23?datasourceId=1');await page.wait_for_selector('.knowledge-doc-page__editor')
  if args.baseline:await capture(page,'00-baseline-document.png');await b.close();return
  await capture(page,'08-document-editor-mock-desktop.png');area=page.get_by_label('知识文档正文',exact=True);await area.fill('合成：第一稿');assert await page.get_by_role('button',name='提交审核',exact=True).is_disabled();passed('未保存正文不能提交旧修订')
  await page.get_by_role('button',name='保存',exact=True).click(click_count=2);await area.fill('合成：第一稿 + 等待新增');await page.wait_for_timeout(700);assert await area.input_value()=='合成：第一稿 + 等待新增';assert await page.get_by_role('button',name='保存',exact=True).is_enabled();await page.get_by_role('button',name='保存',exact=True).click();await page.wait_for_timeout(600);puts=[a for a in f.actions if a['method']=='PUT'];assert len(puts)==2 and puts[1]['payload']['version']==4;passed('保存重复保护、等待新增输入保留及自己的新版本继续保存')
  await page.get_by_role('button',name='提交审核',exact=True).click();await page.locator('.el-message-box__btns .el-button--primary').click(click_count=2);await page.wait_for_timeout(500);assert len([a for a in f.actions if a['path'].endswith('/submit-review')])==1;passed('提交真实保存版本、重复确认只写一次');evidence.append(dict(scenario='editor',requests=f.calls,actions=f.actions));await c.close()
  c,page,f=await open_page('/admin/semantics/knowledge/22?datasourceId=1&tab=review');await page.get_by_text('已核对原始合成资料',exact=True).wait_for();await capture(page,'09-document-review-mock-desktop.png');await page.get_by_role('button',name='审核通过',exact=True).click();await page.locator('.el-message-box__btns .el-button--primary').click();await page.get_by_role('button',name='发布文档',exact=True).wait_for();await page.get_by_role('button',name='发布文档',exact=True).click();await page.locator('.el-message-box__btns .el-button--primary').click();await page.wait_for_timeout(600);assert len([a for a in f.actions if a['path'].endswith('/publish')])==1 and f.build_count==0;passed('审核与文档发布独立、不自动构建');evidence.append(dict(scenario='review-publish',requests=f.calls,actions=f.actions));await c.close()
  c,page,f=await open_page('/admin/semantics/knowledge?datasourceId=1&tab=index','knowledge-only');await page.get_by_role('button',name='明确确认 RAG 构建',exact=True).wait_for();assert not any(x['path'].endswith('/readiness/batch') or x['path'].endswith('/published-snapshot') or x['path']=='/api/admin/metadata/snapshots' for x in f.calls);await capture(page,'10-rag-workspace-mock-desktop.png');await page.get_by_role('button',name='明确确认 RAG 构建',exact=True).click();d=page.get_by_role('dialog');await d.locator('.knowledge-build-snapshot input').fill('11');await d.locator('.knowledge-build-snapshot input').press('Tab');await capture(page,'11-rag-confirmation-mock-desktop.png');await d.get_by_role('button',name='确认构建',exact=True).click(click_count=2);await page.wait_for_timeout(650);assert f.build_count==1;assert f.actions[0]['payload']==dict(datasourceId=1,snapshotId=11,confirmed=True);passed('最小知识角色可显式构建历史来源，重复只写一次');assert 'synthetic-build-old' in await page.locator('.knowledge-page').inner_text();passed('新构建进行中仍保留旧活动build与来源身份');evidence.append(dict(scenario='knowledge-only-build',requests=f.calls,actions=f.actions));await c.close()
  for mode in ['conflict','source-invalid','readonly']:
   c,page,f=await open_page('/admin/semantics/knowledge/23?datasourceId=1',mode);await page.wait_for_selector('.knowledge-doc-page__editor')
   if mode=='conflict':await page.get_by_label('知识文档正文',exact=True).fill('合成：冲突不能丢');await page.get_by_role('button',name='保存',exact=True).click();await page.wait_for_timeout(600);assert await page.get_by_label('知识文档正文',exact=True).input_value()=='合成：冲突不能丢';assert '不会自动重试' in await page.locator('.knowledge-doc-page').inner_text()
   elif mode=='source-invalid':assert '合成来源修订已变化' in await page.locator('.knowledge-doc-page').inner_text();await capture(page,'12-document-source-invalid-mock-desktop.png')
   else:
    assert await page.get_by_label('知识文档正文',exact=True).get_attribute('readonly') is not None
    for tab in ['来源与覆盖','审核记录','版本','切分预览','索引状态']:await page.get_by_role('tab',name=tab,exact=True).click();await page.wait_for_timeout(120)
    assert any(x['path'].endswith('/preview-chunks') for x in f.calls);assert not any(x['path']=='/api/admin/metadata/snapshots' for x in f.calls)
   passed(mode+'文档能力/失败/来源分支');evidence.append(dict(scenario=mode,requests=f.calls,actions=f.actions));await c.close()
  c,page,f=await open_page('/admin/semantics/knowledge?datasourceId=1&tab=index','unknown');await page.get_by_role('button',name='明确确认 RAG 构建',exact=True).click();d=page.get_by_role('dialog');await d.locator('.knowledge-build-snapshot input').fill('11');await d.locator('.knowledge-build-snapshot input').press('Tab');await d.get_by_role('button',name='确认构建',exact=True).click();await page.wait_for_timeout(650);await d.get_by_role('button',name='取消',exact=True).click();await d.wait_for(state='hidden');await page.get_by_role('button',name='刷新构建记录',exact=True).click();await page.wait_for_timeout(150);assert await page.get_by_role('button',name='明确确认 RAG 构建',exact=True).is_disabled();assert f.build_count==1;passed('构建断网保留原目标未知，旧ACTIVE及重读不解锁');await c.close()
  for mode in ['empty','list-error']:
   c,page,f=await open_page(mode=mode);await page.wait_for_selector('.empty-state' if mode=='empty' else '.error-state');passed(mode+'文档列表分支');await c.close()
  c,page,f=await open_page('/admin/semantics/knowledge/new?datasourceId=1','knowledge-only');await page.get_by_role('button',name='创建文档',exact=True).wait_for();await page.get_by_placeholder('例如：订单域业务语义').fill('合成创建主题');await page.get_by_role('button',name='创建文档',exact=True).click(click_count=2);await page.wait_for_timeout(300);assert len([a for a in f.actions if a['path']=='/api/admin/knowledge-docs' and a['method']=='POST'])==1;passed('最小维护能力手建，不需要元数据读取');await c.close()
  for width,height in [(390,844),(768,1024),(1024,768),(1440,900),(844,390)]:
   for path,selector,name in [('/admin/semantics/knowledge?datasourceId=1','.doc-link','13-knowledge-mock-mobile.png'),('/admin/semantics/knowledge/23?datasourceId=1','.knowledge-doc-page__editor','14-document-mock-mobile.png')]:
    c,page,f=await open_page(path,width=width,height=height);await page.wait_for_selector(selector);assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
    if width==390:
     assert await page.locator('.task-page-header__actions').evaluate('(el)=>[...el.querySelectorAll("button")].every(b=>b.getBoundingClientRect().right<=innerWidth+1)')
     await capture(page,name)
    await c.close()
  passed('知识与详情五视口无整页溢出');assert not errors;passed('无页面异常或应用全局错误');(out/'knowledge-browser-verification.json').write_text(json.dumps(dict(kind='real Vue and Chromium; synthetic API only',checks=checks,browserErrors=errors,requestEvidence=evidence),ensure_ascii=False,indent=2));print(json.dumps(dict(passed=len(checks),errors=errors)));await b.close()
if __name__=='__main__':asyncio.run(main())
