"""Real browser, synthetic Java API responses only; no backend or database calls.
Run with an existing loopback frontend: python query_conversation_v2.py --base-url http://127.0.0.1:5173
Requires an available Playwright Python installation and Chromium. No browser security overrides.
"""
import asyncio, json, re
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from playwright.async_api import async_playwright
OUT=Path('output/playwright/conversation-v2-20261029')
BASE='http://127.0.0.1:5173'
NOW='2026-10-29T09:30:00'
DATA=[{'category':'家居','revenue':568240},{'category':'数码','revenue':431780},{'category':'办公','revenue':286410}]
RESULT={'protocolVersion':'IAM-SIMPLE-1','taskId':'fixture-q1','status':'COMPLETED','question':'2026 年第一季度，各品类销售额分别是多少？','sqlExplanation':'按已确认的销售额口径汇总，家居类在本次结果中最高。以下为各品类对比，实际业务口径仍需结合业务核对。','data':DATA,'columns':[{'name':'category','type':'VARCHAR','comment':'品类'},{'name':'revenue','type':'DECIMAL','comment':'销售额'}],'rowCount':3,'chartConfig':{'xAxis':{'type':'category','data':['家居','数码','办公']},'yAxis':{'type':'value'},'series':[{'name':'销售额','type':'bar','data':[568240,431780,286410]}]},'canViewSql':True,'canExport':True,'usedTables':['orders','products'],'usedColumns':['products.category','orders.quantity','orders.unit_price'],'activeMetadataSnapshotId':4,'permissionRevision':12,'finalProtectionStatus':'FINAL_PROTECTED','totalTimeMs':3240,'suggestedQuestions':['只看家居类，再按月份拆分']}
CAP={'protocolVersion':'IAM-SIMPLE-1','userId':1,'systemAdmin':False,'globalFunctions':['query:use','query:sql:view','query:export'],'datasourceCapabilities':[],'queryUse':True,'viewSql':True,'export':True,'permissionRevision':12}
MSGS=[{'id':1,'role':'user','content':RESULT['question'],'createdAt':NOW},{'id':2,'role':'assistant','content':RESULT['sqlExplanation'],'taskId':'fixture-q1','metadata':json.dumps(RESULT,ensure_ascii=False),'createdAt':NOW}]
class Fixture:
 def __init__(self): self.mode='history';self.calls=[];self.status='PROCESSING';self.ask_count=0;self.permission=True
 async def route(self,route):
  req=route.request;p=urlparse(req.url).path;self.calls.append({'method':req.method,'path':p})
  data={};status=200
  if p=='/api/auth/me':data={'id':1,'username':'fixture_reader','realName':'演示用户'}
  elif p=='/api/iam-s1/capabilities':data=CAP
  elif p=='/api/iam-s1/query-resources/datasources':
   if self.mode=='error':status=503;data=None
   elif self.mode=='no-access':data=[]
   else:data=[{'id':1,'name':'销售经营','enabled':True},{'id':2,'name':'供应链','enabled':True}]
  elif p.endswith('/readiness'):data={'datasourceId':int(p.split('/')[-2]),'askable':not p.endswith('/2/readiness'),'stageLabel':'已就绪' if p.endswith('/1/readiness') else '知识待发布','blockReasons':[] if p.endswith('/1/readiness') else [{'message':'知识文档尚未发布，请联系治理人员'}]}
  elif p=='/api/iam-s1/query/conversations':data=[] if self.mode!='history' or parse_qs(urlparse(req.url).query).get('datasourceId')==['2'] else [{'id':10,'datasourceId':1,'title':'第一季度销售情况','createdAt':NOW,'updatedAt':NOW}]
  elif p.endswith('/conversations/10/messages'):data={'items':MSGS,'hasMore':False}
  elif p=='/api/iam-s1/query/ask':self.ask_count+=1;data={'taskId':'fixture-running','conversationId':10}
  elif p.endswith('/events'):return await route.fulfill(status=200,content_type='text/event-stream',body='event: progress\ndata: {"status":"PROCESSING","progressNode":"sql_generation","progressMessage":"正在生成 SQL，准备核对问题语义"}\n\n')
  elif p.endswith('/cancel'):self.status='CANCELLED';data=None
  elif p.endswith('/resume'):data=None
  elif p.endswith('/sql'):data={'sql':'SELECT category, SUM(quantity * unit_price) AS revenue FROM orders JOIN products ON orders.product_id = products.id GROUP BY category'}
  elif p.endswith('/export.csv'):return await route.fulfill(status=200,content_type='text/csv',body='category,revenue\nfixture,1\n')
  elif p.endswith('/feedback'):data={'status':'PENDING_ATTRIBUTION' if req.method=='POST' else 'NOT_SUBMITTED'}
  elif p.endswith('/tasks/fixture-q1'):
   if not self.permission:status=403;data=None
   else:data=RESULT
  elif p.endswith('/tasks/fixture-running'):data={'taskId':'fixture-running','status':self.status,'progressNode':'sql_generation','progressMessage':'正在生成 SQL，准备核对问题语义','errorMessage':'查询已停止' if self.status=='CANCELLED' else ''}
  else:status=404
  await route.fulfill(status=status,content_type='application/json',body=json.dumps({'code':status,'message':'当前账号无权读取' if status==403 else '合成服务暂不可用' if status==503 else 'ok','data':data},ensure_ascii=False))

