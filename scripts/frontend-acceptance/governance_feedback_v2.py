"""Phase7 E: real Vue/Chromium, synthetic API only."""
import asyncio,json,re,sys
from pathlib import Path
from urllib.parse import urlparse,parse_qs
sys.path.insert(0,str(Path(__file__).resolve().parent))
from governance_field_tools_v2 import Fixture as Base,OUT
from playwright.async_api import async_playwright
ROW=dict(id=81,taskId='synthetic-query-1',feedbackType='DISLIKE',reasonCode='FIELD_MEANING',comment='合成：金额结果与原业务口径不符',status='PENDING_ATTRIBUTION',rowVersion=4,revisionId=881,targets=[dict(columnMetaId=204,fieldName='orders.amount')])
FIX={**ROW,'status':'APPLIED','followupStatus':'AWAITING_VERIFICATION','currentRevisionId':901,'resolution':dict(scenario='原订单101，固定筛选条件',expectedResult='原标准100元')}
class Fixture(Base):
 async def route(self,route):
  req=route.request;u=urlparse(req.url);p=u.path;q=parse_qs(u.query)
  async def send(data,status=200,message='ok'):
   self.calls.append(dict(path=p,method=req.method,query=q));await route.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=message,data=data),ensure_ascii=False))
  if p=='/api/iam-s1/capabilities' and self.mode=='feedback-only':return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=False,globalFunctions=['governance:field:view','governance:field:manage'],datasourceCapabilities=[dict(datasourceId=1,datasourceName='销售经营',functionCodes=['governance:field:view','governance:field:manage'])],queryUse=False,viewSql=False,export=False,permissionRevision=1))
  if p=='/api/admin/governance-feedback/datasources/1':return await send(None,503,'合成：反馈读取失败') if self.mode=='list-error' else await send(dict(records=[] if self.mode=='empty' else [FIX if self.mode=='verify' else ROW],total=0 if self.mode=='empty' else 1))
  if p.endswith(('/81/review','/81/verification')):
   self.actions.append(dict(path=p,payload=req.post_data_json));await asyncio.sleep(.3);return await send(None,409,'合成：反馈或修复版本已变化，原依据保留')
  if p=='/api/feedback-reviews':return await send(dict(records=[dict(id=91,columnMetaId=204,columnName='amount',tableName='orders',userId=9,username='synthetic_user',comment='合成旧反馈，无逐行数据源字段',createdAt='2026-10-29T10:00:00')],total=1))
  if p=='/api/feedback-reviews/91/approve':self.actions.append(dict(path=p,payload=req.post_data_json));return await send(None,403,'合成：实际字段所属源无维护权')
  return await super().route(route)
async def main():
 checks=[];errors=[]
 def passed(x):checks.append(dict(name=x,status='passed'))
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/usr/bin/chromium')
  async def page(mode='normal',width=1600):
   c=await b.new_context(viewport=dict(width=width,height=1050),reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-manager');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))");f=Fixture(mode);await c.route('http://127.0.0.1:5173/api/**',f.route);p=await c.new_page();p.on('pageerror',lambda e:errors.append(str(e)));await p.goto('http://127.0.0.1:5173/admin/governance/fields?datasourceId=1&snapshotId=11&tab=feedback');await p.wait_for_selector('h1');await p.evaluate("""()=>{let d=document.createElement('div');d.textContent='合成 API · 非真实联调';d.style.cssText='position:fixed;bottom:8px;right:16px;z-index:99999;background:#fff6e9;color:#725034;padding:6px;font:11px sans-serif;pointer-events:none';document.body.append(d)}""");return c,p,f
  c,p,f=await page();await p.get_by_text(ROW['comment'],exact=True).wait_for();await p.screenshot(path=str(OUT/'29-feedback-workspace.png'),full_page=True);await p.get_by_role('button',name='分析与审核',exact=True).click();d=p.get_by_role('dialog');selects=d.locator('.el-select');await selects.nth(1).click();await p.get_by_role('option',name='orders.amount',exact=True).click();areas=d.locator('textarea');await areas.nth(0).fill('合成：实际执行字段204、修订901、资料v2第3节');await areas.nth(1).fill('原订单101，固定筛选条件');await areas.nth(2).fill('原标准100元');await d.get_by_role('button',name='确认归因',exact=True).click(click_count=2);await d.get_by_text('合成：反馈或修复版本已变化，原依据保留',exact=True).wait_for();assert len(f.actions)==1 and f.actions[0]['payload']['expectedVersion']==4;assert await areas.nth(2).input_value()=='原标准100元';await p.screenshot(path=str(OUT/'30-feedback-attribution-conflict.png'),full_page=True);passed('实际字段归因/原情景标准，409保留依据，双击一次');await c.close()
  c,p,f=await page('verify');await p.get_by_role('button',name='验证原问题',exact=True).click();d=p.get_by_role('dialog');assert '原标准100元' in await d.inner_text();await d.locator('textarea').fill('按原条件重跑，得到100元');await d.locator('input[maxlength="2000"]').fill('受保护执行attempt-synthetic-2');await d.locator('.el-checkbox').click();await d.get_by_role('button',name='确认人工核验',exact=True).click(click_count=2);await d.get_by_text('合成：反馈或修复版本已变化，原依据保留',exact=True).wait_for();assert len(f.actions)==1 and f.actions[0]['payload']['targetRevisionId']==901 and 'expectedResult' not in f.actions[0]['payload'];await p.screenshot(path=str(OUT/'31-feedback-original-scenario-verification.png'),full_page=True);passed('核验固定原标准/精确修复修订，409仍待验证');await c.close()
  c,p,f=await page('feedback-only');await p.get_by_text(ROW['comment'],exact=True).wait_for();assert not any('/api/admin/metadata/snapshots' in x['path'] for x in f.calls);passed('无metadata公共快照也可读取合法源级反馈，不补权');await c.close()
  c,p,f=await page();await p.get_by_role('button',name='查看既有字段反馈记录',exact=False).click();await p.get_by_text('合成旧反馈，无逐行数据源字段',exact=True).wait_for();await p.screenshot(path=str(OUT/'32-legacy-feedback-scope-limitation.png'),full_page=True);calls=[x for x in f.calls if x['path']=='/api/feedback-reviews'];assert calls and all('datasourceId' not in x['query'] for x in calls);await p.locator('.feedback-review-page').get_by_role('button',name='通过',exact=True).click();await p.get_by_role('button',name='确认通过',exact=True).click();await p.get_by_text('合成：实际字段所属源无维护权',exact=False).wait_for();assert len(f.actions)==1;passed('旧列表合并授权源不伪造过滤，服务端逐行403显示');await c.close()
  for mode in ['empty','list-error']:
   c,p,f=await page(mode);await p.wait_for_timeout(400);await p.screenshot(path=str(OUT/f'33-feedback-{mode}.png'),full_page=True);passed('反馈'+mode+'状态分别显示');await c.close()
  c,p,f=await page(width=620);await p.get_by_text(ROW['comment'],exact=True).wait_for();assert await p.evaluate('document.documentElement.scrollWidth<=innerWidth');await p.screenshot(path=str(OUT/'34-feedback-narrow.png'),full_page=True);passed('反馈窄屏表内滚动');await c.close();await b.close()
 result=dict(synthetic=True,realBackendTested=False,checks=checks,browserErrors=errors);(OUT/'feedback-browser-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));assert not errors
if __name__=='__main__':asyncio.run(main())
