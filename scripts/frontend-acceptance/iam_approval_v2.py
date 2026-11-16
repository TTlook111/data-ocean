"""Real Chromium + synthetic access request/review API. No real IAM writes."""
import asyncio,json,sys,re
from pathlib import Path
from urllib.parse import urlparse,parse_qs
sys.path.insert(0,str(Path(__file__).resolve().parent))
from iam_effective_v2 import Fixture as Parent
from playwright.async_api import async_playwright
OUT=Path('output/playwright/iam-product-v2-20261029')
class Fixture(Parent):
 async def route(self,r):
  p=urlparse(r.request.url).path;q=parse_qs(urlparse(r.request.url).query)
  async def send(data,status=200,message='ok'):
   self.calls.append(dict(path=p,method=r.request.method,query=q));return await r.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=message,data=data),ensure_ascii=False))
  row=dict(id=42,requesterId=8,requesterName='合成申请人',datasourceId=1,datasourceName='销售经营',metadataSnapshotId=11,tableName='orders',requestedColumns=['amount','status'],rowScope='ALL',rowScopeName='全部记录',requestedValidUntil='2026-11-01T12:00:00',purpose='合成：月度销售核对',status='PENDING',statusName='待审批')
  if p=='/api/iam-s1/capabilities':
   codes=['security:approval:view']+([] if self.mode=='readonly' else ['security:approval:review']);return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=False,globalFunctions=codes,datasourceCapabilities=[dict(datasourceId=1,datasourceName='销售经营',functionCodes=codes)],queryUse=self.mode!='reviewer-only'))
  if p=='/api/iam-s1/query-resources/datasources':return await send([dict(id=1,name='可申请销售源')])
  if p=='/api/iam-s1/access-requests/mine':return await send(None,503,'合成：本人申请读取失败') if self.mode=='mine-error' else await send([] if self.mode=='empty' else [{**row,'id':41,'requesterId':1,'requesterName':'本人'}])
  if p=='/api/iam-s1/access-requests/queue':return await send(None,503,'合成：待审批读取失败') if self.mode=='pending-error' and q.get('status')==['PENDING'] else await send(dict(records=[] if self.mode=='empty' else [{**row,'requesterId':1 if self.mode=='self-review' else 8} if q.get('status')==['PENDING'] else {**row,'status':'APPROVED','statusName':'已批准','approval':dict(decision='APPROVED',reviewerId=99,approvedColumns=['amount'],approvedValidUntil='2026-11-01T12:00:00',generatedGrantId=71) }],total=1,page=int(q.get('page',['1'])[0]),size=20))
  if p=='/api/iam-s1/access-requests' and r.request.method=='POST':self.actions.append(dict(path=p,payload=r.request.post_data_json));await asyncio.sleep(.2);return await send(None,409,'合成：申请范围已变化，依据保留') if self.mode=='conflict' else await send(43)
  if re.fullmatch('/api/iam-s1/access-requests/(41/withdraw|42/review)',p):self.actions.append(dict(path=p,payload=r.request.post_data_json if r.request.post_data else None));await asyncio.sleep(.2);return await send(None,409,'合成：申请已处理，审核依据保留') if self.mode=='review-conflict' else await send(dict(requestId=42,generatedGrantId=71 if r.request.post_data_json['decision']=='APPROVE' else '')) if p.endswith('/review') else await send(None)
  return await super().route(r)