class AcceptanceFixture(Fixture):
 async def route(self,route):
  p=urlparse(route.request.url).path
  if self.mode=='loading' and p=='/api/iam-s1/query-resources/datasources':await asyncio.sleep(.5)
  failure_path='/api/iam-s1/query/conversations' if self.mode=='list-error' else '/api/iam-s1/query/conversations/10/messages' if self.mode=='messages-error' else None
  if p==failure_path and not getattr(self,'failed_once',False):
   self.failed_once=True;self.calls.append({'method':'GET','path':p})
   return await route.fulfill(status=503,content_type='application/json',body=json.dumps({'message':'合成历史读取失败'}))
  if p.endswith('/conversations/10/messages') and self.mode in ['failed','clarification','timeout']:
   status={'failed':'FAILED','clarification':'CLARIFICATION_REQUIRED','timeout':'TIMEOUT'}[self.mode]
   result={**RESULT,'status':status,'errorMessage':'合成供应商服务暂不可用' if status=='FAILED' else '请补充时间范围' if status=='CLARIFICATION_REQUIRED' else '服务端已超时终止','sqlExplanation':'请补充时间范围' if status=='CLARIFICATION_REQUIRED' else ''}
   messages=[MSGS[0],{**MSGS[1],'metadata':json.dumps(result,ensure_ascii=False),'content':result['sqlExplanation'] or result['errorMessage']}]
   self.calls.append({'method':route.request.method,'path':p})
   return await route.fulfill(status=200,content_type='application/json',body=json.dumps({'code':200,'data':{'items':messages,'hasMore':False}}))
  if p=='/api/iam-s1/query/conversations' and self.mode in ['failed','clarification','timeout','empty-result','denied','limited','list-error','messages-error']:
   self.calls.append({'method':'GET','path':p})
   return await route.fulfill(status=200,content_type='application/json',body=json.dumps({'code':200,'data':[{'id':10,'datasourceId':1,'title':'第一季度销售情况','createdAt':NOW,'updatedAt':NOW}]}))
  if p.endswith('/tasks/fixture-q1') and self.mode in ['empty-result','denied','limited']:
   self.calls.append({'method':'GET','path':p})
   result={**RESULT,'data':[],'rowCount':0,'chartConfig':None,'sqlExplanation':'受保护执行完成，没有匹配记录。'} if self.mode=='empty-result' else {**RESULT,'canViewSql':False,'canExport':False}
   return await route.fulfill(status=403 if self.mode=='denied' else 200,content_type='application/json',body=json.dumps({'code':403 if self.mode=='denied' else 200,'message':'当前权限已变化，结果不可读取','data':None if self.mode=='denied' else result},ensure_ascii=False))
  if p.endswith('/tasks/fixture-running') and self.status=='COMPLETED':
   self.calls.append({'method':'GET','path':p})
   result={**RESULT,'taskId':'fixture-running','sqlExplanation':'本轮按月份展示家居销售额。','data':[{'month':'1月','revenue':165000},{'month':'2月','revenue':184500},{'month':'3月','revenue':218740}],'columns':[{'name':'month','type':'VARCHAR','comment':'月份'},{'name':'revenue','type':'DECIMAL','comment':'销售额'}],'chartConfig':None,'suggestedQuestions':[]}
   return await route.fulfill(status=200,content_type='application/json',body=json.dumps({'code':200,'data':result},ensure_ascii=False))
  return await super().route(route)

