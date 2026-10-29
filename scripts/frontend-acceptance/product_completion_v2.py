"""Stage9 real Chromium with synthetic loopback APIs. Never use a real model/config/credential."""
import asyncio,json,sys,argparse
from pathlib import Path
from urllib.parse import urlparse,parse_qs
from iam_product_v2 import Fixture as Base
from playwright.async_api import async_playwright
OUT=Path('output/playwright/product-completion-v2-20261029')
PATHS={'workbench':'/admin/workbench','runtime':'/admin/platform/runtime','ai':'/admin/platform/ai','prompts':'/admin/platform/prompts','login':'/login','password':'/change-password','profile':'/profile','query-guide':'/guide/query','admin-guide':'/guide/admin','not-found':'/missing-fixture'}
CODES=['admin:workbench:view','datasource:view','metadata:release:view','governance:issue:view','knowledge:view','security:approval:view','system:runtime:view','system:runtime:manage','system:ai-config:view','system:ai-config:manage','prompt:view','prompt:manage','prompt:approve']
class Fixture(Base):
 async def route(self,r):
  p=urlparse(r.request.url).path;method=r.request.method
  async def send(data,status=200,message='ok'):
   self.calls.append(dict(path=p,method=method));await r.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=message,data=data),ensure_ascii=False))
  if p=='/api/iam-s1/capabilities':
   codes=[] if self.mode=='no-rights' else ['admin:workbench:view'] if self.mode=='workbench-only' else [x for x in CODES if not(self.mode=='readonly' and x.endswith(':manage'))]
   return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=False,globalFunctions=codes,datasourceCapabilities=[dict(datasourceId=1,datasourceName='合成经营库',functionCodes=codes,functionNames=[])],queryUse=True,viewSql=False,export=False,permissionRevision=1))
  if p=='/api/admin/dashboard/stats':return await send(None,503,'合成：工作台统计读取失败') if self.mode=='stats-error' else await send(dict(totalDatasources=1,openIssues=2,resolvedIssues=0,recentActivities=[dict(type='PUBLISH',description='合成：经营快照已发布',time='10-29 12:00')]))
  if p=='/api/admin/datasources/simple':return await send([] if self.mode=='empty' else [dict(id=1,name='合成经营库',databaseName='demo')])
  if p=='/api/admin/datasources/readiness/batch':return await send(None,503,'合成：准备情况失败') if self.mode=='readiness-error' else await send([dict(datasourceId=1,datasourceName='合成经营库',askable=False,stage='GOVERNANCE_BLOCKED',stageLabel='待审核',progress=40,connectionReady=True,metadataReady=True,governanceReady=False,knowledgeReady=False,permissionReady=False,blockReasons=[dict(code='GOVERNANCE',message='合成：字段解释待人工审核',actionText='查看详情',ownerRole='治理人员')])])
  if p=='/api/iam-s1/access-requests/queue':return await send(dict(records=[],total=1))
  if p=='/api/admin/system/health':return await send(None,503,'合成：健康读取失败') if self.mode=='health-error' else await send(dict(overall='DEGRADED',checkTime='2026-10-29 12:00',pythonService=dict(status='UNAVAILABLE',description='合成：AI服务未连接',lastCheckTime=None,consecutiveFailures=2),mysql=dict(status='HEALTHY',description='合成数据库可用',lastCheckTime=None),redis=dict(status='UNKNOWN',description='合成：尚未确认',lastCheckTime=None)))
  if p=='/api/admin/system/sql-pools':return await send(dict(activePools=0,pools=[]))
  if p=='/api/admin/alert-rules' and method=='GET':return await send(dict(records=[],total=0))
  if p=='/api/admin/system/ai-config':return await send(dict(providers=[],activeChat=None,activeEmbedding=None))
  if p=='/api/auth/me':return await send(dict(id=1,username='synthetic-user',realName='合成用户',email='demo@example.test',phone='',passwordChanged=True))
  return await super().route(r)
async def main():
 ap=argparse.ArgumentParser();ap.add_argument('--page',choices=PATHS,default='workbench');ap.add_argument('--baseline',action='store_true');a=ap.parse_args();OUT.mkdir(parents=True,exist_ok=True);checks=[];errors=[]
 def passed(x):checks.append(dict(name=x,status='passed'))
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/usr/bin/chromium')
  async def page(mode='normal',width=1600,path=None):
   c=await b.new_context(viewport=dict(width=width,height=1100),reduced_motion='reduce')
   if a.page!='login':await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-stage9');localStorage.setItem('dataocean_user',JSON.stringify({username:'synthetic-user',passwordChanged:true}))")
   f=Fixture(mode);await c.route('http://127.0.0.1:5173/api/**',f.route);p=await c.new_page();p.on('pageerror',lambda e:errors.append(str(e)));await p.goto('http://127.0.0.1:5173'+(path or PATHS[a.page]));await p.wait_for_timeout(450);await p.evaluate("""()=>{let d=document.createElement('div');d.textContent='合成 API · 非真实联调';d.style.cssText='position:fixed;bottom:8px;right:16px;z-index:99999;background:#fff6e9;color:#725034;padding:6px;font:11px sans-serif;pointer-events:none';document.body.append(d)}""");return c,p,f
  if a.baseline:
   c,p,f=await page();await p.screenshot(path=str(OUT/('baseline-'+a.page+'.png')),full_page=True);await c.close()
  elif a.page=='workbench':
   c,p,f=await page();await p.get_by_text('合成经营库 需要处理',exact=True).wait_for();await p.screenshot(path=str(OUT/'01-workbench.png'),full_page=True);passed('有权真实待办与准备情况/活动');await p.get_by_role('button',name='刷新',exact=True).first.focus();assert await p.evaluate("document.activeElement.tagName==='BUTTON'");passed('键盘可聚焦刷新');await c.close()
   c,p,f=await page('workbench-only');await p.get_by_text('当前没有数据源查看范围',exact=False).first.wait_for();assert not any(x['path'] in ['/api/admin/datasources/simple','/api/admin/datasources/readiness/batch','/api/iam-s1/access-requests/queue'] for x in f.calls);passed('仅工作台角色不读无权辅助/不造0');await c.close()
   for mode in ['stats-error','readiness-error','empty']:
    c,p,f=await page(mode);text=await p.locator('main').last.inner_text();assert ('读取失败' in text if mode=='stats-error' else '准备情况' in text if mode=='readiness-error' else '还没有数据源' in text);passed(mode+'独立状态');await c.close()
   c,p,f=await page(width=620);assert await p.evaluate('document.documentElement.scrollWidth<=innerWidth');await p.screenshot(path=str(OUT/'02-workbench-narrow.png'),full_page=True);passed('620窄屏单列与不溢出');await c.close()
  await b.close()
 if not a.baseline:
  result=dict(synthetic=True,realBackendTested=False,checks=checks,browserErrors=errors);(OUT/(a.page+'-checks.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));assert not errors
if __name__=='__main__':asyncio.run(main())
