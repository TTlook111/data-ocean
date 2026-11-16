"""Final cross-page browser journey. All API responses use one mutable synthetic state.
Run against a loopback Vite frontend. No API request is allowed to reach a real service.
Every run gets a new directory; failures/traces are retained, never overwritten.
"""
import argparse, asyncio, hashlib, json, re, subprocess, time
from pathlib import Path
from urllib.parse import urlparse
from playwright.async_api import async_playwright, expect
from stateful_journey_fixture import JourneyFixture, SQL_HASH
BASE='http://127.0.0.1:5173'

async def main():
 ap=argparse.ArgumentParser();ap.add_argument('--base-url',default=BASE);ap.add_argument('--output',default='output/playwright/unified-journey-v2-20261029');ap.add_argument('--happy-only',action='store_true');ap.add_argument('--chromium',default='/usr/bin/chromium' if Path('/usr/bin/chromium').is_file() else None);args=ap.parse_args()
 if urlparse(args.base_url).hostname not in ['127.0.0.1','localhost','::1']:raise SystemExit('Only loopback frontend is allowed')
 out=Path(args.output)/time.strftime('run-%Y%m%dT%H%M%S');out.mkdir(parents=True,exist_ok=False)
 project=Path(__file__).resolve().parents[2]
 source_hashes={str(p.relative_to(project)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((project/'frontend/src').rglob('*')) if p.is_file()}
 baseline=dict(gitHead=subprocess.check_output(['git','rev-parse','HEAD'],cwd=project,text=True).strip(),frontendTreeHash=hashlib.sha256(json.dumps(source_hashes,sort_keys=True).encode()).hexdigest(),scripts={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [Path(__file__),Path(__file__).with_name('stateful_journey_fixture.py'),Path(__file__).with_name('stateful_route_cases_v2.json')]})
 state=JourneyFixture();checks=[];errors=[];step='initial';page=None;contexts=[];screens=[]
 def passed(name,**facts):checks.append(dict(name=name,status='passed',**facts));print('PASS',name,flush=True)
 async def snap(p,name):
  await p.evaluate("""()=>{let e=document.getElementById('journey-label');if(!e){e=document.createElement('div');e.id='journey-label';document.body.append(e)}e.textContent='统一有状态场景 · 合成API · 非真实后端';e.style.cssText='position:fixed;right:8px;bottom:4px;z-index:99999;background:#fff6e9;color:#725034;padding:5px;font:11px sans-serif;pointer-events:none'}""")
  await p.screenshot(path=str(out/name),full_page=True);screens.append(name)
 async def context(browser,width=1600):
  c=await browser.new_context(viewport=dict(width=width,height=1050),reduced_motion='reduce');contexts.append(c);await c.route(args.base_url+'/api/**',state.route)
  # Fail closed: never allow a fixture miss or external model/API request to leave loopback.
  await c.route(re.compile(r'https?://(?!'+re.escape(urlparse(args.base_url).netloc+'/')+r')'),lambda r:r.abort('blockedbyclient'))
  await c.tracing.start(screenshots=True,snapshots=True,sources=False);p=await c.new_page();p.on('pageerror',lambda e:errors.append(str(e)));p.on('console',lambda m:errors.append(m.text[:500]) if any(x in m.text for x in ['Unhandled error during execution','[全局错误]','TypeError:','ReferenceError:']) else None);return c,p
 async def login(p,actor):
  await p.goto(args.base_url+'/login');await p.get_by_placeholder('请输入账号',exact=True).fill(actor);await p.get_by_placeholder('请输入密码',exact=True).fill('Synthetic123');await p.get_by_placeholder('请输入验证码',exact=True).fill('A1B2');await p.get_by_role('button',name='登录',exact=True).click();await p.wait_for_url('**/query');await p.wait_for_timeout(150)
 async def nav(p,domain,label):
  await p.get_by_role('navigation',name='后台业务导航',exact=True).get_by_role('link',name=domain,exact=True).click()
  await p.get_by_role('navigation',name='后台业务导航',exact=True).get_by_role('link',name=label,exact=True).click()
 async def confirm(p,name=None):
  box=p.locator('.el-message-box');await box.wait_for(state='visible')
  if name:await box.get_by_role('button',name=name,exact=True).click()
  else:await box.locator('.el-message-box__btns button').last.click()
 async with async_playwright() as pw:
  browser=await pw.chromium.launch(executable_path=args.chromium)
  try:
   c,page=await context(browser);step='operator login';await login(page,'operator');await page.goto(args.base_url+'/admin/data-sources');await page.get_by_role('heading',name='数据源',exact=True).wait_for()
   row=page.locator('.el-table__row').filter(has_text='合成销售经营');await row.get_by_role('button',name='查看详情',exact=True).click();await page.get_by_role('button',name='编辑连接',exact=True).wait_for();assert not state.connected[1] and 11 not in state.snapshots;await snap(page,'01-source-unprepared.png');passed('同一场景初始：源1未核验、未采集，不冒充已就绪')
   step='connection edit';await page.get_by_role('button',name='编辑连接',exact=True).click();d=page.locator('.data-source-dialog');await d.get_by_label('说明',exact=True).fill('合成：完整旅程接入说明，后续版本与口径逐步核验');await d.get_by_label('密码',exact=True).fill('SyntheticOnly123');await d.get_by_role('button',name='测试连接',exact=True).click();await page.get_by_text('连接测试已通过，可以保存。',exact=True).wait_for();assert not state.connected[1];await d.get_by_role('button',name='保存',exact=True).click();await d.wait_for(state='hidden');assert state.connected[1] and any(t['event']=='SOURCE_UPDATED' for t in state.transitions);assert 11 not in state.snapshots;passed('真实连接表单测试与PUT保存，连接成功未自动采集/发布')
   step='collect failure retry';await nav(page,'数据接入','采集任务');await page.get_by_role('button',name='发起全量采集',exact=True).click();await page.get_by_role('button',name='开始采集',exact=True).click(click_count=2);await page.get_by_text('合成：首次采集连接超时，请明确重试',exact=False).wait_for();assert len(state.jobs)==1 and 11 not in state.snapshots
   await page.locator('.el-table__row').filter(has_text='首次采集').get_by_role('button',name='重试',exact=True).click();await page.get_by_role('link',name='查看生成快照',exact=True).wait_for();assert state.snapshots[11]['status']=='DRAFT' and len(state.jobs)==2;await snap(page,'02-collection-retry.png');passed('采集失败后由UI明确重试，重复点击一次提交，成功生成精确11/v1草稿')
   step='snapshot structure';await page.get_by_role('link',name='查看生成快照',exact=True).click();await page.get_by_role('heading',name='快照详情',exact=True).wait_for();assert 'orders' in await page.locator('.snapshot-detail-page').inner_text();await snap(page,'03-collected-snapshot.png');passed('采集回执→快照详情为同一11/v1及orders.amount结构')
   step='field draft';await nav(page,'语义中心','字段解释');await page.goto(args.base_url+'/admin/semantics/field-explanations?datasourceId=1&snapshotId=11&tableName=orders&columnMetaId=204');await page.get_by_label('字段含义',exact=True).wait_for();assert state.field['currentApproved'] is None
   await page.get_by_role('button',name='生成 AI 候选',exact=True).click();await page.get_by_label('字段含义',exact=True).fill('合成：订单优惠扣减后的成交金额，退款按原月净额扣回，单位元。');await page.get_by_label('业务依据',exact=True).fill('合成订单手册v1第3节，订单101原预期100元')
   await expect(page.get_by_role('button',name='保存草稿',exact=True)).to_be_enabled();await page.get_by_role('button',name='保存草稿',exact=True).click();await expect(page.get_by_role('button',name='提交审核',exact=True)).to_be_enabled();await page.get_by_role('button',name='提交审核',exact=True).click();await expect(page.get_by_role('tab',name='人工审核',exact=True)).to_be_visible();assert state.field['workingStatus']=='PENDING_REVIEW' and state.field['currentApproved'] is None;await snap(page,'04-field-submitted.png');passed('同字段AI候选→人工依据/草稿→精确修订提交，候选未自动成事实')
   # Separate browser session uses independent review rights against the same server state.
   step='independent review';rc,rp=await context(browser);await login(rp,'reviewer');await rp.goto(args.base_url+'/admin/semantics/field-explanations?datasourceId=1&snapshotId=11&tableName=orders&columnMetaId=204');await rp.get_by_role('tab',name='人工审核',exact=True).click();await rp.locator('.field-review-preview').wait_for();assert '退款按原月' in await rp.locator('.field-review-preview').inner_text()
   await rp.get_by_label('字段解释原始资料引用',exact=True).fill('合成订单手册v1第3节');await rp.get_by_label('字段解释审核依据',exact=True).fill('人工核对原始情景、字段用途与单位一致');await rp.get_by_text('已人工核对原始资料；AI 自述不作为确认依据',exact=True).click();await rp.get_by_role('button',name='审核通过',exact=True).click(click_count=2);await rp.wait_for_timeout(200);assert state.field['currentApprovedRevisionId']==302 and state.field['draft'] is None;assert not state.published() and not state.docs and not state.builds;await snap(rp,'05-independent-review.png');passed('独立审核账号确认修订302；审核不自动发布快照/知识/构建')
   step='release';await nav(rp,'数据资产','元数据版本');rr=rp.locator('.el-table__row').filter(has=rp.get_by_role('button',name='查看版本 v1 详情',exact=True));await rr.get_by_role('button',name='进入审核',exact=True).click();await confirm(rp);await rr.get_by_role('button',name='批准',exact=True).wait_for();await rr.get_by_role('button',name='批准',exact=True).click();await confirm(rp);await rr.get_by_role('button',name='发布',exact=True).wait_for();await rr.get_by_role('button',name='发布',exact=True).click();await rp.get_by_role('button',name='确认发布',exact=True).click(click_count=2);await rp.wait_for_timeout(200);assert state.published()['id']==11 and not state.docs and not state.builds;await snap(rp,'06-metadata-published.png');passed('11/v1经审核批准再显式发布，发布未自动生成知识或构建')
   step='published object';await page.goto(args.base_url+'/admin/assets?datasourceId=1');await page.get_by_role('button',name='销售订单',exact=True).click();await page.locator('.entity-detail__panel').wait_for();assert '与发布目标一致' in await page.locator('.entity-detail__meta').inner_text() and state.published()['id']==11;await page.get_by_role('button',name='查看字段',exact=True).nth(3).click();await page.wait_for_url('**/104?**');await page.locator('.entity-detail__summary').wait_for();assert '成交金额' in await page.locator('h1').inner_text();await snap(page,'06b-exact-published-object.png');passed('同一发布目录→表对象→amount字段104，来源11与语义字段204分清')
   step='knowledge generated';await nav(page,'语义中心','语义知识');await page.get_by_role('button',name='生成快照目录',exact=True).click();d=page.get_by_role('dialog');await expect(d.get_by_role('button',name='开始生成',exact=True)).to_be_enabled();await d.get_by_role('button',name='开始生成',exact=True).click();await d.get_by_role('button',name='编辑草稿',exact=True).click();await page.get_by_label('知识文档正文',exact=True).wait_for();assert state.docs[0]['sourceSnapshotId']==11 and state.docs[0]['status']=='DRAFT';passed('快照生成文档23关联11与已审字段事实，非手工新建伪造来源')
   area=page.get_by_label('知识文档正文',exact=True);await area.fill((await area.input_value())+'\n\n合成核验：订单101成交金额为100元，审核依据已注明。');await page.get_by_role('button',name='保存',exact=True).click();await expect(page.get_by_role('button',name='提交审核',exact=True)).to_be_enabled();await page.get_by_role('button',name='提交审核',exact=True).click();await confirm(page);await page.wait_for_timeout(120);assert state.docs[0]['status']=='PENDING_REVIEW'
   step='knowledge publish';await rp.goto(args.base_url+'/admin/semantics/knowledge/23?datasourceId=1&tab=review');await rp.get_by_role('button',name='审核通过',exact=True).click();await confirm(rp);await rp.get_by_role('button',name='发布文档',exact=True).wait_for();await rp.get_by_role('button',name='发布文档',exact=True).click();await confirm(rp);await rp.wait_for_timeout(120);assert state.docs[0]['status']=='PUBLISHED' and not state.builds;await snap(rp,'07-knowledge-published.png');passed('同文档保存修订→独立审核→发布，状态推进但未自动build')
   step='explicit build';await rp.goto(args.base_url+'/admin/semantics/knowledge?datasourceId=1&tab=index');await rp.get_by_role('button',name='明确确认 RAG 构建',exact=True).click();d=rp.get_by_role('dialog');await d.locator('.knowledge-build-snapshot input').fill('11');await d.locator('.knowledge-build-snapshot input').press('Tab');await d.get_by_role('button',name='确认构建',exact=True).click(click_count=2);await rp.get_by_text('journey-build-1',exact=False).first.wait_for();assert len(state.builds)==1 and state.builds[0]['status']=='ACTIVE';assert state.builds[0]['members'][0]['revision']==state.docs[0]['currentVersion'];await snap(rp,'08-explicit-build.png');passed('显式确认构建1，固定同文档修订/hash/快照/字段302，模拟worker核验后活动')
   step='ask';qc,qp=await context(browser);await login(qp,'reader');await qp.goto(args.base_url+'/query?datasourceId=1');await expect(qp.get_by_role('textbox',name='输入查询问题',exact=True)).to_be_enabled();await qp.get_by_role('textbox',name='输入查询问题',exact=True).fill('合成：按已核对净额口径查询订单101的成交金额');await qp.get_by_role('button',name='发送',exact=True).click();await qp.locator('.embedded-result').wait_for();assert state.query_no==1
   await qp.get_by_role('button',name='来源与依据',exact=True).click();d=qp.get_by_role('dialog',name='这条回答的结果与依据',exact=True);await d.get_by_role('tab',name='可信依据',exact=True).click();assert 'journey-build-1' in await d.inner_text() and SQL_HASH in await d.inner_text() and 'journey-attempt-1' in await d.inner_text();await snap(qp,'09-query-execution-evidence.png');passed('真实composer问数→同任务/快照11/build1/字段302/attempt及SQL hash证据，单次提交')
   await d.get_by_role('tab',name='SQL',exact=True).click();await d.locator('.sql-block').wait_for();assert 'SUM(amount)' in await d.locator('.sql-block').inner_text();await d.get_by_role('button',name='Close this dialog',exact=True).click()
   step='feedback submit';await qp.get_by_role('button',name='评价：结果有误',exact=True).click();d=qp.get_by_role('dialog');await d.locator('textarea').fill('合成：请核对订单101退款归属月，原标准100元');await d.get_by_role('button',name='补充说明',exact=True).click();await expect(qp.locator('.feedback-status')).to_contain_text('评价已记录，等待分析原因');assert len(state.feedback)==1 and state.feedback[0]['revisionId'] is None;await snap(qp,'10-feedback-from-answer.png');passed('本回答反馈关联同任务，UNKNOWN待归因，不提前选定字段修订')
   step='lineage';await nav(page,'数据资产','数据血缘');await page.goto(args.base_url+'/admin/assets/lineage?datasourceId=1&entityId=100');await page.get_by_role('button',name='查看查询使用',exact=True).click();await page.locator('.query-lineage-evidence').wait_for();assert '本有状态旅程' in await page.locator('.query-lineage-evidence').inner_text();assert state.projected=={'journey-query-1'};await snap(page,'11-query-lineage.png');passed('关系图空边与本次已投影查询使用分开，不由共现虚构JOIN或加工血缘')
   step='query audit';await page.goto(args.base_url+'/admin/operations/queries?datasourceId=1');await page.locator('.el-table__row').filter(has_text='合成：按已核对净额口径查询订单101的成交金额').wait_for();await page.get_by_role('button',name='详情',exact=True).first.click();await page.get_by_text('审计保存的 SQL',exact=True).wait_for();assert await page.locator('.sql-section pre').count()==0;await page.get_by_role('button',name='按当前权限读取 SQL',exact=True).click();await page.locator('.sql-section pre').wait_for();assert 'WHERE order_id = 101' in await page.locator('.sql-section pre').inner_text();assert len([r for r in state.journal if r['path']=='/api/admin/audit-logs/8801'])==2;await snap(page,'11b-same-query-audit.png');await page.keyboard.press('Escape');await page.locator('.el-overlay.is-drawer:visible').wait_for(state='hidden');passed('查询分析展示同任务数据库ID701/审计8801；SQL不默认显示，按当前权限再读')
   step='feedback attribution';await nav(page,'数据治理','字段治理');await page.goto(args.base_url+'/admin/governance/fields?datasourceId=1&snapshotId=11&tab=feedback');await page.get_by_text('合成：请核对订单101退款归属月，原标准100元',exact=True).wait_for();await page.get_by_role('button',name='分析与审核',exact=True).click();d=page.get_by_role('dialog');sels=d.locator('.el-select');await sels.nth(0).click();await page.get_by_role('option',name='字段含义',exact=True).click();await sels.nth(1).click();await page.get_by_role('option',name='orders.amount',exact=True).click();areas=d.locator('textarea');await areas.nth(0).fill('合成：据本任务已使用字段204和修订302归因');await areas.nth(1).fill('订单101，原筛选和净额口径不变');await areas.nth(2).fill('原业务标准100元');await d.get_by_role('button',name='确认归因',exact=True).click(click_count=2);await d.wait_for(state='hidden');assert state.feedback[0]['status']=='APPLIED' and state.feedback[0]['reasonCode']=='FIELD_MEANING' and state.feedback[0]['columnMetaId']==204 and state.feedback[0]['revisionId']==302 and state.field['currentApprovedRevisionId']==302;await snap(page,'12-feedback-attributed.png');passed('源级反馈UI精确归因204/302并固定原情景标准，未自动改正式事实/发布/build')
   # More branches are added below; all share this state and these real sessions.
   if not args.happy_only:
    step="adversarial branches"
    await adversarial(browser,context,login,nav,confirm,snap,passed,state,page,rp,qp,out,errors,args.base_url)
   assert not errors,errors
   assert not state.unhandled,state.unhandled
   assert not state.fixture_errors,state.fixture_errors
   assert all('status' in r or r.get('auxiliary') for r in state.journal),[r for r in state.journal if 'status' not in r and not r.get('auxiliary')]
   for path,expected in [('/api/admin/field-semantics/columns/204/review',1 if args.happy_only else 2),('/api/admin/snapshots/11/publish',1),('/api/admin/governance-feedback/81/review',1)]:
    assert sum(r['method'] in ['POST','PATCH'] and r['path']==path for r in state.journal)==expected,path
   result=dict(status='passed',baseline=baseline,unmodeledAuxiliaryReads=[dict(path=r['path'],status=r.get('status')) for r in state.journal if r.get('auxiliary')],synthetic=True,realBackendTested=False,unifiedStatefulJourneyExecuted=True,checks=checks,browserErrors=errors,transitions=state.transitions,requests=state.journal,screenshots=screens,finalFacts=dict(snapshot=state.published(),fieldRevision=state.field['currentApprovedRevisionId'],docs=state.docs,builds=state.builds,queries=list(state.query_tasks.values()),feedback=state.feedback))
   (out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print('RESULT',out,len(checks),'passed',flush=True)
  except BaseException as e:
   if page:
    try:await snap(page,'FAILED-current-page.png')
    except:pass
   (out/'failure.json').write_text(json.dumps(dict(status='failed',baseline=baseline,step=step,error=str(e),checks=checks,browserErrors=errors,transitions=state.transitions,requests=state.journal,unhandled=state.unhandled,fixtureErrors=state.fixture_errors),ensure_ascii=False,indent=2));print('FAILED',step,str(e),out,flush=True);raise
  finally:
   for i,c in enumerate(contexts):
    try:await c.tracing.stop(path=str(out/f'trace-{i}.zip'));await c.close()
    except:pass
   await browser.close()

async def adversarial(browser,context,login,nav,confirm,snap,passed,state,page,rp,qp,out,errors,base):
 # These branches use the SAME state produced by the UI journey above. Faults,
 # role revocation and worker completion are explicit external test events.
 c,read=await context(browser);await login(read,'readonly')
 await read.goto(base+'/admin/semantics/field-explanations?datasourceId=1&snapshotId=11&columnMetaId=204&tableName=orders')
 await read.get_by_label('字段含义',exact=True).wait_for()
 assert await read.get_by_role('button',name='保存草稿',exact=True).count()==0 or await read.get_by_role('button',name='保存草稿',exact=True).is_disabled()
 assert state.field['currentApprovedRevisionId']==302
 await snap(read,'13-readonly-governance.png');passed('只读角色读同一事实，不能生成/保存/审核或自动补权')

 c,meta=await context(browser);await login(meta,'metadata_only')
 await meta.goto(base+'/admin/releases/snapshots/11?datasourceId=1')
 await meta.get_by_role('heading',name='快照详情',exact=True).wait_for()
 link=meta.get_by_role('link',name='返回有权数据工作区',exact=True)
 await expect(link).to_have_attribute('href',re.compile(r'/admin/assets\?.*datasourceId=1.*snapshotId=11'))
 assert not any(r['actor']=='metadata_only' and '/release-gate' in r['path'] for r in state.journal)
 await link.click();await meta.wait_for_url('**/admin/assets?**')
 passed('metadata最小角色结构详情返回合法对象目录，不读发布门禁辅助接口')
 c,scoped=await context(browser);await login(scoped,'scoped')
 await scoped.goto(base+'/admin/releases/snapshots/11?datasourceId=1')
 await scoped.get_by_text('需要当前数据源的 metadata:view 才能读取快照结构。',exact=True).wait_for()
 assert not any(r['actor']=='scoped' and r['path']=='/api/admin/metadata/snapshots/11' for r in state.journal)
 passed('功能在源2的绑定不能读取源1，粗功能入口不补源权限')

 # A read failure can retry; an unknown accepted write cannot auto-resubmit.
 state.faults[('GET','/api/admin/datasources')]='503'
 await page.goto(base+'/admin/data-sources');await page.get_by_role('button',name='重试',exact=True).click()
 await page.locator('.el-table__row').filter(has_text='合成销售经营').wait_for()
 passed('来源列表503明确失败后安全重读，不用空列表冒充成功')
 state.hold_builds=True;state.faults[('POST','/api/admin/knowledge-docs/rag-builds')]='unknown'
 await rp.goto(base+'/admin/semantics/knowledge?datasourceId=1&tab=index')
 await rp.get_by_role('button',name='明确确认 RAG 构建',exact=True).click()
 d=rp.get_by_role('dialog');await d.locator('.knowledge-build-snapshot input').fill('11');await d.locator('.knowledge-build-snapshot input').press('Tab')
 await d.get_by_role('button',name='确认构建',exact=True).click();await rp.get_by_text('结果未知',exact=False).first.wait_for()
 await d.get_by_role('button',name='取消',exact=True).click();await rp.get_by_role('button',name='刷新构建记录',exact=True).click()
 await expect(rp.get_by_role('button',name='明确确认 RAG 构建',exact=True)).to_be_disabled()
 assert len(state.builds)==2 and state.builds[0]['status']=='ACTIVE' and state.builds[1]['status']=='QUEUED'
 await snap(rp,'14-build-unknown-old-active.png');passed('服务端接收build2后断网：未知写锁定、只重读、旧活动build1保留')
 state.hold_builds=False;await rp.get_by_role('button',name='刷新构建记录',exact=True).click()
 await expect(rp.get_by_text('当前活动build：journey-build-2',exact=False)).to_be_visible()
 assert len(state.builds)==2 and state.query_tasks['journey-query-1']['buildId']=='journey-build-1'
 await expect(rp.get_by_role('button',name='明确确认 RAG 构建',exact=True)).to_be_disabled()
 passed('模拟worker核验后build2切换，在途/历史任务仍固定旧build1；未自动重发')

 # Return to the exact source/task through browser history and reload.
 await qp.reload();await qp.locator('.embedded-result').wait_for()
 await qp.goto(base+'/guide/query');await qp.go_back();await qp.locator('.embedded-result').wait_for()
 assert state.query_no==1
 async with qp.expect_download() as download:
  await qp.locator('.embedded-result').get_by_role('button',name='导出 CSV',exact=True).click()
 assert (await download.value).suggested_filename.endswith('.csv')
 passed('会话返回/刷新恢复原任务不重问，CSV按当前身份重新请求')
 state.revoked.add(('reader',1));state.permission_revision+=1
 await qp.get_by_role('button',name='SQL',exact=True).click()
 await qp.locator('.sql-read-error').wait_for()
 assert await qp.locator('.sql-block').count()==0
 assert any(r['path']=='/api/iam-s1/query/tasks/journey-query-1/sql' and r.get('status')==403 for r in state.journal)
 await snap(qp,'15-revoked-sql.png');await qp.reload()
 await qp.get_by_text('从一份业务数据开始',exact=True).wait_for()
 assert await qp.locator('.embedded-result').count()==0
 passed('撤业务数据权后SQL重读403并不显示SQL，刷新后清空旧回答/历史')
 state.revoked.remove(('reader',1));state.permission_revision+=1
 await qp.reload();await qp.locator('.embedded-result').wait_for()
 state.hold_queries=True
 await qp.get_by_role('textbox',name='输入查询问题',exact=True).fill('合成：停止与明确重试同一订单问题')
 await qp.get_by_role('button',name='发送',exact=True).click();await qp.get_by_role('button',name='停止查询',exact=True).click()
 await qp.get_by_text('已停止',exact=True).wait_for()
 assert state.query_tasks['journey-query-2']['status']=='CANCELLED'
 state.hold_queries=False
 await qp.get_by_role('button',name='保留原问题重试',exact=True).click()
 await expect(qp.locator('.embedded-result')).to_have_count(2)
 assert state.query_no==3 and state.query_tasks['journey-query-3']['status']=='COMPLETED'
 passed('停止产生终态而非假成功；保留原问题由用户明确重新发送，未无限重试')

 # Another collected version has distinct column IDs. It is not a publication.
 await page.goto(base+'/admin/collections?datasourceId=1');await page.get_by_role('button',name='发起全量采集',exact=True).click();await page.get_by_role('button',name='开始采集',exact=True).click()
 await page.get_by_role('link',name='查看生成快照',exact=True).first.wait_for()
 assert state.snapshots[12]['status']=='DRAFT' and state.published()['id']==11
 await page.goto(base+'/admin/releases/snapshots/12?datasourceId=1');await page.get_by_role('heading',name='快照详情',exact=True).wait_for()
 assert 'v2' in await page.locator('.snapshot-detail-page').inner_text()
 await page.goto(base+'/admin/semantics/field-explanations?datasourceId=1&snapshotId=12&tableName=orders&columnMetaId=204')
 await page.wait_for_timeout(250)
 assert state.field['currentApprovedRevisionId']==302 and await page.get_by_role('button',name='保存草稿',exact=True).count()==0
 passed('采集新12/v2不改变发布11或旧修订；错版本字段204不进入可写旧草稿')
 state.faults[('GET','/api/admin/datasources/1/readiness')]='delay'
 async with page.expect_request(lambda r:urlparse(r.url).path=='/api/admin/datasources/1/readiness'):
  await page.goto(base+'/admin/data-sources/1')
 await page.goto(base+'/admin/data-sources/2');await page.wait_for_timeout(1000)
 assert any(r['path']=='/api/admin/datasources/1/readiness' and r.get('faultInjected')=='delay' and r.get('status')==200 for r in state.journal)
 assert '合成供应链' in await page.locator('main').inner_text() and '合成销售经营' not in await page.locator('h1').inner_text()
 assert state.readiness(2)['permissionReady'] is False and state.readiness(2)['askable'] is False and state.readiness(2)['stage']=='PERMISSION_PENDING'
 passed('切源1→2晚回包不覆盖源2标题/就绪上下文')

 c,buildNarrow=await context(browser);await login(buildNarrow,'reviewer')
 for p,url,name in [(qp,base+'/query?datasourceId=1','问数'),(page,base+'/admin/data-sources/1','接入'),(page,base+'/admin/collections?datasourceId=1','采集'),(page,base+'/admin/semantics/field-explanations?datasourceId=1&snapshotId=11&columnMetaId=204&tableName=orders','治理'),(buildNarrow,base+'/admin/semantics/knowledge?datasourceId=1&tab=index','构建'),(page,base+'/admin/assets/lineage?datasourceId=1&entityId=100','血缘')]:
  await p.set_viewport_size(dict(width=390,height=844));await p.goto(url);await p.wait_for_timeout(250)
  assert await p.locator('body').evaluate('e=>e.scrollWidth<=innerWidth+1'),name
  await p.keyboard.press('Tab');assert await p.evaluate('document.activeElement!==document.body')
  await snap(p,'narrow-'+name+'.png');passed(name+'390px文档无横溢且键盘可聚焦')
  await p.set_viewport_size(dict(width=1600,height=1050))
 # Explicit repair is a new human revision, never an attribution side effect.
 await page.goto(base+'/admin/semantics/field-explanations?datasourceId=1&snapshotId=11&columnMetaId=204&tableName=orders')
 await expect(page.get_by_role('button',name='保存草稿',exact=True)).to_be_enabled();await page.get_by_label('业务名称',exact=True).fill('订单成交金额');await page.get_by_label('字段含义',exact=True).fill('合成：订单优惠扣减后的成交金额，退款按原下单月份净额扣回，单位元。')
 await page.get_by_label('业务依据',exact=True).fill('合成订单手册v1第3节补充核验，订单101固定原标准100元')
 await page.get_by_role('button',name='保存草稿',exact=True).click();await expect(page.get_by_role('button',name='提交审核',exact=True)).to_be_enabled();await page.get_by_role('button',name='提交审核',exact=True).click()
 c,repair=await context(browser);await login(repair,'reviewer')
 await repair.goto(base+'/admin/semantics/field-explanations?datasourceId=1&snapshotId=11&columnMetaId=204&tableName=orders');await repair.get_by_role('tab',name='人工审核',exact=True).click()
 await repair.get_by_label('字段解释原始资料引用',exact=True).fill('合成订单手册v1第3节补充核验');await repair.get_by_label('字段解释审核依据',exact=True).fill('按原订单情景核对退款归属，不变更固定验收标准')
 await repair.get_by_text('已人工核对原始资料；AI 自述不作为确认依据',exact=True).click();await repair.get_by_role('button',name='审核通过',exact=True).click()
 await repair.wait_for_timeout(150);assert state.field['currentApprovedRevisionId']==303 and state.feedback[0]['followupStatus']=='QUEUED'
 await page.reload();await page.get_by_role('button',name='复查当前快照',exact=True).click();await page.get_by_text('语义复查完成',exact=False).first.wait_for()
 assert state.feedback[0]['followupStatus']=='AWAITING_VERIFICATION' and state.feedback[0]['currentRevisionId']==303
 # The original session remains locked after unknown write. In this separate
 # session the reviewer first reads its exact accepted receipt, then deliberately
 # confirms a NEW build. No refresh-based auto unlock is claimed.
 await repair.goto(base+'/admin/semantics/knowledge?datasourceId=1&tab=index');await repair.get_by_text('journey-build-2',exact=True).wait_for()
 await repair.get_by_role('button',name='明确确认 RAG 构建',exact=True).click();d=repair.get_by_role('dialog');await d.locator('.knowledge-build-snapshot input').fill('11');await d.locator('.knowledge-build-snapshot input').press('Tab');await d.get_by_role('button',name='确认构建',exact=True).click()
 await repair.get_by_text('当前活动build：journey-build-3',exact=False).wait_for()
 await expect(rp.get_by_role('button',name='明确确认 RAG 构建',exact=True)).to_be_disabled()
 assert sum(r['actor']=='reviewer' and r['method']=='POST' and r['path']=='/api/admin/knowledge-docs/rag-builds' for r in state.journal)==3
 await qp.goto(base+'/query?datasourceId=1');await qp.get_by_role('textbox',name='输入查询问题',exact=True).fill('合成：按原订单101、原筛选与净额口径复核成交金额100元');await qp.get_by_role('button',name='发送',exact=True).click();await expect(qp.locator('.embedded-result')).to_have_count(3)
 assert state.query_tasks['journey-query-4']['revisionId']==303 and state.query_tasks['journey-query-4']['buildId']=='journey-build-3'
 await page.goto(base+'/admin/governance/fields?datasourceId=1&snapshotId=11&tab=feedback');await page.get_by_role('button',name='验证原问题',exact=True).click();d=page.get_by_role('dialog')
 assert '原业务标准100元' in await d.inner_text() and '303' in await d.inner_text()
 await d.get_by_label('按原情景核对后的实际结果',exact=True).fill('不同标准，不可通过');await d.get_by_label('验收证据记录位置',exact=True).fill('合成journey-query-4/attempt4/build3/修订303');await d.locator('.el-checkbox').click();await d.get_by_role('button',name='确认人工核验',exact=True).click()
 await d.get_by_text('合成：实际结果不符合原验收标准，保持待验证',exact=True).wait_for();assert state.feedback[0]['followupStatus']=='AWAITING_VERIFICATION'
 await d.get_by_label('按原情景核对后的实际结果',exact=True).fill('原业务标准100元');await d.get_by_role('button',name='确认人工核验',exact=True).click();await d.wait_for(state='hidden')
 assert state.feedback[0]['followupStatus']=='VERIFIED' and state.feedback[0]['revisionId']==302 and state.feedback[0]['currentRevisionId']==303
 await snap(page,'18-original-scenario-human-verification.png');passed('归因后人工修订303→独立审核/同修订复查→显式build3→原场景问数；错误标准拒绝，固定原标准人工核验才结案')

 state.invalid_sources.add(11)
 await repair.goto(base+'/admin/semantics/knowledge/23?datasourceId=1&tab=source');await repair.get_by_text('合成：来源指纹已失效，需核对原来源修订',exact=False).first.wait_for()
 assert len(state.builds)==3 and state.builds[-1]['status']=='ACTIVE'
 await snap(repair,'19-source-invalid-distinct-from-old-version.png');passed('旧build合法使用与来源指纹失效明确区分：来源失效提示不自动发布或重建')
 state.invalid_sources.remove(11)
 # Logout uses the product action and server token invalidation, then the SAME
 # browser is logged into another account to prove local history isolation.
 await qp.goto(base+'/query?datasourceId=1');await qp.get_by_role('button',name='打开用户菜单',exact=True).click();await qp.get_by_role('menuitem',name='退出登录',exact=True).click();await qp.wait_for_url('**/login')
 await login(qp,'readonly');await qp.goto(base+'/query')
 await qp.get_by_text('从一份业务数据开始',exact=True).wait_for()
 assert await qp.locator('.embedded-result').count()==0 and await qp.locator('.history-row').count()==0
 assert any(t['event']=='LOGOUT' and t['actor']=='reader' for t in state.transitions)
 await snap(qp,'16-logout-new-account-isolated.png');passed('真实退出使合成token失效，同浏览器新账号无旧会话/结果/SQL')

 # Read-only URL compatibility smoke; it cannot be confused with writing every
 # tab or validating the auxiliary fixtures' backend semantics.
 c,smoke=await context(browser);await login(smoke,'system')
 cases=json.loads(Path(__file__).with_name('stateful_route_cases_v2.json').read_text())['routes']
 records=[]
 for case in cases:
  before=len(state.journal);observed=[]
  if case['url']=='/login':
   ac,target=await context(browser);await target.goto(base+'/login');await target.get_by_placeholder('请输入账号',exact=True).wait_for()
  else:
   target=smoke
   observed=await target.evaluate("""async source=>{const router=document.getElementById('app').__vue_app__.config.globalProperties.$router;await router.push('/profile');const seen=[];const stop=router.beforeResolve(to=>{if(to.redirectedFrom)seen.push({path:to.path,query:to.query,hash:to.hash,from:to.redirectedFrom.path})});await router.push(source);stop();return seen}""",case['url'])
   await target.wait_for_url(lambda u:urlparse(u).path==case['expectedPath'])
  expected_file=case['component'].removeprefix('frontend/src/')
  await target.wait_for_function("""({path,file})=>{const router=document.getElementById('app').__vue_app__.config.globalProperties.$router;const route=router.currentRoute.value;const leaf=route.matched.at(-1)?.instances.default?.$;return route.path===path&&leaf?.isMounted&&!leaf?.isUnmounted&&(!file||route.matched.some(r=>r.instances.default?.$?.type.__file?.endsWith(file)))}""",arg=dict(path=case['expectedPath'],file=expected_file))
  mounted=await target.evaluate("""()=>document.getElementById('app').__vue_app__.config.globalProperties.$router.currentRoute.value.matched.map(r=>({path:r.path,file:r.instances.default?.$?.type.__file,mounted:r.instances.default?.$?.isMounted}))""")
  actual=urlparse(target.url);assert actual.path==case['expectedPath'],(case['n'],actual.path,case['expectedPath'])
  await expect(target.locator('#app')).to_contain_text(re.compile(r'\S'))
  body=await target.locator('body').inner_text();assert body.strip() and 'Failed to fetch dynamically imported module' not in body,(case['n'],body)
  if case['type']=='redirect':
   assert observed and observed[0]['path']==case['expectedPath'],case['n']
   for key,values in __import__('urllib.parse',fromlist=['parse_qs']).parse_qs(urlparse(case['url']).query).items():
    assert str(observed[0]['query'].get(key))==values[0],(case['n'],key,observed)
   assert observed[0]['hash'].removeprefix('#')==urlparse(case['url']).fragment
  print('ROUTE',case['n'],case['url'],actual.path,flush=True)
  records.append(dict({k:v for k,v in case.items() if k!='assertion'},plannedAssertion=case['assertion'],plannedAssertionExecuted=False,executed=True,actualURL=target.url,passed=True,actualChecks=['installed router','expected path','mounted leaf/SFC']+(['redirect query/hash'] if case['type']=='redirect' else []),mountedComponents=mounted,observedRedirect=observed,bodyExcerpt=body[:350],requests=state.journal[before:]))
  (out/'route-smoke-partial.json').write_text(json.dumps(records,ensure_ascii=False,indent=2))
 assert len(records)==42
 (out/'route-smoke.json').write_text(json.dumps(dict(synthetic=True,realBackendTested=False,records=records,limits='Read-only route/legacy URL smoke. Auxiliary page GETs are marked; not all tab writes or a real backend acceptance.'),ensure_ascii=False,indent=2))
 await snap(smoke,'17-404-return.png');passed('42路由记录实际只读URL兼容浏览，5重定向保留参数/hash；非42独立页面或全写验收')
if __name__=='__main__':asyncio.run(main())
