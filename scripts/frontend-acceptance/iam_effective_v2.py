"""Synthetic API, real Chromium IAM effective permission UI; no business SQL executed."""
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
   self.calls.append(dict(path=p,method=r.request.method,query=q));return await r.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=message,data=data),ensure_ascii=False))
  if p=='/api/iam-s1/capabilities':
   codes=['security:effective:view']+([] if self.mode=='minimal' else ['security:permission:view']);return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=self.mode=='system-admin',globalFunctions=codes,datasourceCapabilities=[dict(datasourceId=2,datasourceName='管理核对源',functionCodes=codes)],queryUse=self.mode not in ['minimal','other','system-admin']))
  if p=='/api/iam-s1/query-resources/datasources':return await send(None,503,'合成：QUERY资源不可用') if self.mode=='error' else await send([] if self.mode in ['empty','system-admin'] else [dict(id=1,name='本人有业务授权源')])
  if re.fullmatch('/api/iam-s1/query-resources/datasources/1/snapshots',p):return await send([dict(id=11,datasourceId=1,status='PUBLISHED',snapshotVersion=3)])
  if re.fullmatch('/api/iam-s1/query-resources/datasources/1/snapshots/11/tables',p):return await send([dict(datasourceId=1,metadataSnapshotId=11,tableName='orders',selectable=True,columnCount=1)])
  if '/query-resources/' in p and p.endswith('/columns'):return await send([dict(columnMetaId=204,columnName='amount',columnComment='实付金额',selectable=True)])
  if p=='/api/iam-s1/subjects':return await send([dict(id=8,name='合成核对用户',subjectType='USER')])
  if p=='/api/iam-s1/datasources/2/snapshots':return await send([dict(id=11,datasourceId=2,status='PUBLISHED',snapshotVersion=3)])
  if p=='/api/iam-s1/datasources/2/snapshots/11/tables':return await send([dict(datasourceId=2,metadataSnapshotId=11,tableName='orders',selectable=True,columnCount=1)])
  if p=='/api/iam-s1/effective-permissions/preview':
   payload=r.request.post_data_json;self.actions.append(dict(path=p,payload=payload));await asyncio.sleep(.2);return await send(None,403,'合成：核对权限已撤销') if self.mode=='preview-error' else await send(dict(allowed=True,userId=payload['userId'],datasourceId=payload['datasourceId'],datasourceName='合成核对范围',activeMetadataSnapshotId=11,permissionRevision=27,calculatedAt='2026-10-29T12:00:00',tables=[dict(tableName='orders',allowed=True,allowedColumns=['amount'],reasonCode='ALLOWED',grantSources=[dict(grantId=71,subjectType='DEPARTMENT',subjectId=8,grantSource='ADMIN',explicitColumns=['amount'])],fieldProtections=[],reasons=['合成：部门授权只允许实付金额字段'])]))
  return await super().route(r)
