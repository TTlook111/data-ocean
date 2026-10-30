"""Phase8 real Chromium plus synthetic loopback IAM API; no real authorization writes."""
import asyncio,json,re,sys
from pathlib import Path
from urllib.parse import urlparse,parse_qs
sys.path.insert(0,str(Path(__file__).resolve().parent))
from governance_glossary_v2 import Fixture as Base
from playwright.async_api import async_playwright
OUT=Path('output/playwright/iam-product-v2-20261029')
class Fixture(Base):
 async def route(self,route):
  req=route.request;u=urlparse(req.url);p=u.path;q=parse_qs(u.query)
  async def send(data,status=200,message='ok'):
   self.calls.append(dict(path=p,method=req.method,query=q));await route.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=message,data=data),ensure_ascii=False))
  if p=='/api/iam-s1/capabilities':
   codes=['security:permission:view']+([] if self.mode=='readonly' else ['security:permission:manage']);return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=False,globalFunctions=codes,datasourceCapabilities=[dict(datasourceId=i,datasourceName='销售经营' if i==1 else '供应链',functionCodes=codes,functionNames=[]) for i in [1,2]],queryUse=False,viewSql=False,export=False,permissionRevision=1))
  if p=='/api/iam-s1/datasources':return await send([dict(id=1,name='销售经营'),dict(id=2,name='供应链')])
  if p=='/api/iam-s1/templates/grant-templates':return await send([])
  if p=='/api/iam-s1/subjects':return await send([dict(id=8,name='销售部',subjectType='DEPARTMENT'),dict(id=8,name='合成用户',subjectType='USER')])
  if re.fullmatch(r'/api/iam-s1/datasources/\d+/snapshots',p):return await send([dict(id=11,datasourceId=int(p.split('/')[4]),snapshotVersion=3,status='PUBLISHED')])
  if re.fullmatch(r'/api/iam-s1/datasources/\d+/snapshots/11/tables',p):return await send([dict(datasourceId=1,metadataSnapshotId=11,tableName='orders',tableComment='销售订单',selectable=True,columnCount=2)])
  if p.endswith('/tables/orders/columns'):return await send([dict(columnMetaId=204,columnName='amount',columnComment='实付金额',dataType='DECIMAL(12,2)',protectionLevel='NORMAL',selectable=True),dict(columnMetaId=205,columnName='status',columnComment='订单状态',dataType='VARCHAR',selectable=True)])
  if p=='/api/iam-s1/data-grants' and req.method=='GET':return await send(None,503,'合成：授权读取失败') if self.mode=='list-error' else await send([] if self.mode=='empty' else [dict(id=71,datasourceId=int(q.get('datasourceId',['1'])[0]),datasourceName='销售经营',subjectType='DEPARTMENT',subjectTypeName='部门',subjectId=8,subjectName='销售部',resourceScope='TABLE',tableName='orders',metadataSnapshotId=11,effect='ALLOW',status='ACTIVE',columns=['amount'],rowConditions=[],summary='合成：允许销售部查询订单实付金额，全部记录，长期有效')])
  if p=='/api/iam-s1/data-grants' and req.method=='POST':self.actions.append(dict(path=p,payload=req.post_data_json));await asyncio.sleep(.3);return await send(None,409,'合成：授权范围或来源已变化，草稿保留') if self.mode=='conflict' else await send(72)
  if p=='/api/iam-s1/data-grants/71' and req.method=='DELETE':self.actions.append(dict(path=p,query=q));await asyncio.sleep(.2);return await send(None)
  return await super().route(route)
