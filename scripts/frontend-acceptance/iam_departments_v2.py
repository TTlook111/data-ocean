"""Synthetic department tree/member API, rendered by real Chromium; no organization mutations."""
import asyncio,json,sys,re
from pathlib import Path
from urllib.parse import urlparse,parse_qs
sys.path.insert(0,str(Path(__file__).resolve().parent))
from iam_users_v2 import Fixture as Parent
from playwright.async_api import async_playwright
OUT=Path('output/playwright/iam-product-v2-20261029')
class Fixture(Parent):
 async def route(self,r):
  p=urlparse(r.request.url).path;q=parse_qs(urlparse(r.request.url).query)
  async def send(data,status=200,message='ok'):
   self.calls.append(dict(path=p,method=r.request.method,query=q));return await r.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=message,data=data),ensure_ascii=False))
  if p=='/api/iam-s1/capabilities':
   codes=['organization:department:view']+([] if self.mode=='readonly' else ['organization:department:manage'])+([] if self.mode=='dept-only' else ['organization:user:view']);return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=False,globalFunctions=codes,datasourceCapabilities=[],queryUse=False))
  if not hasattr(self,'flat'):self.flat=[dict(id=8,deptName='销售部',deptCode='SALES',sortOrder=1),dict(id=9,parentId=8,deptName='经营分析',deptCode='ANALYSIS',sortOrder=2),dict(id=10,deptName='研发部',deptCode='RND',sortOrder=0)]
  if p=='/api/admin/departments/tree':
   if self.mode=='tree-error':return await send(None,503,'合成：部门树读取失败')
   nodes={x['id']:{**x,'children':[]} for x in self.flat};roots=[]
   for n in nodes.values():
    parent=nodes.get(n.get('parentId'))
    if parent is None:roots.append(n)
    else:parent['children'].append(n)
   return await send([] if self.mode=='empty' else roots)
  if p=='/api/admin/users':return await send(None,503,'合成：直属人数读取失败') if self.mode=='count-error' and q.get('departmentId')==['9'] else await send(dict(records=[],total=1 if q.get('departmentId')==['8'] else 0))
  if p=='/api/admin/departments' and r.request.method=='POST':
   payload=r.request.post_data_json;self.actions.append(dict(path=p,payload=payload));await asyncio.sleep(.2);self.flat.append(dict(id=11,**payload));return await send(dict(id=11))
  if p=='/api/admin/departments/9' and r.request.method=='PUT':
   payload=r.request.post_data_json;self.actions.append(dict(path=p,payload=payload));await asyncio.sleep(.2)
   if self.mode=='conflict':return await send(None,409,'合成：部门层级或资料冲突，人工草稿保留')
   self.flat=[{**x,**payload} if x['id']==9 else x for x in self.flat];return await send(None)
  if p=='/api/admin/departments/9' and r.request.method=='DELETE':self.actions.append(dict(path=p));self.flat=[x for x in self.flat if x['id']!=9];return await send(None)
  return await super().route(r)
