"""Real Chromium synthetic user/department API. No actual accounts or credentials touched."""
import asyncio,json,sys,re
from pathlib import Path
from urllib.parse import urlparse,parse_qs
sys.path.insert(0,str(Path(__file__).resolve().parent))
from iam_organization_v2 import Fixture as Parent
from playwright.async_api import async_playwright
OUT=Path('output/playwright/iam-product-v2-20261029')
class Fixture(Parent):
 async def route(self,r):
  p=urlparse(r.request.url).path;q=parse_qs(urlparse(r.request.url).query)
  async def send(data,status=200,message='ok'):
   self.calls.append(dict(path=p,method=r.request.method,query=q));return await r.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=message,data=data),ensure_ascii=False))
  row=dict(id=8,username='synthetic_analyst',realName='合成分析员',departmentId=8,departmentName='销售部',email='example@example.invalid',phone='13800000000',status=getattr(self,'user_status',1),createdAt='2026-10-20T12:00:00',roleIds=[],roleNames=[],roleCodes=[])
  row.update(getattr(self,'user_patch',{}))
  if self.mode=='self':row['id']=1
  if p=='/api/iam-s1/capabilities':
   codes=['organization:user:view']+([] if self.mode=='readonly' else ['organization:user:manage','organization:user:export'])+([] if self.mode=='minimal' else ['organization:department:view']);return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=False,globalFunctions=codes,datasourceCapabilities=[],queryUse=False))
  if p=='/api/admin/departments/tree':return await send([dict(id=8,deptName='销售部',deptCode='SALES',sortOrder=1,children=[dict(id=9,parentId=8,deptName='经营分析',deptCode='ANALYSIS',children=[])])])
  if p=='/api/admin/users' and r.request.method=='GET':return await send(None,503,'合成：用户列表读取失败') if self.mode=='list-error' else await send(dict(records=[] if self.mode=='empty' else [row],total=1,page=int(q.get('page',['1'])[0]),pageSize=int(q.get('pageSize',['20'])[0])))
  if p=='/api/admin/users/8' and r.request.method=='GET':return await send(row)
  if p=='/api/admin/users/8' and r.request.method=='PUT':
   payload=r.request.post_data_json;self.actions.append(dict(path=p,payload=payload));await asyncio.sleep(.2)
   if self.mode=='conflict':return await send(None,409,'合成：用户资料冲突，保留人工输入')
   self.user_patch=payload;return await send(None)
  if p=='/api/admin/users' and r.request.method=='POST':
   payload=r.request.post_data_json;self.actions.append(dict(path=p,payload={k:v for k,v in payload.items() if k!='password'}));await asyncio.sleep(.2);return await send(dict(id=9))
  if p=='/api/admin/users/8/status':self.actions.append(dict(path=p,payload=r.request.post_data_json));self.user_status=r.request.post_data_json['status'];return await send(None)
  if p=='/api/admin/users/8' and r.request.method=='DELETE':self.actions.append(dict(path=p));return await send(None)
  if p=='/api/admin/users/8/reset-password':self.actions.append(dict(path=p));await asyncio.sleep(.2);return await send({} if self.mode=='reset-malformed' else dict(tempPassword='Synthetic-Only-22'))
  if p=='/api/admin/users/import':self.actions.append(dict(path=p));await asyncio.sleep(.2);return await send(dict(success=1,failed=1,errors=['第3行：合成账号重复，未导入']))
  if p in ['/api/admin/users/export','/api/admin/users/import-template']:
   self.calls.append(dict(path=p,method=r.request.method,query=q));return await r.fulfill(content_type='text/csv',body='username,realName\nsynthetic,合成用户\n')
  return await super().route(r)
