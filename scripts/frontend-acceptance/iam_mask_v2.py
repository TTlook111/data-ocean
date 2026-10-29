"""Real Chromium, synthetic IAM field-protection contract. Does not validate Java writes."""
import asyncio,json,sys,re
from pathlib import Path
from urllib.parse import urlparse,parse_qs
sys.path.insert(0,str(Path(__file__).resolve().parent))
from iam_product_v2 import Fixture as Parent
from playwright.async_api import async_playwright
OUT=Path('output/playwright/iam-product-v2-20261029')
class Fixture(Parent):
 async def route(self,r):
  p=urlparse(r.request.url).path;q=parse_qs(urlparse(r.request.url).query)
  async def send(data,status=200,message='ok'):
   self.calls.append(dict(path=p,method=r.request.method,query=q));return await r.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,data=data,message=message),ensure_ascii=False))
  if p=='/api/iam-s1/capabilities':
   codes=['security:mask:view']+([] if self.mode=='minimal' else ['security:permission:view'])+([] if self.mode in ['readonly','minimal'] else ['security:mask:manage']);return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=False,globalFunctions=codes,datasourceCapabilities=[dict(datasourceId=1,datasourceName='销售经营',functionCodes=codes)],queryUse=False))
  if p.endswith('/tables/orders/columns'):return await send([dict(columnMetaId=204,columnName='amount',columnComment='实付金额',dataType='DECIMAL(12,2)',protectionLevel='MASKED',selectable=True),dict(columnMetaId=205,columnName='status',columnComment='订单状态',dataType='VARCHAR',selectable=True)])
  if p=='/api/iam-s1/field-protections' and r.request.method=='GET':return await send(None,503,'合成：正式保护读取失败') if self.mode=='list-error' else await send([] if self.mode=='empty' else [dict(id=71,datasourceId=1,metadataSnapshotId=11,tableName='orders',columnMetaId=204,columnName='amount',protectionLevel='MASKED',maskPolicy='EMAIL',status='ACTIVE',updatedAt='2026-10-29 12:00')])
  if p=='/api/iam-s1/field-protections' and r.request.method=='POST':self.actions.append(dict(path=p,payload=r.request.post_data_json));await asyncio.sleep(.2);return await send(None,409,'合成：保护冲突，保留人工依据') if self.mode=='conflict' else await send(72)
  if p=='/api/iam-s1/field-protections/71' and r.request.method=='DELETE':self.actions.append(dict(path=p));return await send(None)
  if p=='/api/admin/catalog/mask-candidates':return await send([] if self.mode=='empty' else [dict(entityId=205,name='phone',displayName='客户手机号',fqn='sales.orders.phone',pendingMask=dict(mask_strategy='PHONE',tag_fqn='PII.手机号'))])
  if re.fullmatch('/api/admin/catalog/mask-candidates/205/(confirm|reject)',p):self.actions.append(dict(path=p,payload=r.request.post_data_json if r.request.post_data else None));await asyncio.sleep(.2);return await send(None)
  return await super().route(r)
