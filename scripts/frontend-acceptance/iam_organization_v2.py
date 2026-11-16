"""Synthetic organization/role API in real Chromium; no actual role bindings changed."""
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
  role=dict(id=9,roleCode='METADATA_REVIEWER',roleName='合成元数据审核员',enabled=True,protectedRole=False,builtIn=False,functionCodes=['metadata:view'],functionNames=['查看元数据'],capabilitySummary='查看元数据；后台负责源单独绑定',memberCount=1)
  if p=='/api/iam-s1/capabilities':
   codes=['organization:role:view','organization:role:manage','organization:permission:view','organization:user:view'];codes=['organization:role:view'] if self.mode=='role-only' else ['organization:permission:view'] if self.mode=='catalog-only' else codes;return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=self.mode not in ['role-only','catalog-only'],globalFunctions=codes,datasourceCapabilities=[],queryUse=False))
  if p=='/api/iam-s1/roles' and r.request.method=='POST':self.actions.append(dict(path=p,payload=r.request.post_data_json));await asyncio.sleep(.2);return await send(10)
  if p=='/api/iam-s1/roles/9' and r.request.method=='PUT':self.actions.append(dict(path=p,payload=r.request.post_data_json));await asyncio.sleep(.2);return await send(None,409,'合成：角色变更冲突，保留草稿') if self.mode=='role-conflict' else await send(None)
  if p=='/api/iam-s1/roles/9' and r.request.method=='DELETE':self.actions.append(dict(path=p));return await send(None,409,'合成：仍有成员，不能删除')
  if p=='/api/iam-s1/roles' and r.request.method=='GET' and self.mode=='roles-error':return await send(None,503,'合成：角色列表读取失败')
  if p=='/api/iam-s1/roles' and r.request.method=='GET':return await send([] if self.mode=='empty' else [role])
  if p=='/api/iam-s1/templates/role-templates':return await send([dict(code='BUSINESS_ANALYST',name='业务分析人员',description='合成角色模板',functionCodes=['query:use'],functionNames=['使用问数'],capabilitySummary='可进入问数；数据另行授权',dataHint='无自动数据授权',systemAdminOnly=False)])
  if p=='/api/iam-s1/functions':return await send([dict(code='metadata:view',name='查看元数据',description='查看治理对象',domain='元数据',workspace='元数据治理',dependencies=[],systemAdminOnly=False,active=True),dict(code='query:use',name='使用问数',description='使用智能问数',domain='业务问数',workspace='智能问数',dependencies=[],systemAdminOnly=False,active=True)])
  if p=='/api/iam-s1/subjects':return await send([dict(id=88,name='合成首批外用户',subjectType='USER',subjectTypeName='用户')] if q.get('keyword')==['首批外'] else [dict(id=8,name='合成治理专员',subjectType='USER',subjectTypeName='用户')])
  if p=='/api/iam-s1/datasources' and self.mode=='source-error':return await send(None,503,'合成：源目录暂不可用')
  if p=='/api/iam-s1/datasources':return await send([dict(id=1,name='销售经营',enabled=True),dict(id=2,name='供应链',enabled=True)])
  if p=='/api/iam-s1/users/8/roles' and r.request.method=='POST':self.actions.append(dict(path=p,payload=r.request.post_data_json));return await send(89)
  if p=='/api/iam-s1/user-roles/88/datasources' and r.request.method=='PUT':
   self.actions.append(dict(path=p,payload=r.request.post_data_json));await asyncio.sleep(.2)
   if self.mode=='binding-conflict':return await send(None,409,'合成：原绑定已变化，保留负责源依据')
   self.bound_sources=list(set(getattr(self,'bound_sources',[1])+r.request.post_data_json['datasourceIds']));return await send(None)
  if p=='/api/iam-s1/user-roles/88/datasources/1' and r.request.method=='DELETE':self.actions.append(dict(path=p));self.bound_sources=[x for x in getattr(self,'bound_sources',[1]) if x!=1];return await send(None)
  if p=='/api/iam-s1/users/8/roles/9/disable':self.actions.append(dict(path=p));self.binding_enabled=False;return await send(None)
  if p=='/api/iam-s1/users/8/roles/9' and r.request.method=='DELETE':self.actions.append(dict(path=p));self.binding_removed=True;return await send(None)
  if p=='/api/iam-s1/users/8/roles' and r.request.method=='GET' and getattr(self,'binding_removed',False):return await send([])
  if p=='/api/iam-s1/users/8/roles' and r.request.method=='GET':return await send([dict(userRoleId=88,userId=8,roleId=9,roleCode=role['roleCode'],roleName=role['roleName'],roleEnabled=True,bindingEnabled=getattr(self,'binding_enabled',True),protectedRole=False,functionNames=['查看元数据'],capabilitySummary='查看元数据',responsibleDatasources=[dict(id=i,name='销售经营' if i==1 else '供应链',enabled=True) for i in getattr(self,'bound_sources',[1])],responsibleDatasourceSummary='销售经营')])
  if p=='/api/iam-s1/user-roles/88/datasources':return await send([dict(id=1,name='销售经营',enabled=True)])
  return await super().route(r)
