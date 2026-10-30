"""Phase7 F: synthetic fixtures for actual local Vue/Chromium, no backend writes."""
import asyncio,json,re,sys
from pathlib import Path
from urllib.parse import urlparse
sys.path.insert(0,str(Path(__file__).resolve().parent))
from governance_feedback_v2 import Fixture as Base,OUT
from playwright.async_api import async_playwright
GROUP=dict(id=31,name='sales_terms',displayName='销售业务',description='销售经营定义与字段映射',status='PUBLISHED')
TERM=dict(id=301,glossaryId=31,name='paid_amount',displayName='实付金额',description='订单扣除优惠后的实付金额，单位元',synonyms='["订单实付","成交金额"]',relatedTerms='[]',fqn='sales_terms.paid_amount',status='DRAFT',revision=3,linkedColumnCount=1,datasourceNames=['销售经营'],updatedAt='2026-10-29T10:00:00')
COLUMN=dict(entityId=901,datasourceId=1,datasourceName='销售经营',snapshotId=11,tableId=111,columnId=204,tableName='orders',columnName='amount',columnType='DECIMAL(12,2)',description='实付金额，元',fqn='sales.orders.amount')
class Fixture(Base):
 async def route(self,route):
  req=route.request;p=urlparse(req.url).path
  async def send(data,status=200,message='ok'):
   self.calls.append(dict(path=p,method=req.method));await route.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=message,data=data),ensure_ascii=False))
  if p=='/api/admin/glossary' and req.method=='GET':return await send([GROUP,{**GROUP,'id':32,'name':'supply_terms','displayName':'供应链业务'}])
  if p=='/api/admin/glossary/field-sources':return await send([dict(id=1,name='销售经营')])
  if p=='/api/admin/glossary/field-tables':return await send([dict(id=111,tableName='orders',tableComment='销售订单')])
  if p=='/api/admin/glossary/field-columns':return await send(dict(records=[COLUMN],total=1))
  if re.fullmatch(r'/api/admin/glossary/\d+/terms',p):return await send([{**TERM,'id':302,'glossaryId':32,'displayName':'供货数量','status':'PENDING_REVIEW'}] if '/32/' in p else [TERM])
  if re.fullmatch(r'/api/admin/glossary/terms/\d+/workspace',p):
   term={**TERM,'id':302,'glossaryId':32,'displayName':'供货数量','status':'PENDING_REVIEW'} if '/302/' in p else TERM
   return await send(dict(term=term,columns=[COLUMN],reviews=[dict(id=1,revision=2,action='APPROVED',actorId=1,createdAt='2026-10-28T10:00:00',snapshotJson=json.dumps(dict(description='旧定义',columns=[COLUMN]),ensure_ascii=False),contentHash='synthetic-hash')],needsReview=False,invalidMappings=[]))
  if re.fullmatch(r'/api/admin/glossary/terms/\d+/(draft|review|submit|revert)',p) or p.endswith('/31/drafts'):
   self.actions.append(dict(path=p,method=req.method,payload=req.post_data_json));await asyncio.sleep(.3);return await send(None,409,'合成：术语修订或来源已变化，原稿保留') if self.mode=='conflict' else await send({**TERM,'id':303})
  return await super().route(route)
async def main():
 baseline='--baseline' in sys.argv;checks=[];errors=[]
 def passed(x):checks.append(dict(name=x,status='passed'))
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/usr/bin/chromium')
  async def page(mode='normal',query='glossaryId=31&termId=301',width=1600):
   c=await b.new_context(viewport=dict(width=width,height=1050),reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-manager');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))");f=Fixture(mode);await c.route('http://127.0.0.1:5173/api/**',f.route);p=await c.new_page();p.on('pageerror',lambda e:errors.append(str(e)));await p.goto('http://127.0.0.1:5173/admin/semantics/glossaries?'+query);await p.wait_for_selector('h1');await p.evaluate("""()=>{let d=document.createElement('div');d.textContent='合成 API · 非真实联调';d.style.cssText='position:fixed;bottom:8px;right:16px;z-index:99999;background:#fff6e9;color:#725034;padding:6px;font:11px sans-serif;pointer-events:none';document.body.append(d)}""");return c,p,f
  if baseline:
   c,p,f=await page();await p.locator('.term-detail').wait_for();await p.screenshot(path=str(OUT/'baseline-glossary-functional.png'),full_page=True);await c.close();await b.close();return
  c,p,f=await page();await p.locator('.term-detail').wait_for();await p.screenshot(path=str(OUT/'35-glossary-workspace.png'),full_page=True);passed('分组/定义/实际映射/审核阶段分离，既有功能保留')
  await p.get_by_role('button',name='编辑',exact=True).click();d=p.get_by_role('dialog');await d.locator('input[placeholder="例如：客户编号、有效订单"]').wait_for();assert await d.locator('textarea').input_value()==TERM['description'];await p.screenshot(path=str(OUT/'36-term-definition-mapping-editor.png'),full_page=True);passed('定义与同义词/实际字段一体编辑，术语目录接口独立授权');await d.get_by_role('button',name='取消',exact=True).click();await c.close()
  c,p,f=await page('conflict');await p.locator('.term-detail').wait_for();await p.get_by_role('button',name='编辑',exact=True).click();d=p.get_by_role('dialog');await d.locator('input[placeholder="例如：客户编号、有效订单"]').fill('合成：人工修订金额口径');await d.get_by_role('button',name='保存草稿',exact=True).click(click_count=2);await d.get_by_text('合成：术语修订或来源已变化，原稿保留',exact=True).wait_for();assert len(f.actions)==1 and f.actions[0]['payload']['revision']==3 and f.actions[0]['payload']['entityIds']==[901];assert await d.locator('input[placeholder="例如：客户编号、有效订单"]').input_value()=='合成：人工修订金额口径';await p.screenshot(path=str(OUT/'37-term-save-conflict.png'),full_page=True);passed('聚合保存精确revision/映射，双击一次且409留稿');await c.close()
  c,p,f=await page('normal','glossaryId=bad');await p.get_by_text('指定术语分组标识无效',exact=False).wait_for();assert not any(re.fullmatch(r'/api/admin/glossary/\d+/terms',x['path']) for x in f.calls);await p.screenshot(path=str(OUT/'38-glossary-invalid-scope.png'),full_page=True);passed('显式非法分组不静默选第一组');await c.close()
  c,p,f=await page('conflict','glossaryId=32&termId=302');await p.get_by_role('button',name='审核拒绝',exact=True).click();d=p.get_by_role('dialog');await d.get_by_label('术语审核依据',exact=True).fill('合成：缺少完整业务口径');await d.get_by_role('button',name='确认审核',exact=True).click(click_count=2);await d.get_by_text('合成：术语修订或来源已变化，原稿保留',exact=True).wait_for();assert await d.get_by_label('术语审核依据',exact=True).input_value()=='合成：缺少完整业务口径';assert len(f.actions)==1 and f.actions[0]['payload']['revision']==3;await p.screenshot(path=str(OUT/'39-term-review-conflict.png'),full_page=True);passed('审核精确修订，409保留拒绝依据');await c.close()
  c,p,f=await page(width=620);await p.locator('.term-detail').wait_for();assert await p.evaluate('document.documentElement.scrollWidth<=innerWidth');await p.screenshot(path=str(OUT/'40-glossary-narrow.png'),full_page=True);passed('术语窄屏单列与操作可达');await c.close();await b.close()
 result=dict(synthetic=True,realBackendTested=False,checks=checks,browserErrors=errors);(OUT/'glossary-browser-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));assert not errors
if __name__=='__main__':asyncio.run(main())