async def main():
 checks=[];errors=[]
 def passed(n):checks.append(dict(name=n,status='passed'))
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/usr/bin/chromium')
  async def page(mode='normal',width=1600,query='?tab=effective'):
   c=await b.new_context(viewport=dict(width=width,height=1100),reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-manager');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))");f=Fixture(mode);await c.route('http://127.0.0.1:5173/api/**',f.route);p=await c.new_page();p.on('pageerror',lambda e:errors.append(str(e)));await p.goto('http://127.0.0.1:5173/admin/access/iam'+query);await p.wait_for_selector('h1');await p.evaluate("""()=>{let d=document.createElement('div');d.textContent='合成 API · 非真实联调';d.style.cssText='position:fixed;bottom:8px;right:16px;z-index:99999;background:#fff6e9;color:#725034;padding:6px;font:11px sans-serif;pointer-events:none';document.body.append(d)}""");await p.wait_for_timeout(400);return c,p,f
  async def fields(p):
   await p.locator('.scope-grid label').filter(has_text=re.compile(r'^\s*表')).locator('.el-select').click();await p.get_by_role('option',name='orders',exact=True).click();await p.locator('.scope-grid label').filter(has_text='核对字段').locator('.el-select').click();await p.get_by_role('option',name='实付金额',exact=True).click();await p.keyboard.press('Escape')
  c,p,f=await page();await fields(p);await p.get_by_role('button',name='计算实际权限',exact=True).click(click_count=2);await p.get_by_text('合成：部门授权只允许实付金额字段',exact=True).wait_for();assert len(f.actions)==1 and f.actions[0]['payload']['userId']==1 and f.actions[0]['payload']['datasourceId']==1;assert not any(x['path']=='/api/iam-s1/subjects' or x['path']=='/api/iam-s1/datasources' for x in f.calls);await p.screenshot(path=str(OUT/'11-effective-self-result.png'),full_page=True);passed('本人QUERY资源独立，精确字段双击一次及实际依据')
  await p.get_by_text('查看他人',exact=True).click();await p.wait_for_timeout(300);assert await p.get_by_text('合成：部门授权只允许实付金额字段',exact=True).count()==0;await p.locator('.scope-grid label').filter(has_text='目标用户').locator('.el-select').click();await p.get_by_role('option',name='合成核对用户 · #8',exact=True).click();await fields(p);await p.get_by_role('button',name='计算实际权限',exact=True).click();await p.get_by_text('合成：部门授权只允许实付金额字段',exact=True).wait_for();assert f.actions[-1]['payload']['userId']==8 and f.actions[-1]['payload']['datasourceId']==2;await p.screenshot(path=str(OUT/'12-effective-other-result.png'),full_page=True);passed('用途切换清旧结论，EFFECTIVE对象/同绑定管理源不混本人');await c.close()
  c,p,f=await page('minimal');await p.get_by_text('现有管理资源目录需要同源 security:permission:view；查看他人的功能仍独立，未自动补权。',exact=True).wait_for();assert not any('/snapshots' in x['path'] for x in f.calls);assert any(x['path']=='/api/iam-s1/subjects' and x['query'].get('scope')==['EFFECTIVE'] for x in f.calls);passed('目录合同缺口明确，无组织目录补权或QUERY回退');await c.close()
  for mode in ['empty','error','system-admin']:
   c,p,f=await page(mode);await p.wait_for_timeout(300);assert not f.actions;assert not any(x['path']=='/api/iam-s1/datasources' for x in f.calls);await p.screenshot(path=str(OUT/f'13-effective-{mode}.png'),full_page=True);passed('当前用途'+mode+'不借后台范围填空');await c.close()
  c,p,f=await page(query='?tab=effective&datasourceId=2');await p.get_by_text('指定数据源不在当前用途的可用范围，未选择其他源。',exact=True).wait_for();assert not any('/snapshots' in x['path'] for x in f.calls);passed('显式用途外源不回退');await c.close()
  c,p,f=await page('preview-error');await fields(p);await p.get_by_role('button',name='计算实际权限',exact=True).click();await p.get_by_text('合成：核对权限已撤销',exact=True).wait_for();assert await p.get_by_text('结论：可以查询',exact=False).count()==0;passed('服务端403不展示旧成功');await c.close()
  c,p,f=await page(width=620);await fields(p);await p.get_by_role('button',name='计算实际权限',exact=True).click();await p.get_by_text('合成：部门授权只允许实付金额字段',exact=True).wait_for();assert await p.evaluate('document.documentElement.scrollWidth<=innerWidth');await p.screenshot(path=str(OUT/'14-effective-narrow.png'),full_page=True);passed('620窄屏范围/结论换行');await c.close();await b.close()
 result=dict(synthetic=True,realBackendTested=False,checks=checks,browserErrors=errors);(OUT/'effective-browser-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));assert not errors
if __name__=='__main__':asyncio.run(main())