async def main():
 checks=[];errors=[]
 def passed(n):checks.append(dict(name=n,status='passed'))
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/usr/bin/chromium')
  async def page(mode='normal',width=1600,tab='roles'):
   c=await b.new_context(viewport=dict(width=width,height=1100),reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-manager');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))");f=Fixture(mode);await c.route('http://127.0.0.1:5173/api/**',f.route);p=await c.new_page();p.on('pageerror',lambda e:errors.append(str(e)));await p.goto('http://127.0.0.1:5173/admin/access/iam-organization?tab='+tab);await p.wait_for_selector('h1');await p.evaluate("""()=>{let d=document.createElement('div');d.textContent='合成 API · 非真实联调';d.style.cssText='position:fixed;bottom:8px;right:16px;z-index:99999;background:#fff6e9;color:#725034;padding:6px;font:11px sans-serif;pointer-events:none';document.body.append(d)}""");await p.wait_for_timeout(350);return c,p,f
  async def binding(p):
   await p.get_by_role('tab',name='用户角色与负责源',exact=True).click();await p.get_by_text('逐个用户、逐个角色绑定管理',exact=True).wait_for()
   await p.locator('.actions .el-select').first.click();await p.get_by_role('option',name='合成治理专员 · #8',exact=True).click();await p.locator('.binding-block h3').filter(has_text='合成元数据审核员').wait_for()
  async def add_source(p):
   block=p.locator('.binding-block');await block.locator('.actions .el-select').click();await p.get_by_role('option',name='供应链',exact=True).click();await p.keyboard.press('Escape');await p.wait_for_timeout(200);await block.get_by_placeholder('本次负责源变更依据').fill('合成：仅本绑定新增供应链')
  if '--baseline' in sys.argv:
   c,p,f=await page();await p.screenshot(path=str(OUT/'baseline-organization-roles.png'),full_page=True);await c.close();await b.close();return
  c,p,f=await page();await p.get_by_text('合成元数据审核员',exact=True).wait_for();assert not any(x['path']=='/api/iam-s1/subjects' for x in f.calls);await p.screenshot(path=str(OUT/'20-organization-roles.png'),full_page=True);passed('角色列表先读、三权与同绑定说明、辅助目录懒读');await p.get_by_role('button',name='删除',exact=True).click();assert '角色 #9' in await p.get_by_role('dialog').inner_text();await p.get_by_role('button',name='取消',exact=True).click();assert not f.actions;passed('删除精确角色确认/取消零写');await c.close()
  c,p,f=await page('role-conflict');await p.get_by_role('button',name='编辑',exact=True).click();await p.locator('.function-row').filter(has_text='使用问数').locator('.el-checkbox__label').click();await p.get_by_placeholder('本次角色功能变更依据').fill('合成：保留角色人工依据');await p.get_by_role('button',name='保存角色',exact=True).click(click_count=2);await p.get_by_text('合成：角色变更冲突，保留草稿',exact=True).wait_for();assert len(f.actions)==1 and set(f.actions[0]['payload']['functionCodes'])=={'metadata:view','query:use'};assert await p.get_by_placeholder('本次角色功能变更依据').input_value()=='合成：保留角色人工依据';await p.screenshot(path=str(OUT/'21-role-conflict-draft.png'),full_page=True);passed('角色依赖矩阵/409保稿与重复一次');await c.close()
  c,p,f=await page();await binding(p);await p.screenshot(path=str(OUT/'22-user-role-source-bindings.png'),full_page=True);await add_source(p);await p.get_by_role('button',name='确认追加负责源',exact=True).click(click_count=2);d=p.get_by_role('dialog');assert '用户 #8 / 角色 #9 / 绑定 #88' in await d.inner_text() and '不移除其他源' in await d.inner_text();await p.get_by_role('button',name='确认变更绑定',exact=True).click();await p.wait_for_timeout(350);assert len(f.actions)==1 and f.actions[0]['path']=='/api/iam-s1/user-roles/88/datasources' and f.actions[0]['payload']['datasourceIds']==[2];assert len(f.bound_sources)==2;passed('追加仅精确userRoleId，不替换原源或跨角色拼接')
  await p.locator('.source-list>div').filter(has_text='销售经营').get_by_role('button',name='移除此负责源',exact=True).click();assert '绑定 #88' in await p.get_by_role('dialog').inner_text() and '负责源 #1' in await p.get_by_role('dialog').inner_text();await p.get_by_role('button',name='确认变更绑定',exact=True).click();await p.wait_for_timeout(350);assert f.actions[-1]['path']=='/api/iam-s1/user-roles/88/datasources/1' and f.bound_sources==[2];passed('单条源移除准确接口/对象，不增加批量授权');await c.close()
  c,p,f=await page('binding-conflict');await binding(p);await add_source(p);await p.get_by_role('button',name='确认追加负责源',exact=True).click();await p.get_by_role('button',name='确认变更绑定',exact=True).click();await p.get_by_text('合成：原绑定已变化，保留负责源依据',exact=True).wait_for();assert await p.get_by_placeholder('本次负责源变更依据').input_value()=='合成：仅本绑定新增供应链';await p.screenshot(path=str(OUT/'23-binding-conflict-draft.png'),full_page=True);passed('负责源409保留精确绑定草稿/依据');await c.close()
  c,p,f=await page('source-error');await binding(p);await p.get_by_text('合成：源目录暂不可用',exact=True).wait_for();assert await p.get_by_role('button',name='移除此负责源',exact=True).is_enabled();assert await p.get_by_role('button',name='确认追加负责源',exact=True).is_disabled();passed('源目录辅助失败不阻已返回绑定精确操作');await c.close()
  c,p,f=await page();await binding(p);await p.locator('.actions .el-select').first.get_by_role('combobox').fill('首批外');await p.get_by_role('option',name='合成首批外用户 · #88',exact=True).wait_for();assert any(x['path']=='/api/iam-s1/subjects' and x['query'].get('keyword')==['首批外'] for x in f.calls);await p.keyboard.press('Escape');passed('远程keyword检索首批外用户且保留当前精确绑定');await c.close()
  c,p,f=await page('role-only');await p.get_by_text('合成元数据审核员',exact=True).wait_for();assert await p.get_by_role('button',name='编辑',exact=True).count()==0;assert not any(x['path'] in ['/api/iam-s1/subjects','/api/iam-s1/datasources','/api/iam-s1/functions'] for x in f.calls);passed('最小role-only不读其他辅助或开放编辑');await c.close()
  c,p,f=await page('catalog-only',tab='catalog');await p.get_by_text('固定功能目录 · 只读',exact=True).wait_for();assert not any(x['path'] in ['/api/iam-s1/roles','/api/iam-s1/subjects'] for x in f.calls);await p.get_by_placeholder('筛选功能名称/编码/说明').fill('查看元数据');assert await p.get_by_text('查看元数据',exact=True).count()>=1;await p.screenshot(path=str(OUT/'24-function-catalog-readonly.png'),full_page=True);passed('最小catalog-only只读目录/筛选保真实返回数');await c.close()
  c,p,f=await page('empty');await p.get_by_text('本次未返回匹配角色。',exact=True).wait_for();passed('角色真实空态');await c.close()
  c,p,f=await page('roles-error');await p.get_by_text('合成：角色列表读取失败',exact=True).wait_for();assert await p.get_by_text('本次未返回匹配角色。',exact=True).count()==0;passed('角色读取失败不伪装空');await c.close()
  c,p,f=await page(width=620);await binding(p);assert await p.evaluate('document.documentElement.scrollWidth<=innerWidth');await p.screenshot(path=str(OUT/'25-organization-narrow.png'),full_page=True);passed('620窄屏组织与绑定明确降级');await c.close();await b.close()
 result=dict(synthetic=True,realBackendTested=False,checks=checks,browserErrors=errors);(OUT/'organization-browser-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));assert not errors
if __name__=='__main__':asyncio.run(main())
