"""Stage9 real Chromium with synthetic loopback APIs. Never use a real model/config/credential."""
import asyncio,json,sys,argparse,re
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
   self.calls.append(dict(path=p,method=method,query=parse_qs(urlparse(r.request.url).query)));await r.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=message,data=data),ensure_ascii=False))
  if p=='/api/iam-s1/capabilities':
   codes=[] if self.mode=='no-rights' else ['admin:workbench:view'] if self.mode=='workbench-only' else [x for x in CODES if not(self.mode=='readonly' and x.endswith(':manage'))]
   return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=self.mode=='system-admin',globalFunctions=codes,datasourceCapabilities=[dict(datasourceId=1,datasourceName='合成经营库',functionCodes=codes,functionNames=[])],queryUse=True,viewSql=False,export=False,permissionRevision=1))
  if p=='/api/admin/dashboard/stats':return await send(None,503,'合成：工作台统计读取失败') if self.mode=='stats-error' else await send(dict(totalDatasources=1,openIssues=2,resolvedIssues=0,recentActivities=[dict(type='PUBLISH',description='合成：经营快照已发布',time='10-29 12:00')]))
  if p=='/api/admin/datasources/simple':return await send([] if self.mode=='empty' else [dict(id=1,name='合成经营库',databaseName='demo')])
  if p=='/api/admin/datasources/readiness/batch':return await send(None,503,'合成：准备情况失败') if self.mode=='readiness-error' else await send([dict(datasourceId=1,datasourceName='合成经营库',askable=False,stage='GOVERNANCE_BLOCKED',stageLabel='待审核',progress=40,connectionReady=True,metadataReady=True,governanceReady=False,knowledgeReady=False,permissionReady=False,blockReasons=[dict(code='GOVERNANCE',message='合成：字段解释待人工审核',actionText='查看详情',ownerRole='治理人员')])])
  if p=='/api/iam-s1/access-requests/queue':return await send(dict(records=[],total=1))
  if p=='/api/admin/system/health':return await send(None,503,'合成：健康读取失败') if self.mode=='health-error' else await send(dict(overall='DEGRADED',checkTime='2026-10-29 12:00',pythonService=dict(status='UNAVAILABLE',description='合成：AI服务未连接',lastCheckTime=None,consecutiveFailures=2),mysql=dict(status='HEALTHY',description='合成数据库可用',lastCheckTime=None),redis=dict(status='UNKNOWN',description='合成：尚未确认',lastCheckTime=None)))
  if p=='/api/admin/system/sql-pools':return await send(dict(activePools=1,pools=[dict(datasourceId=1,poolSize=2,lastUsedAt=1793275200,createdAt=1793270000)])) if self.mode=='system-admin' else await send(dict(activePools=0,pools=[]))
  if p=='/api/admin/system/sql-pools/1/reset':self.actions.append(dict(path=p,payload=r.request.post_data_json));await asyncio.sleep(.2);return await send(None)
  if p=='/api/admin/alert-rules' and method=='GET':
   if not hasattr(self,'ruleEnabled'):self.ruleEnabled=True
   q=parse_qs(urlparse(r.request.url).query);return await send(dict(records=[dict(id=41 if q.get('page',['1'])==['1'] else 42,metric='ERROR_RATE',threshold=5,operator='>',notificationType='SYSTEM',enabled=self.ruleEnabled,createdAt='2026-10-29 12:00')],total=21))
  if p=='/api/admin/alert-rules' and method=='POST':
   self.actions.append(dict(path=p,payload=r.request.post_data_json));await asyncio.sleep(.2);return await send(None,409,'合成：规则冲突，人工阈值保留') if self.mode=='conflict' else await send(dict(id=43))
  if p=='/api/admin/alert-rules/41/toggle':self.actions.append(dict(path=p));self.ruleEnabled=not self.ruleEnabled;return await send(None)
  if p.startswith('/api/admin/system/ai-config'):
   if not hasattr(self,'aiConfig'):
    self.aiConfig=dict(providers=[dict(id='p',name='合成供应商',baseUrl='https://example.invalid/v1',chatModels=[dict(name='chat')],embeddingModels=[dict(name='embed',dimension=8),dict(name='embed-next',dimension=16)],status='connected')],activeChat=dict(providerId='p',model='chat',temperature='0.3',timeout='120',maxRetries='2'),activeEmbedding=dict(providerId='p',model='embed',dimension=8,collection='synthetic_active'),vectorizeStatus=dict(status='NORMAL'))
    if self.mode=='unconfigured':self.aiConfig=dict(providers=[dict(id='p',name='合成未同步供应商',baseUrl='https://example.invalid/v1')],activeChat=None,activeEmbedding=None)
    if self.mode=='pending':self.aiConfig['pendingEmbedding']=dict(providerId='p',model='embed-next',dimension=16);self.aiConfig['vectorizeStatus']=dict(status='REINDEX_REQUIRED')
   if p=='/api/admin/system/ai-config' and method=='GET':return await send(None,503,'合成：AI配置读取失败') if self.mode=='ai-error' else await send(self.aiConfig)
   body=r.request.post_data_json or {};self.actions.append(dict(path=p,method=method,payload={k:v for k,v in body.items() if k!='apiKey'},apiKeySupplied=bool(body.get('apiKey'))))
   await asyncio.sleep(.2)
   if self.mode=='conflict':return await send(None,409,'合成：供应商变更冲突，草稿保留')
   if p=='/api/admin/system/ai-config/providers' and method=='POST':self.aiConfig['providers'].append({k:v for k,v in body.items() if k!='apiKey'});return await send(dict(id=body['id']))
   if p=='/api/admin/system/ai-config/providers/p' and method=='PUT':return await send(dict(id='p'))
   if p=='/api/admin/system/ai-config' and method=='PUT':
    if body.get('chat'):self.aiConfig['activeChat']=body['chat']
    if body.get('embedding'):self.aiConfig['pendingEmbedding']=body['embedding'];self.aiConfig['vectorizeStatus']=dict(status='REINDEX_REQUIRED')
    return await send(self.aiConfig)
   return await send(dict(id='p'))
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
  elif a.page=='runtime':
   c,p,f=await page();await p.get_by_text('降级',exact=True).wait_for();await p.get_by_text('不可用',exact=True).wait_for();await p.screenshot(path=str(OUT/'03-runtime-health.png'),full_page=True);assert not any(x['path']=='/api/admin/datasources/simple' for x in f.calls);passed('服务真实降级/不可用/未确认分层，无源辅助依赖');await c.close()
   c,p,f=await page('health-error');await p.get_by_text('合成：健康读取失败',exact=True).wait_for();passed('健康读取失败独立于健康/空');await c.close()
   c,p,f=await page(path=PATHS['runtime']+'?tab=pools');await p.get_by_text('当前活跃连接池 0 个。',exact=False).wait_for();passed('SQL池真实0，按Tab懒读');await c.close()
   c,p,f=await page('conflict',path=PATHS['runtime']+'?tab=alerts');await p.get_by_role('button',name='新增规则',exact=True).click();d=p.get_by_role('dialog');await d.get_by_role('spinbutton').fill('');await d.get_by_role('spinbutton').press('Tab');await d.get_by_role('button',name='保存',exact=True).click();await p.wait_for_timeout(100);assert not f.actions;passed('清空阈值不静默保存为0');await d.get_by_role('spinbutton').fill('7');await d.get_by_role('spinbutton').press('Tab');await d.get_by_role('button',name='保存',exact=True).click(click_count=2);await d.get_by_text('合成：规则冲突，人工阈值保留',exact=True).wait_for();assert len(f.actions)==1 and f.actions[0]['payload']['threshold']==7;await p.screenshot(path=str(OUT/'04-runtime-rule-conflict.png'),full_page=True);passed('真实规则表单、双击一次/409保人工阈值');await c.close()
   c,p,f=await page(path=PATHS['runtime']+'?tab=alerts');await p.locator('.el-switch').click();await p.get_by_role('dialog').get_by_text('规则 #41',exact=False).wait_for();await p.get_by_role('button',name='取消',exact=True).click();assert not f.actions;passed('启停确认精确规则/取消零写');await p.locator('.btn-next').click();await p.wait_for_timeout(200);assert any(x['path']=='/api/admin/alert-rules' and x.get('query',{}).get('page')==['2'] and x.get('query',{}).get('pageSize')==['20'] for x in f.calls);passed('规则服务端分页可到第二页');await c.close()
   c,p,f=await page('system-admin',path=PATHS['runtime']+'?tab=pools');await p.get_by_role('button',name='重置连接池',exact=True).click(click_count=2);d=p.get_by_role('dialog');assert '数据源 #1' in await d.inner_text();await d.get_by_role('textbox').fill('合成：精确源连接恢复核验');await p.wait_for_timeout(300);await p.screenshot(path=str(OUT/'06-runtime-pool-confirmation.png'),full_page=True);await d.get_by_role('button',name='确认重置',exact=True).click();await p.wait_for_timeout(350);assert len(f.actions)==1 and f.actions[0]['path'].endswith('/1/reset') and f.actions[0]['payload']==dict(confirmed=True,reason='合成：精确源连接恢复核验');passed('sys真实池精确ID/原因确认，双击一次POST');await c.close()
   c,p,f=await page('readonly',path=PATHS['runtime']+'?tab=alerts');assert await p.get_by_role('button',name='新增规则',exact=True).is_disabled();passed('运行查看与维护分开');await c.close()
   c,p,f=await page(width=620);assert await p.evaluate('document.documentElement.scrollWidth<=innerWidth');await p.screenshot(path=str(OUT/'05-runtime-narrow.png'),full_page=True);passed('运行620窄屏');await c.close()
  elif a.page=='ai':
   c,p,f=await page();await p.locator('.provider-opener').first.wait_for();await p.locator('.provider-opener').first.focus();await p.keyboard.press('Enter');await p.get_by_role('button',name='保存参数',exact=True).wait_for();await p.screenshot(path=str(OUT/'07-ai-current.png'),full_page=True);passed('当前配置/供应商键盘展开，Chat与Embedding分流');await c.close()
   c,p,f=await page('unconfigured');await p.locator('.provider-opener').filter(has_text='合成未同步供应商').click();await p.get_by_text('尚无返回的Chat模型',exact=False).wait_for();assert await p.get_by_role('button',name='编辑供应商',exact=True).is_enabled();passed('未同步供应商仍可编辑/测试/同步，缺状态未确认');await c.close()
   c,p,f=await page();await p.get_by_role('button',name='添加供应商',exact=True).click();d=p.get_by_role('dialog',name='供应商',exact=True);await d.get_by_placeholder('dashscope',exact=True).fill('synthetic-new');await d.get_by_placeholder('通义千问').fill('合成新供应商');await d.get_by_placeholder('https://dashscope.aliyuncs.com/compatible-mode/v1').fill('https://example.invalid/v1');await d.get_by_placeholder('留空则不修改').fill('SYNTHETIC_NEW_KEY');await d.get_by_role('button',name='保存',exact=True).click(click_count=2);await p.get_by_role('button',name='确认执行',exact=True).click();await p.locator('.provider-opener').filter(has_text='合成新供应商').wait_for();assert len(f.actions)==1 and f.actions[0]['apiKeySupplied'];assert 'SYNTHETIC_NEW_KEY' not in await p.content();passed('创建精确ID/新输入密钥不回显，双击一次且无模型仍可见');await c.close()
   c,p,f=await page('conflict');await p.get_by_role('button',name='编辑供应商',exact=True).click();d=p.get_by_role('dialog',name='供应商',exact=True);await d.get_by_placeholder('通义千问').fill('人工供应商名称');await d.get_by_placeholder('留空则不修改').fill('SYNTHETIC_NEW_KEY');await d.get_by_role('button',name='保存',exact=True).click(click_count=2);await p.get_by_role('button',name='确认执行',exact=True).click();await d.get_by_text('合成：供应商变更冲突，草稿保留',exact=False).wait_for();assert len(f.actions)==1 and await d.get_by_placeholder('留空则不修改').input_value()=='' and await d.get_by_placeholder('通义千问').input_value()=='人工供应商名称';await p.wait_for_timeout(300);await p.screenshot(path=str(OUT/'08-ai-conflict-draft.png'),full_page=True);passed('409留非秘密草稿、清新输入密钥、不自动重发');await c.close()
   c,p,f=await page('pending');await p.get_by_role('button',name='保存参数',exact=True).click();await p.get_by_role('button',name='确认执行',exact=True).click();await p.get_by_text('当前Chat保存接口可能取消该配置',exact=False).wait_for();assert not f.actions;passed('pending时Chat保存零PUT，不取消待切换配置');await c.close()
   c,p,f=await page(path=PATHS['ai']+'?tab=embedding');assert await p.get_by_role('button',name='自动检测向量维度').first.is_disabled();assert not any('detect-dimension' in x['path'] for x in f.calls);await p.locator('.model-item').filter(has_text='embed-next').get_by_role('button',name='使用',exact=True).click();await p.get_by_role('button',name='确认执行',exact=True).click();await p.wait_for_timeout(350);assert f.actions[0]['payload']['embedding']['dimension']==16 and f.actions[0]['payload']['embedding']['model']=='embed-next';passed('未核验检测不调用模型，人工精确行维度16显式确认');await c.close()
   c,p,f=await page(path=PATHS['ai']+'?tab=embedding');row=p.locator('.model-item').first;await row.get_by_role('spinbutton').fill('9');await row.get_by_role('spinbutton').press('Tab');await row.get_by_role('button',name='使用',exact=True).click();await p.get_by_role('button',name='确认执行',exact=True).click();await p.wait_for_timeout(350);assert f.actions[0]['payload']['embedding']['dimension']==9 and f.actions[0]['payload']['embedding']['model']=='embed';passed('当前Embedding改维度9实际渲染可提交，未回落8');await c.close()
   c,p,f=await page();await p.get_by_role('button',name='添加供应商',exact=True).click();d=p.get_by_role('dialog',name='供应商',exact=True);await d.get_by_placeholder('dashscope',exact=True).fill('p');await d.get_by_placeholder('https://dashscope.aliyuncs.com/compatible-mode/v1').fill('https://example.invalid/new');await d.get_by_role('button',name='保存',exact=True).click();await d.get_by_text('未创建或覆盖',exact=False).wait_for();assert not f.actions;passed('添加已有ID零POST，不借upsert覆盖原供应商');await c.close()
   c,p,f=await page('readonly');assert await p.get_by_role('button',name='添加供应商',exact=True).is_disabled();assert await p.get_by_role('button',name='测试供应商连接',exact=True).is_disabled();passed('配置查看不自动带维护或模型调用');await c.close()
   c,p,f=await page();await p.get_by_role('button',name='测试供应商连接',exact=True).click();await p.get_by_role('button',name='确认执行',exact=True).click();await p.wait_for_timeout(350);await p.get_by_role('button',name='同步供应商模型列表',exact=True).click();await p.get_by_role('button',name='确认执行',exact=True).click();await p.wait_for_timeout(350);assert [x['path'] for x in f.actions]==['/api/admin/system/ai-config/providers/p/test','/api/admin/system/ai-config/providers/p/sync-models'];passed('测试/同步均确认精确供应商，实际HTTP被合成拦截');await c.close()
   c,p,f=await page('unconfigured');await p.get_by_role('button',name='删除供应商',exact=True).click();assert '精确删除供应商 p' in await p.get_by_role('dialog',name='删除供应商',exact=True).inner_text();await p.get_by_role('button',name='取消',exact=True).click();assert not f.actions;passed('删除精确供应商确认，取消零DELETE');await c.close()
   c,p,f=await page();inputs=p.locator('.model-params input');await inputs.nth(0).fill('abc');await p.get_by_role('button',name='保存参数',exact=True).click();await p.get_by_text('未提交配置',exact=False).wait_for();assert not f.actions;await inputs.nth(0).fill('0');await inputs.nth(2).fill('0');await p.get_by_role('button',name='保存参数',exact=True).click();await p.get_by_role('button',name='确认执行',exact=True).click();await p.wait_for_timeout(350);assert f.actions[0]['payload']['chat']['temperature']=='0' and f.actions[0]['payload']['chat']['maxRetries']=='0';passed('非法Chat参数零PUT，合法0温度/0重试实际提交');await c.close()
   c,p,f=await page('ai-error');await p.get_by_text('合成：AI配置读取失败',exact=True).wait_for();passed('配置失败不当空配置');await c.close()
   c,p,f=await page(width=620);assert await p.evaluate('document.documentElement.scrollWidth<=innerWidth');await p.screenshot(path=str(OUT/'09-ai-narrow.png'),full_page=True);passed('AI620窄屏');await c.close()
  await b.close()
 if not a.baseline:
  result=dict(synthetic=True,realBackendTested=False,checks=checks,browserErrors=errors);(OUT/(a.page+'-checks.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));assert not errors
if __name__=='__main__':asyncio.run(main())