async def main():
 import argparse
 parser=argparse.ArgumentParser();parser.add_argument('--base-url',default=BASE);parser.add_argument('--output',default=str(OUT));parser.add_argument('--chromium',default='/usr/bin/chromium');args=parser.parse_args()
 if urlparse(args.base_url).hostname not in ['127.0.0.1','localhost','::1']:raise SystemExit('Only a verified local frontend is allowed')
 out=Path(args.output);out.mkdir(parents=True,exist_ok=True)
 checks=[];errors=[];records=[]
 def passed(name,detail=''):checks.append({'name':name,'status':'passed','detail':detail})
 async with async_playwright() as p:
  browser=await p.chromium.launch(executable_path=args.chromium,headless=True)
  async def open_page(mode='history',width=1600,height=1050):
   context=await browser.new_context(viewport={'width':width,'height':height},device_scale_factor=1.5,reduced_motion='reduce',accept_downloads=True)
   await context.add_init_script("localStorage.setItem('dataocean_token','synthetic-browser-fixture');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_reader',passwordChanged:true}));")
   fixture=AcceptanceFixture();fixture.mode=mode
   await context.route(re.compile('^'+re.escape(args.base_url.rstrip('/'))+'/api/'),fixture.route)
   page=await context.new_page();page.on('pageerror',lambda error:errors.append(str(error)))
   page.on('console',lambda message:errors.append(message.text) if message.type=='error' and '[全局错误]' in message.text else None)
   await page.goto(args.base_url+'/query')
   return context,page,fixture
  async def capture(page,name):
   # Wait for portal entry motion as well as DOM visibility before visual evidence.
   await page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
   await page.wait_for_function('''() => {
    const dialog = document.querySelector('.query-evidence-dialog');
    if (!dialog) return true;
    for (let node = dialog; node && node !== document.body; node = node.parentElement) {
     if (Number(getComputedStyle(node).opacity) < .999) return false;
     if (node.getAnimations().some(animation => animation.playState === 'running')) return false;
    }
    return true;
   }''')
   await page.evaluate("document.getElementById('fixture-label')?.remove();document.body.insertAdjacentHTML('beforeend','<div id=fixture-label style=\"position:fixed;bottom:4px;right:8px;z-index:99999;color:#676d67;font:11px system-ui;pointer-events:none\">浏览器验收 · 合成 API 响应</div>')")
   await page.screenshot(path=str(out/name),full_page=True)
  try:
   context,page,f=await open_page()
   await page.wait_for_selector('.embedded-result');await page.wait_for_timeout(350)
   assert await page.locator('.query-answer').count()==1
   assert await page.locator('.query-main > aside').count()==0
   assert await page.locator('.datasource-picker').count()==1
   passed('单列对话、回答内结果、数据源单一入口')
   assert not any(call['path'].endswith('/sql') for call in f.calls)
   assert await page.locator('.query-answer .export-button').is_visible()
   passed('无后台负责源 reader 按顶层功能显示 SQL/导出；SQL 不预取')
   await capture(page,'01-completed-mock-desktop.png')
   card=page.locator('.query-answer').first
   await card.get_by_role('button',name='SQL',exact=True).click()
   await page.wait_for_selector('.query-evidence-dialog .sql-block')
   assert 'SELECT' in await page.locator('.sql-block').inner_text()
   await page.locator('.query-evidence-dialog .close-button').click()
   await page.wait_for_selector('.query-evidence-dialog',state='hidden')
   passed('SQL 按需读取与弹窗关闭')
   await card.get_by_role('button',name='来源与依据',exact=True).click()
   await page.wait_for_selector('.trust-grid')
   assert 'orders' in await page.locator('.query-evidence-dialog').inner_text()
   await capture(page,'03-evidence-mock-desktop.png')
   await page.locator('.query-evidence-dialog .close-button').click()
   await page.wait_for_selector('.query-evidence-dialog',state='hidden')
   passed('同任务来源、快照/权限/保护摘要按需查看')
   await card.get_by_role('tab',name='数据',exact=True).click()
   assert await card.locator('.el-table').is_visible()
   passed('同回答图表/受保护数据切换')
   async with page.expect_download() as download:
    await card.get_by_role('button',name='导出 CSV',exact=True).click()
   assert (await download.value).suggested_filename.endswith('.csv')
   await card.get_by_role('button',name='评价：结果准确',exact=True).click()
   await page.wait_for_selector('.feedback-status')
   passed('CSV 与反馈绑定当前回答的 taskId')
   await card.get_by_role('tab',name='图表',exact=True).click()
   await page.get_by_role('button',name='只看家居类，再按月份拆分',exact=True).click()
   await page.get_by_role('button',name='发送',exact=True).click()
   await page.locator('textarea').press('Enter')
   await page.wait_for_selector('.message-state.processing')
   assert f.ask_count==1
   assert await page.locator('.query-answer').count()==1
   passed('重复发送仅一次 ask；新任务处理中旧图表仍属于旧回答')
   await capture(page,'02-running-mock-desktop.png')
   await page.get_by_role('button',name='停止查询',exact=True).click()
   await page.get_by_text('已停止',exact=True).wait_for()
   assert await page.locator('.query-answer').count()==1
   passed('停止得到取消状态，不新增成功结果')
   f.status='COMPLETED'
   await page.get_by_role('button',name='保留原问题重试',exact=True).click()
   await page.wait_for_selector('.query-answer[data-task-id="fixture-running"] .embedded-result')
   assert await page.locator('.query-answer').count()==2
   await page.locator('.query-answer[data-task-id="fixture-q1"] .details-button').filter(has_text='SQL').click()
   await page.wait_for_selector('.sql-block')
   assert [call for call in f.calls if call['path'].endswith('/sql')][-1]['path'].endswith('/fixture-q1/sql')
   await page.locator('.query-evidence-dialog .close-button').click()
   passed('两轮完成结果分别保留；回看旧 SQL 仍请求旧 taskId')
   await page.locator('.datasource-trigger').click()
   await page.get_by_role('option',name=re.compile('供应链')).click()
   await page.wait_for_function("location.search.includes('datasourceId=2')")
   assert await page.locator('.query-answer').count()==0
   assert await page.get_by_role('button',name='发送',exact=True).is_disabled()
   assert '知识文档尚未发布' in await page.locator('.composer-readiness').inner_text()
   passed('切源清除旧会话；未就绪源阻断发送并说明真实原因')
   await page.go_back();await page.wait_for_selector('.query-answer .embedded-result')
   await page.locator('.query-answer').first.get_by_role('button',name='评价：结果有误',exact=True).click()
   await page.get_by_text('补充反馈（可选）',exact=True).wait_for()
   await page.go_forward();await page.wait_for_function("location.search.includes('datasourceId=2')")
   await page.wait_for_selector('.el-message-box',state='hidden')
   passed('浏览器导航使卡片卸载后，只关闭其补充反馈框')
   records.append({'scenario':'main','requests':f.calls});await context.close()
   for mode,title in [('empty-result','完成空集'),('denied','当前权限拒绝'),('limited','无 SQL/导出功能'),('failed','服务失败'),('clarification','澄清'),('timeout','服务端超时')]:
    context,page,f=await open_page(mode)
    await page.wait_for_selector('.conversation-stream');await page.wait_for_timeout(450)
    if mode=='empty-result':
     await page.get_by_text('查询成功，没有匹配记录',exact=True).wait_for()
     assert await page.locator('.feedback-buttons').count()==1 and await page.locator('.export-button').count()==0
    elif mode=='denied':
     await page.locator('.answer-error').wait_for()
     assert await page.locator('.answer-summary').count()==0 and await page.locator('.embedded-result').count()==0
     assert RESULT['sqlExplanation'] not in await page.locator('body').inner_text()
    elif mode=='limited':
     await page.wait_for_selector('.embedded-result')
     assert await page.locator('.export-button').count()==0 and await page.get_by_role('button',name='SQL',exact=True).count()==0
     assert not any(call['path'].endswith('/sql') for call in f.calls)
    else:
     assert await page.locator('.query-answer').count()==0
     assert await page.get_by_role('button',name='恢复等待',exact=True).count()==0
     if mode=='clarification':assert await page.get_by_role('button',name='补充问题',exact=True).is_visible()
     else:assert await page.get_by_role('button',name='保留原问题重试',exact=True).is_visible()
    passed(title+'独立状态和操作门禁');records.append({'scenario':mode,'requests':f.calls});await context.close()
   for mode in ['list-error','messages-error']:
    context,page,f=await open_page(mode)
    await page.get_by_role('button',name='重试历史会话',exact=True).wait_for()
    await page.get_by_role('button',name='重试历史会话',exact=True).click()
    await page.wait_for_selector('.history-row')
    assert await page.locator('.history-error').count()==0
    if mode=='messages-error':await page.wait_for_selector('.embedded-result')
    passed(mode+'同源可重试恢复');await context.close()
   context,page,f=await open_page('loading')
   await page.get_by_text('正在读取数据与会话',exact=True).wait_for()
   assert await page.get_by_role('button',name='发送',exact=True).is_disabled()
   await page.get_by_text('你想从数据中了解什么？',exact=True).wait_for()
   passed('初次读取有独立加载状态且禁止发送');await context.close()
   for mode in ['new','error','no-access']:
    context,page,f=await open_page(mode)
    await page.get_by_text('数据源读取失败' if mode=='error' else '从一份业务数据开始' if mode=='no-access' else '你想从数据中了解什么？',exact=True).wait_for()
    text=await page.locator('.welcome-state').inner_text()
    assert ('数据源读取失败' in text if mode=='error' else '没有可查询的数据源' in text if mode=='no-access' else '你想从数据中了解什么' in text)
    if mode=='new':await capture(page,'04-new-conversation-mock-desktop.png')
    passed(mode+'首屏状态可区分');await context.close()
   context,page,f=await open_page('history')
   await page.wait_for_selector('.embedded-result')
   for width,height in [(390,844),(768,1024),(1024,768),(1440,900),(1600,1050),(844,390)]:
    await page.set_viewport_size({'width':width,'height':height});await page.wait_for_timeout(100)
    dimensions=await page.evaluate("({scroll:document.documentElement.scrollWidth,width:innerWidth,composer:document.querySelector('.composer-dock').getBoundingClientRect().bottom,height:innerHeight})")
    assert dimensions['scroll']<=width+1,dimensions
    assert dimensions['composer']<=height+1,dimensions
    if width==390:
     await page.get_by_role('button',name=re.compile('历史会话')).filter(has=page.locator('small')).click()
     assert await page.locator('.history-section').is_visible()
     await page.get_by_role('button',name=re.compile('历史会话')).filter(has=page.locator('small')).click()
     await capture(page,'05-mobile-mock.png')
   passed('390/768/1024/1440/1600 与横屏无整页溢出、composer 可达')
   trigger=page.locator('.datasource-trigger');await trigger.focus();await trigger.press('ArrowDown')
   await page.wait_for_selector('[role="option"]')
   await page.keyboard.press('Escape')
   assert await trigger.evaluate('(element)=>element===document.activeElement')
   passed('数据源键盘打开、Escape 关闭并恢复焦点')
   await context.close()
   assert not errors,errors
   passed('无未捕获异常或应用全局错误')
  finally:
   (out/'browser-verification.json').write_text(json.dumps({'kind':'real Chromium with synthetic API only','checks':checks,'browserErrors':errors,'requestEvidence':records},ensure_ascii=False,indent=2))
   await browser.close()
 print(json.dumps({'passed':len(checks),'browserErrors':errors,'output':str(out)},ensure_ascii=False))

if __name__=='__main__':asyncio.run(main())
