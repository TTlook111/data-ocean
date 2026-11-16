"""Phase7 C: actual Vue/Chromium, synthetic API only."""
import asyncio,json,re,sys
from pathlib import Path
from urllib.parse import urlparse
sys.path.insert(0,str(Path(__file__).resolve().parent))
from governance_rules_status_v2 import Fixture as Base,OUT
from playwright.async_api import async_playwright
ROW=dict(id=71,rowVersion=3,kind='TABLE',knowledgeKey='TABLE:orders',title='销售订单用途与粒度',status='PENDING_REVIEW',effectiveStatus='PENDING_REVIEW',body=dict(description='一行一笔订单，金额单位元',grain='订单主键唯一标识一笔订单'),dependencies=['table:orders','column:orders.amount'],evidence='合成：订单手册v2第3节',evidenceConfirmation=dict(reference='订单手册v2第3节'),unresolvedItems=[],origin='MANUAL')
class Fixture(Base):
 async def route(self,route):
  req=route.request;p=urlparse(req.url).path
  async def send(data,status=200,message='ok'):
   self.calls.append(dict(path=p,method=req.method));await route.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=message,data=data),ensure_ascii=False))
  if re.fullmatch('/api/admin/business-knowledge/snapshots/11',p):return await send([ROW,{**ROW,'id':70,'title':'订单业务历史版本','status':'SUPERSEDED'}])
  if p.endswith('/71/source-status'):return await send(dict(sourceChanged=True,currentHash='synthetic-hash-v2',original=[dict(dependency='column:orders.amount',tableName='orders',columnName='amount',comment='订单金额')],current=[dict(dependency='column:orders.amount',tableName='orders',columnName='amount',comment='实付金额，元')]))
  if p.endswith('/71/history'):return await send([dict(id=1,action='DRAFT_CREATED',operatorId=1,createdAt='2026-10-29T12:00:00',payload=dict(note='合成：人工录入业务手册依据'))])
  if p.endswith(('/11/drafts','/11/candidates','/71/actions')):
   self.actions.append(dict(path=p,method=req.method,payload=req.post_data_json));await asyncio.sleep(.3);return await send(None,409,'合成：当前修订已变化，原依据保留') if self.mode=='conflict' else await send(ROW)
  return await super().route(route)
async def main():
 checks=[];errors=[]
 def passed(x):checks.append(dict(name=x,status='passed'))
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/usr/bin/chromium')
  async def page(mode='normal',width=1600):
   c=await b.new_context(viewport=dict(width=width,height=1050),reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-manager');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))");f=Fixture(mode);await c.route('http://127.0.0.1:5173/api/**',f.route);p=await c.new_page();p.on('pageerror',lambda e:errors.append(str(e)));await p.goto('http://127.0.0.1:5173/admin/semantics/business-knowledge?datasourceId=1&snapshotId=11');await p.wait_for_selector('h1');await p.evaluate("""()=>{let d=document.createElement('div');d.textContent='合成 API · 非真实联调';d.style.cssText='position:fixed;bottom:8px;right:16px;z-index:99999;background:#fff6e9;color:#725034;padding:6px;font:11px sans-serif;pointer-events:none';document.body.append(d)}""");return c,p,f
  c,p,f=await page();await p.get_by_text(ROW['title'],exact=True).wait_for();await p.screenshot(path=str(OUT/'16-business-workspace.png'),full_page=True);assert not await p.get_by_text('订单业务历史版本',exact=True).count();await p.get_by_text('显示历史修订',exact=False).click();await p.get_by_text('订单业务历史版本',exact=True).wait_for();passed('业务对象事实与文档/发布/构建分开，历史按需展开')
  await p.locator('.el-table__row').filter(has_text=ROW['title']).get_by_role('button',name='查看与修订',exact=True).click();d=p.get_by_role('dialog');await d.get_by_role('button',name='核对已保存修订的来源',exact=True).click();await d.get_by_text('依赖字段、审核或结构来源已经变化，请核对业务口径。',exact=True).wait_for();await p.screenshot(path=str(OUT/'17-business-source-comparison.png'),full_page=True);passed('精确修订来源前后对照及人工hash确认');await d.get_by_role('button',name='关闭',exact=True).click();await c.close()
  c,p,f=await page('conflict');await p.get_by_text(ROW['title'],exact=True).wait_for();await p.locator('.el-table__row').filter(has_text=ROW['title']).get_by_role('button',name='查看与修订',exact=True).click();d=p.get_by_role('dialog');await d.get_by_role('button',name='保存新修订',exact=True).click(click_count=2);await d.get_by_text('合成：当前修订已变化，原依据保留',exact=True).wait_for();assert len(f.actions)==1 and f.actions[0]['payload']['expectedRevisionId']==71 and f.actions[0]['payload']['expectedVersion']==3;await p.screenshot(path=str(OUT/'18-business-save-conflict.png'),full_page=True);passed('保存精确基线双击一次，409保留原修订');await d.get_by_role('button',name='关闭',exact=True).click();await p.locator('.el-table__row').filter(has_text=ROW['title']).get_by_role('button',name='通过',exact=True).click();d=p.get_by_role('dialog');await d.get_by_label('业务处理依据',exact=True).fill('合成：资料v2第3节');await d.get_by_role('button',name='确认处理',exact=True).click(click_count=2);await d.get_by_text('合成：当前修订已变化，原依据保留',exact=True).wait_for();assert await d.get_by_label('业务处理依据',exact=True).input_value()=='合成：资料v2第3节';assert len(f.actions)==2;await p.screenshot(path=str(OUT/'19-business-review-conflict.png'),full_page=True);passed('审核409保留原目标版本与人工依据，双击一次');await c.close()
  c,p,f=await page(width=620);await p.get_by_text(ROW['title'],exact=True).wait_for();assert await p.evaluate('document.documentElement.scrollWidth<=innerWidth');await p.screenshot(path=str(OUT/'20-business-narrow.png'),full_page=True);passed('窄屏动作换行与表内滚动');await c.close();await b.close()
 result=dict(synthetic=True,realBackendTested=False,checks=checks,browserErrors=errors);(OUT/'business-browser-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));assert not errors
if __name__=='__main__':asyncio.run(main())