async def main():
 checks=[];errors=[]
 def passed(n):checks.append(dict(name=n,status='passed'))
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/usr/bin/chromium')
  async def page(mode='normal',width=1600,query='?tab=protections'):
   c=await b.new_context(viewport=dict(width=width,height=1100),reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-manager');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))");f=Fixture(mode);await c.route('http://127.0.0.1:5173/api/**',f.route);p=await c.new_page();p.on('pageerror',lambda e:errors.append(str(e)));await p.goto('http://127.0.0.1:5173/admin/access/iam'+query);await p.wait_for_selector('h1');await p.evaluate("""()=>{let d=document.createElement('div');d.textContent='合成 API · 非真实联调';d.style.cssText='position:fixed;bottom:8px;right:16px;z-index:99999;background:#fff6e9;color:#725034;padding:6px;font:11px sans-serif;pointer-events:none';document.body.append(d)}""");await p.wait_for_timeout(400);return c,p,f
  async def select_table(p):
   await p.locator('.scope-panel label').filter(has_text='精确表').locator('.el-select').click();await p.get_by_role('option',name='orders · 销售订单',exact=True).click();await p.get_by_text('实付金额',exact=True).wait_for()
  c,p,f=await page();await select_table(p);await p.screenshot(path=str(OUT/'07-field-protection-workspace.png'),full_page=True);assert not any(x['path'].startswith('/api/admin/metadata') or x['path']=='/api/iam-s1/datasources' for x in f.calls);passed('有目录能力的发布范围/字段/候选独立于公共元数据')
  row=p.locator('.el-table__row').filter(has_text='实付金额');await row.locator('.el-select').first.click();await p.get_by_role('option',name='正常显示',exact=True).click();await row.get_by_placeholder('本次保护变更依据').fill('合成：人工确认改为正常显示，查询仍需授权');await row.get_by_role('button',name='保存保护',exact=True).click(click_count=2);d=p.get_by_role('dialog');assert '字段 #204' in await d.inner_text() and '快照 #11' in await d.inner_text();await p.wait_for_timeout(200);await p.screenshot(path=str(OUT/'08-protection-exact-confirmation.png'),full_page=True);await p.get_by_role('button',name='确认变更',exact=True).click();await p.wait_for_timeout(400);assert len(f.actions)==1 and f.actions[0]['payload']['columnMetaId']==204;passed('正式保护精确确认且双击一次')
  await p.get_by_role('button',name='撤销',exact=True).click();assert '保护 #71' in await p.get_by_role('dialog').inner_text();await p.get_by_role('button',name='取消',exact=True).click();assert len(f.actions)==1;passed('撤销精确正式记录，取消零写')
  cand=p.locator('.mask-candidate-panel');await cand.get_by_role('button',name='确认生效',exact=True).click(click_count=2);assert '字段实体 #205' in await p.get_by_role('dialog').inner_text();await p.get_by_role('button',name='确认生效',exact=True).last.click();await p.wait_for_timeout(400);assert len(f.actions)==2 and f.actions[1]['payload']==dict(maskStrategy='PHONE');passed('候选确认实际实体与固定策略，不授予数据权');await c.close()
  c,p,f=await page('conflict');await select_table(p);row=p.locator('.el-table__row').filter(has_text='实付金额');await row.locator('.el-select').first.click();await p.get_by_role('option',name='正常显示',exact=True).click();await row.get_by_placeholder('本次保护变更依据').fill('合成：冲突保留依据');await row.get_by_role('button',name='保存保护',exact=True).click();await p.get_by_role('button',name='确认变更',exact=True).click();await p.get_by_text('合成：保护冲突，保留人工依据',exact=True).wait_for();assert await row.get_by_placeholder('本次保护变更依据').input_value()=='合成：冲突保留依据';passed('保护409保留人工依据与策略');await p.screenshot(path=str(OUT/'09-protection-conflict-draft.png'),full_page=True);await c.close()
  c,p,f=await page('readonly');await select_table(p);assert await p.get_by_role('button',name='保存保护',exact=True).count()==0;assert await p.locator('.mask-candidate-panel').get_by_role('button',name='确认生效',exact=True).is_disabled();passed('查看/维护分离，候选只读无补权');await c.close()
  c,p,f=await page('minimal');await p.get_by_text('当前角色可读正式保护与候选，但现有字段目录需要同源 security:permission:view；未增加权限，目录维护暂不可用。',exact=True).wait_for();assert await p.get_by_text('当前源各快照的正式记录',exact=True).count()==1;assert not any('/snapshots' in x['path'] for x in f.calls);passed('最小mask角色不被缺权目录卡住源级正式记录');await c.close()
  for mode in ['empty','list-error']:
   c,p,f=await page(mode);text='本次未返回正式保护记录。' if mode=='empty' else '合成：正式保护读取失败';await p.get_by_text(text,exact=True).wait_for();passed('正式保护'+mode+'单独状态');await c.close()
  c,p,f=await page(query='?tab=protections&datasourceId=bad');await p.get_by_text('指定数据源标识无效，未选择其他范围。',exact=True).wait_for();assert not any('field-protections' in x['path'] or 'mask-candidates' in x['path'] for x in f.calls);passed('显式坏源不回退或读取候选');await c.close()
  c,p,f=await page(width=620);await select_table(p);assert await p.evaluate('document.documentElement.scrollWidth<=innerWidth');await p.screenshot(path=str(OUT/'10-protection-narrow.png'),full_page=True);passed('620窄屏范围换行/表内滚动');await c.close();await b.close()
 result=dict(synthetic=True,realBackendTested=False,checks=checks,browserErrors=errors);(OUT/'field-protection-browser-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));assert not errors
if __name__=='__main__':asyncio.run(main())