async def main():
 checks=[];errors=[]
 def passed(n):checks.append(dict(name=n,status='passed'))
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/usr/bin/chromium')
  async def page(mode='normal',width=1600):
   c=await b.new_context(viewport=dict(width=width,height=1100),reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-manager');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))");f=Fixture(mode);await c.route('http://127.0.0.1:5173/api/**',f.route);p=await c.new_page();p.on('pageerror',lambda e:errors.append(str(e)));await p.goto('http://127.0.0.1:5173/admin/access/iam-approvals');await p.wait_for_selector('h1');await p.evaluate("""()=>{let d=document.createElement('div');d.textContent='合成 API · 非真实联调';d.style.cssText='position:fixed;bottom:8px;right:16px;z-index:99999;background:#fff6e9;color:#725034;padding:6px;font:11px sans-serif;pointer-events:none';document.body.append(d)}""");await p.wait_for_timeout(350);return c,p,f
  async def fields(p):
   await p.get_by_role('button',name='发起访问申请',exact=True).click();await p.locator('.request-editor label').filter(has_text=re.compile(r'^\s*申请表')).locator('.el-select').click();await p.get_by_role('option',name='orders',exact=True).click();await p.locator('.request-editor label').filter(has_text=re.compile(r'^\s*申请字段')).locator('.el-select').click();await p.get_by_role('option',name='实付金额',exact=True).click();await p.keyboard.press('Escape');await p.wait_for_timeout(200);await p.get_by_placeholder('本次需要这些字段的具体用途').fill('合成：保留本次月度核对依据')
  async def open_review(p):
   await p.get_by_role('tab',name='待审批',exact=True).click();await p.get_by_role('button',name='同意',exact=True).click();await p.get_by_placeholder('本次审批的具体判断依据').fill('合成：只批准实付金额用于核对');await p.locator('.review-editor .el-select').click();await p.get_by_role('option',name='status',exact=True).click();await p.keyboard.press('Escape');await p.wait_for_timeout(200)
  if '--baseline' in sys.argv:
   c,p,f=await page();await p.screenshot(path=str(OUT/'baseline-approval.png'),full_page=True);await c.close();await b.close();return
  c,p,f=await page();await p.get_by_text('合成：月度销售核对',exact=True).wait_for();assert not any('/query-resources/' in x['path'] for x in f.calls);await p.screenshot(path=str(OUT/'15-access-request-list.png'),full_page=True);passed('先读本人申请，不预读APPLY或后台目录');await p.get_by_role('button',name='撤回',exact=True).click();assert '申请 #41 / 申请人 #1 / 源 #1 / 快照 #11' in await p.get_by_role('dialog').inner_text();await p.get_by_role('button',name='取消',exact=True).click();assert not f.actions;passed('撤回精确对象，取消零写');await c.close()
  c,p,f=await page('conflict');await fields(p);await p.get_by_role('button',name='提交申请',exact=True).click(click_count=2);await p.get_by_text('合成：申请范围已变化，依据保留',exact=True).wait_for();assert len(f.actions)==1 and f.actions[0]['payload']['columns']==['amount'];assert await p.get_by_placeholder('本次需要这些字段的具体用途').input_value()=='合成：保留本次月度核对依据';await p.screenshot(path=str(OUT/'16-request-conflict-draft.png'),full_page=True);passed('APPLY精确字段，申请双击一次/409保稿');await c.close()
  c,p,f=await page();await open_review(p);await p.get_by_role('button',name='核对并批准',exact=True).click(click_count=2);d=p.get_by_role('dialog');assert '申请 #42 / 申请人 #8 / 源 #1 / 快照 #11' in await d.inner_text();await p.wait_for_timeout(220);await p.screenshot(path=str(OUT/'17-approval-exact-confirmation.png'),full_page=True);await p.get_by_role('button',name='确认批准并生成临时授权',exact=True).click();await p.wait_for_timeout(400);assert len(f.actions)==1 and f.actions[0]['payload']['approvedColumns']==['amount'] and f.actions[0]['payload']['approvedValidUntil'] and not f.actions[0]['payload']['approvedValidUntil'].endswith('Z');passed('审批子集/本地期限/精确确认且一次写');await p.get_by_role('tab',name='已处理',exact=True).click();await p.get_by_text('批准字段：amount',exact=False).wait_for();passed('实际批准字段与原申请字段分开显示');await c.close()
  c,p,f=await page('review-conflict');await open_review(p);await p.get_by_role('button',name='核对并批准',exact=True).click();await p.get_by_role('button',name='确认批准并生成临时授权',exact=True).click();await p.get_by_text('合成：申请已处理，审核依据保留',exact=True).wait_for();assert await p.get_by_placeholder('本次审批的具体判断依据').input_value()=='合成：只批准实付金额用于核对';await p.screenshot(path=str(OUT/'18-approval-conflict-draft.png'),full_page=True);passed('审批409保留原字段/期限/人工依据');await c.close()
  c,p,f=await page('pending-error');await p.get_by_role('tab',name='待审批',exact=True).click();await p.get_by_text('合成：待审批读取失败',exact=True).wait_for();await p.get_by_role('tab',name='已处理',exact=True).click();await p.get_by_text('批准字段：amount',exact=False).wait_for();passed('待审失败与已处理分页各自独立');await c.close()
  for mode in ['readonly','reviewer-only','self-review']:
   c,p,f=await page(mode);await p.get_by_role('tab',name='待审批',exact=True).click();await p.wait_for_timeout(250)
   if mode in ['readonly','self-review']:assert await p.get_by_role('button',name='同意',exact=True).is_disabled()
   if mode=='reviewer-only':assert not any('/query-resources/' in x['path'] for x in f.calls)
   passed('审批'+mode+'最小角色/同源/自审边界');await c.close()
  for mode in ['empty','mine-error']:
   c,p,f=await page(mode);await p.get_by_text('本次未返回本人申请。' if mode=='empty' else '合成：本人申请读取失败',exact=True).wait_for();passed('本人申请'+mode+'状态');await c.close()
  c,p,f=await page(width=620);await open_review(p);assert await p.evaluate('document.documentElement.scrollWidth<=innerWidth');await p.screenshot(path=str(OUT/'19-approval-narrow.png'),full_page=True);passed('620窄屏申请审核表单降级/表内滚动');await c.close();await b.close()
 result=dict(synthetic=True,realBackendTested=False,checks=checks,browserErrors=errors);(OUT/'approval-browser-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));assert not errors
if __name__=='__main__':asyncio.run(main())