async def main():
 checks=[];errors=[]
 def passed(n):checks.append(dict(name=n,status='passed'))
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/usr/bin/chromium')
  async def page(mode='normal',width=1600):
   c=await b.new_context(viewport=dict(width=width,height=1100),reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-manager');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))");f=Fixture(mode);await c.route('http://127.0.0.1:5173/api/**',f.route);p=await c.new_page();p.on('pageerror',lambda e:errors.append(str(e)));await p.goto('http://127.0.0.1:5173/admin/access/iam-organization?tab=departments');await p.wait_for_selector('h1');await p.evaluate("""()=>{let d=document.createElement('div');d.textContent='合成 API · 非真实联调';d.style.cssText='position:fixed;bottom:8px;right:16px;z-index:99999;background:#fff6e9;color:#725034;padding:6px;font:11px sans-serif;pointer-events:none';document.body.append(d)}""");await p.wait_for_timeout(350);return c,p,f
  async def select(p,name='经营分析'):
   await p.locator('.department-grid .tree-node').filter(has_text=name).click();await p.locator('.department-detail h3').filter(has_text=name).wait_for()
  if '--baseline' in sys.argv:
   c,p,f=await page();await p.screenshot(path=str(OUT/'baseline-departments.png'),full_page=True);await c.close();await b.close();return
  c,p,f=await page();await select(p);await p.locator('.department-detail').get_by_text('0 人',exact=True).wait_for();await p.screenshot(path=str(OUT/'32-department-workspace.png'),full_page=True);passed('启用树/精确父与直属人数，真实0不等未知');await p.get_by_placeholder('搜索名称、编码或ID').fill('ANALYSIS');assert await p.locator('.department-grid .tree-node').filter(has_text='销售部').count()==1;assert await p.locator('.department-grid .tree-node').filter(has_text='研发部').count()==0;passed('搜索保命中节点父路径，详情仍按精确ID')
  await p.get_by_role('button',name='删除部门',exact=True).click();assert '只删除部门 #9 经营分析（ANALYSIS）' in await p.get_by_role('dialog').inner_text() and '没有级联删除' in await p.get_by_role('dialog').inner_text();await p.wait_for_timeout(200);await p.screenshot(path=str(OUT/'33-department-exact-deletion.png'),full_page=True);await p.get_by_role('button',name='取消',exact=True).click();assert not f.actions;passed('精确空部门删除确认，不级联/取消零写');await c.close()
  c,p,f=await page();await select(p,'销售部');assert await p.get_by_role('button',name='删除部门',exact=True).is_disabled();passed('已知非空部门不开放删除');await c.close()
  c,p,f=await page('conflict');await select(p);await p.get_by_role('button',name='编辑部门',exact=True).click();d=p.get_by_role('dialog');await d.get_by_placeholder('明确部门名称').fill('合成：保留部门人工名称');await d.get_by_role('spinbutton').fill('0');await d.get_by_role('spinbutton').press('Tab');await d.get_by_role('button',name='保存部门',exact=True).click(click_count=2);await d.get_by_text('合成：部门层级或资料冲突，人工草稿保留',exact=True).wait_for();assert len(f.actions)==1 and f.actions[0]['payload']['sortOrder']==0 and f.actions[0]['payload']['parentId']==8;assert await d.get_by_placeholder('明确部门名称').input_value()=='合成：保留部门人工名称';await p.screenshot(path=str(OUT/'34-department-conflict-draft.png'),full_page=True);passed('精确编辑预读、排序0、双击一次与409保稿');await c.close()
  c,p,f=await page();await select(p);await p.get_by_role('button',name='编辑部门',exact=True).click();d=p.get_by_role('dialog');await d.locator('.el-select').first.click();await p.locator('.el-select-dropdown:visible .el-tree-node__content').filter(has_text='研发部').click();await p.wait_for_timeout(200);await d.get_by_role('button',name='保存部门',exact=True).click();assert '父部门从 #8 改为 #10' in await p.get_by_role('dialog',name='调整精确部门层级',exact=True).inner_text();await p.get_by_role('button',name='确认调整上级',exact=True).click();await p.wait_for_timeout(350);assert f.actions[0]['payload']['parentId']==10;await p.locator('.department-detail').get_by_text('研发部 · #10',exact=True).wait_for();passed('层级变更精确确认含下级授权影响，成功按树重读');await c.close()
  c,p,f=await page('dept-only');await select(p);assert not any(x['path']=='/api/admin/users' for x in f.calls);await p.locator('.department-detail').get_by_text('未读取或不可用',exact=True).wait_for();assert await p.get_by_role('button',name='删除部门',exact=True).is_enabled();passed('最小部门角色不读user辅助，未知人数不阻合法核心');await c.close()
  c,p,f=await page('count-error');await select(p);await p.get_by_text('合成：直属人数读取失败',exact=True).wait_for();assert await p.locator('.department-detail').get_by_text('0 人',exact=True).count()==0;passed('直属人数辅助失败不伪造0或卡树');await c.close()
  c,p,f=await page('readonly');await select(p);assert await p.get_by_role('button',name='编辑部门',exact=True).is_disabled();assert await p.get_by_role('button',name='新增顶层部门',exact=True).is_disabled();passed('部门查看/维护分离');await c.close()
  c,p,f=await page();await p.get_by_role('button',name='新增顶层部门',exact=True).click();d=p.get_by_role('dialog');await d.get_by_placeholder('明确部门名称').fill('合成新部门');await d.get_by_placeholder('唯一部门编码').fill('SYNTHETIC');await d.get_by_role('button',name='保存部门',exact=True).click();await p.wait_for_timeout(350);assert f.actions[0]['payload']['sortOrder']==0 and not f.actions[0]['payload'].get('parentId');await p.locator('.department-grid .tree-node').filter(has_text='合成新部门').wait_for();passed('创建顶层明确排序0/正ID回执后重读，不新增授权');await c.close()
  for mode in ['empty','tree-error']:
   c,p,f=await page(mode);await p.get_by_text('本次未返回启用部门。' if mode=='empty' else '合成：部门树读取失败',exact=True).wait_for();passed('部门'+mode+'独立状态');await c.close()
  c,p,f=await page(width=620);await select(p);assert await p.evaluate('document.documentElement.scrollWidth<=innerWidth');await p.screenshot(path=str(OUT/'35-department-narrow.png'),full_page=True);passed('620窄屏树与详情单列降级');await c.close();await b.close()
 result=dict(synthetic=True,realBackendTested=False,checks=checks,browserErrors=errors);(OUT/'departments-browser-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));assert not errors
if __name__=='__main__':asyncio.run(main())