async def main():
 checks=[];errors=[]
 def passed(n):checks.append(dict(name=n,status='passed'))
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/usr/bin/chromium')
  async def page(mode='normal',width=1600):
   c=await b.new_context(viewport=dict(width=width,height=1100),reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-manager');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}));Object.defineProperty(navigator,'clipboard',{value:{writeText:async value=>window.__syntheticCopied=value}})");f=Fixture(mode);await c.route('http://127.0.0.1:5173/api/**',f.route);p=await c.new_page();p.on('pageerror',lambda e:errors.append(str(e)));await p.goto('http://127.0.0.1:5173/admin/access/iam-organization?tab=users');await p.wait_for_selector('h1');await p.evaluate("""()=>{let d=document.createElement('div');d.textContent='合成 API · 非真实联调';d.style.cssText='position:fixed;bottom:8px;right:16px;z-index:99999;background:#fff6e9;color:#725034;padding:6px;font:11px sans-serif;pointer-events:none';document.body.append(d)}""");await p.wait_for_timeout(350);return c,p,f
  async def menu(p,name):
   await p.get_by_role('button',name='更多操作',exact=True).click();await p.get_by_role('menuitem',name=name,exact=True).click()
  if '--baseline' in sys.argv:
   c,p,f=await page();await p.screenshot(path=str(OUT/'baseline-users.png'),full_page=True);await c.close();await b.close();return
  c,p,f=await page();await p.get_by_text('合成分析员',exact=True).wait_for();await p.screenshot(path=str(OUT/'26-user-workspace.png'),full_page=True);passed('账号列表/独立角色说明/当前筛选总数');await p.get_by_role('button',name='详情',exact=True).click();await p.locator('.user-detail').get_by_text('#8 · synthetic_analyst',exact=True).wait_for();await p.screenshot(path=str(OUT/'27-user-detail.png'),full_page=True);await p.locator('.el-drawer__close-btn').click();passed('独立精确GET详情，不把旧roles空数组当无角色')
  await menu(p,'删除账号');assert '用户 #8 synthetic_analyst' in await p.get_by_role('dialog').inner_text();await p.get_by_role('button',name='取消',exact=True).click();assert not f.actions;passed('删除精确账号/关联影响确认，取消零写');await c.close()
  c,p,f=await page('conflict');await p.get_by_role('button',name='编辑',exact=True).click();d=p.get_by_role('dialog');await d.get_by_label('姓名',exact=True).fill('合成新的分析员');await d.get_by_role('button',name='保存用户',exact=True).click(click_count=2);await d.get_by_text('合成：用户资料冲突，保留人工输入',exact=True).wait_for();assert await d.get_by_label('姓名',exact=True).input_value()=='合成新的分析员';assert len(f.actions)==1 and f.actions[0]['payload']['departmentId']==8 and 'roleIds' not in f.actions[0]['payload'];await p.screenshot(path=str(OUT/'28-user-conflict-draft.png'),full_page=True);passed('保存前精确重读、双击一次、409保姓名和原部门');await c.close()
  c,p,f=await page();await menu(p,'禁用账号');await p.get_by_role('button',name='确认禁用账号',exact=True).click();await p.locator('.el-table__body').get_by_text('禁用',exact=True).wait_for();assert f.actions[0]['payload']==dict(status=2);passed('账号状态精确写入后重读事实');await c.close()
  c,p,f=await page();await menu(p,'重置密码');await p.get_by_role('button',name='确认重置密码',exact=True).click();d=p.get_by_role('dialog',name='本次临时密码 · 用户 #8',exact=True);await d.get_by_text('Synthetic-Only-22',exact=True).wait_for();assert await p.evaluate('window.__syntheticCopied===undefined');await p.screenshot(path=str(OUT/'29-synthetic-temporary-password.png'),full_page=True);await d.get_by_role('button',name='复制临时密码',exact=True).click();assert await p.evaluate('window.__syntheticCopied')=='Synthetic-Only-22';await d.get_by_role('button',name='已记录并关闭',exact=True).click();await p.wait_for_timeout(250);assert await p.get_by_text('Synthetic-Only-22',exact=True).count()==0;passed('临时密码本次展示、显式复制、关闭清除（合成Clipboard）');await c.close()
  c,p,f=await page('reset-malformed');await menu(p,'重置密码');await p.get_by_role('button',name='确认重置密码',exact=True).click();await p.get_by_text('重置回执缺少临时密码，结果未知；不要直接重复重置',exact=True).wait_for();assert await p.get_by_text('Synthetic-Only-22',exact=True).count()==0;passed('残缺密码回执未知留锁，不伪造密码或重发');await c.close()
  c,p,f=await page('self');assert await p.get_by_role('link',name='修改本人密码',exact=True).get_attribute('href')=='/change-password';await p.get_by_role('button',name='更多操作',exact=True).click();assert await p.get_by_role('menuitem',name='重置密码',exact=True).count()==0;passed('本人用已有修改密码入口避免失效token吞凭据');await c.close()
  c,p,f=await page();await p.locator('input[type=file]').set_input_files(dict(name='synthetic-users.csv',mimeType='text/csv',buffer=b'username,password,realName\nsynthetic_import,SyntheticOnly22,fixture\n'));await p.locator('.import-report').get_by_text('成功 1 条；失败 1 条。',exact=False).wait_for();await p.get_by_text('第3行：合成账号重复，未导入',exact=True).wait_for();assert len(f.actions)==1;await p.screenshot(path=str(OUT/'30-user-import-partial.png'),full_page=True);passed('已有CSV导入逐项回执/部分成功不自动重导');await c.close()
  c,p,f=await page();
  async with p.expect_download() as download:await p.get_by_role('button',name='导出当前筛选',exact=True).click()
  assert (await download.value).suggested_filename=='dataocean-users.csv';passed('独立导出能力、当前筛选CSV真实浏览器下载事件');await c.close()
  c,p,f=await page('minimal');await p.get_by_text('合成分析员',exact=True).wait_for();assert not any(x['path']=='/api/admin/departments/tree' for x in f.calls);await p.get_by_role('button',name='新增用户',exact=True).click();d=p.get_by_role('dialog');await d.get_by_label('用户名',exact=True).fill('abc');await d.get_by_label('初始密码',exact=True).fill('SyntheticOnly22');await d.get_by_label('姓名',exact=True).fill('合'*50);await d.get_by_role('button',name='保存用户',exact=True).click();await d.get_by_text('用户名为4–50位字母、数字或下划线',exact=True).wait_for();assert not f.actions;await d.get_by_label('用户名',exact=True).fill('synthetic_new');await d.get_by_label('姓名',exact=True).fill('合'*51);await d.get_by_role('button',name='保存用户',exact=True).click();await d.get_by_text('姓名2–50位',exact=True).wait_for();assert not f.actions;passed('真实DTO边界：三位用户名/51字姓名零POST');await d.get_by_label('姓名',exact=True).fill('合'*50);await d.get_by_label('手机',exact=True).fill('+1 555 0123');await d.get_by_role('button',name='保存用户',exact=True).click();await p.wait_for_timeout(300);assert f.actions[0]['payload']['username']=='synthetic_new' and not f.actions[0]['payload'].get('departmentId') and 'roleIds' not in f.actions[0]['payload'];passed('无部门辅助权仍可按DTO创建未分配账号，不赋角色');await c.close()
  c,p,f=await page('readonly');assert await p.get_by_role('button',name='新增用户',exact=True).is_disabled();assert await p.get_by_role('button',name='导出当前筛选',exact=True).is_disabled();passed('用户查看/维护/导出分离');await c.close()
  for mode in ['empty','list-error']:
   c,p,f=await page(mode);await p.get_by_text('当前筛选没有返回用户。' if mode=='empty' else '合成：用户列表读取失败',exact=True).wait_for();passed('用户'+mode+'独立状态');await c.close()
  c,p,f=await page(width=620);await p.get_by_text('合成分析员',exact=True).wait_for();assert await p.evaluate('document.documentElement.scrollWidth<=innerWidth');await p.screenshot(path=str(OUT/'31-user-narrow.png'),full_page=True);passed('620窄屏筛选换行/表内滚动');await c.close();await b.close()
 result=dict(synthetic=True,realBackendTested=False,checks=checks,browserErrors=errors);(OUT/'users-browser-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));assert not errors
if __name__=='__main__':asyncio.run(main())