async def main():
 baseline='--baseline' in sys.argv;OUT.mkdir(parents=True,exist_ok=True);checks=[];errors=[]
 def passed(x):checks.append(dict(name=x,status='passed'))
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/usr/bin/chromium')
  async def page(mode='normal',width=1600,query=''):
   c=await b.new_context(viewport=dict(width=width,height=1050),reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-manager');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))");f=Fixture(mode);await c.route('http://127.0.0.1:5173/api/**',f.route);p=await c.new_page();p.on('pageerror',lambda e:errors.append(str(e)));await p.goto('http://127.0.0.1:5173/admin/access/iam'+query);await p.wait_for_selector('h1');await p.evaluate("""()=>{let d=document.createElement('div');d.textContent='合成 API · 非真实联调';d.style.cssText='position:fixed;bottom:8px;right:16px;z-index:99999;background:#fff6e9;color:#725034;padding:6px;font:11px sans-serif;pointer-events:none';document.body.append(d)}""");return c,p,f
  if baseline:
   c,p,f=await page();await p.wait_for_timeout(450);await p.screenshot(path=str(OUT/'baseline-data-grants.png'),full_page=True);await c.close();await b.close();return
  c,p,f=await page();await p.get_by_text('合成：允许销售部查询订单实付金额，全部记录，长期有效',exact=True).wait_for();await p.screenshot(path=str(OUT/'01-data-grants-workspace.png'),full_page=True);passed('三权模型与实际源授权列表，先读后配')
  await p.get_by_role('button',name='撤销',exact=True).click();d=p.get_by_role('dialog');assert '71' in await d.inner_text() and 'DEPARTMENT' in await d.inner_text();await p.get_by_role('button',name='取消',exact=True).click();assert not f.actions;passed('撤销明确原grant/源/对象，取消零写')
  await p.get_by_role('button',name='新增数据授权',exact=True).click();await p.locator('.grant-editor .field').filter(has_text='授权给谁').locator('.el-select').nth(1).click();await p.get_by_role('option',name='销售部',exact=True).click();await p.locator('.grant-editor .el-switch').click();await p.get_by_role('button',name='保存数据授权',exact=True).click(click_count=2);d=p.get_by_role('dialog');assert 'DEPARTMENT #8' in await d.inner_text() and '数据源 #1' in await d.inner_text() and '所有表' in await d.inner_text();await p.wait_for_timeout(250);await p.screenshot(path=str(OUT/'02-source-deny-exact-confirmation.png'),full_page=True);await p.get_by_role('button',name='确认保存禁止',exact=True).click();await p.wait_for_timeout(450);assert len(f.actions)==1 and f.actions[0]['payload']['resourceScope']=='DATASOURCE' and f.actions[0]['payload']['columns']==[];passed('源级DENY不要求字段/快照，精确确认后双击仅一写');await c.close()
  c,p,f=await page('conflict');await p.get_by_text('合成：允许销售部查询订单实付金额，全部记录，长期有效',exact=True).wait_for();await p.get_by_role('button',name='新增数据授权',exact=True).click();e=p.locator('.grant-editor');await e.locator('.field').filter(has_text='授权给谁').locator('.el-select').nth(1).click();await p.get_by_role('option',name='销售部',exact=True).click();await e.locator('.field').filter(has_text='哪些表（必选）').locator('.el-select').click();await p.get_by_role('option',name='orders',exact=False).click();await e.locator('.field').filter(has_text='哪些字段').locator('.el-select').click();await p.get_by_role('option',name='实付金额',exact=True).click();await p.keyboard.press('Escape');await e.locator('input[placeholder="例如：销售部新增订单分析需求"]').fill('合成：保留人工授权依据');await p.get_by_role('button',name='保存数据授权',exact=True).click(click_count=2);await e.get_by_text('合成：授权范围或来源已变化，草稿保留',exact=True).wait_for();assert await e.locator('input[placeholder="例如：销售部新增订单分析需求"]').input_value()=='合成：保留人工授权依据';assert len(f.actions)==1 and f.actions[0]['payload']['columns']==[dict(columnMetaId=204,columnName='amount')];await p.screenshot(path=str(OUT/'03-grant-conflict-draft.png'),full_page=True);passed('ALLOW冻结发布快照/字段，409保依据且双击一次');await c.close()
  c,p,f=await page(query='?datasourceId=bad');await p.get_by_text('指定数据源标识无效，未选择其他范围。',exact=False).wait_for();assert not any(x['path']=='/api/iam-s1/data-grants' for x in f.calls);await p.screenshot(path=str(OUT/'04-grant-invalid-source.png'),full_page=True);passed('显式非法源不回退偏好源');await c.close()
  c,p,f=await page('readonly');await p.get_by_text('合成：允许销售部查询订单实付金额，全部记录，长期有效',exact=True).wait_for();assert await p.get_by_role('button',name='新增数据授权',exact=True).is_disabled();assert await p.get_by_role('button',name='撤销',exact=True).is_disabled();assert not any(x['path']=='/api/iam-s1/subjects' for x in f.calls);passed('查看/维护分开，最小查看角色不请求对象辅助目录');await c.close()
  for mode in ['empty','list-error']:
   c,p,f=await page(mode);await p.wait_for_timeout(400);await p.screenshot(path=str(OUT/f'05-grant-{mode}.png'),full_page=True);passed('授权'+mode+'独立状态');await c.close()
  c,p,f=await page(width=620);await p.get_by_text('合成：允许销售部查询订单实付金额，全部记录，长期有效',exact=True).wait_for();assert await p.evaluate('document.documentElement.scrollWidth<=innerWidth');await p.screenshot(path=str(OUT/'06-grant-narrow.png'),full_page=True);passed('授权窄屏三权单列/表内滚动');await c.close()
  await b.close()
 result=dict(synthetic=True,realBackendTested=False,checks=checks,browserErrors=errors);(OUT/'data-grants-browser-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));assert not errors
if __name__=='__main__':asyncio.run(main())
