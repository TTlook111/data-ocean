"""Real Chromium, loopback Vue, synthetic API only. Phase7 B."""
import asyncio,json,re,sys
from pathlib import Path
from urllib.parse import urlparse,parse_qs
sys.path.insert(0,str(Path(__file__).resolve().parent))
from governance_completion_v2 import Fixture as Base,RULE,OUT
from playwright.async_api import async_playwright
class Fixture(Base):
 async def route(self,route):
  req=route.request;p=urlparse(req.url).path
  async def send(data,status=200,message='ok'):
   self.calls.append(dict(path=p,method=req.method));await route.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=message,data=data),ensure_ascii=False))
  if p=='/api/admin/quality-rules/templates':return await send([dict(code=RULE['templateCode'],name='字段注释缺失',dimension='COMPLETENESS',targetType='COLUMN',checkType='SCHEMA',description='合成：检查原始说明',allowedScopes=['GLOBAL','DATASOURCE','TABLE','COLUMN'],parameters=[])])
  if p=='/api/admin/quality-rules' and req.method=='POST':
   self.actions.append(dict(path=p,method=req.method,payload=req.post_data_json));await asyncio.sleep(.25);return await send(None,409,'合成：配置已经变化，请保留草稿重新核对') if self.mode=='conflict' else await send({**RULE,'id':73})
  if re.fullmatch('/api/admin/quality-rules/71(/history|/revisions)?',p):return await send([] if p.endswith(('/history','/revisions')) else RULE)
  if p.endswith('/batch-governance-status'):
   self.actions.append(dict(path=p,method=req.method,payload=req.post_data_json));await asyncio.sleep(.3);return await send(dict(updated=0,excluded=2))
  return await super().route(route)
async def main():
 OUT.mkdir(parents=True,exist_ok=True);checks=[];errors=[]
 def passed(name):checks.append(dict(name=name,status='passed'))
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/usr/bin/chromium')
  async def page(path,mode='normal',width=1600):
   c=await b.new_context(viewport=dict(width=width,height=1050),reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-manager');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))");f=Fixture(mode);await c.route('http://127.0.0.1:5173/api/**',f.route);p=await c.new_page();p.on('pageerror',lambda e:errors.append(str(e)));await p.goto('http://127.0.0.1:5173'+path);await p.wait_for_selector('h1');await p.evaluate("""()=>{let d=document.createElement('div');d.textContent='合成 API · 设计验收 · 非真实联调';d.style.cssText='position:fixed;bottom:8px;right:16px;z-index:99999;background:#fff6e9;color:#725034;padding:6px;font:11px sans-serif;pointer-events:none';document.body.append(d)}""");return c,p,f
  rules='/admin/governance/rules?datasourceId=1&snapshotId=11';status='/admin/governance/status?datasourceId=1&snapshotId=11'
  c,p,f=await page(rules);await p.get_by_role('button',name=RULE['ruleName'],exact=True).wait_for();await p.screenshot(path=str(OUT/'10-rules-workspace.png'),full_page=True);passed('来源规则配置/只读规划/实际执行职责明确')
  await p.get_by_role('button',name=RULE['ruleName'],exact=True).click();await p.get_by_role('dialog').wait_for();await p.screenshot(path=str(OUT/'11-rule-history-drawer.png'),full_page=True);passed('详情历史保留且按需展开');await p.keyboard.press('Escape');await c.close()
  c,p,f=await page(rules,'conflict');await p.get_by_role('button',name=RULE['ruleName'],exact=True).wait_for();await p.get_by_role('button',name='新增规则',exact=True).click();d=p.get_by_role('dialog');await d.get_by_role('button',name='浏览检查项用途和示例',exact=True).click();await p.locator('.quality-check-catalog__item').first.click();await p.get_by_role('button',name='使用此检查项',exact=True).first.click();await d.locator('input[maxlength="200"]').fill('合成：保留人工配置');await d.get_by_role('button',name='保存规则',exact=True).click(click_count=2);await d.get_by_text('合成：配置已经变化，请保留草稿重新核对',exact=True).wait_for();assert await d.locator('input[maxlength="200"]').input_value()=='合成：保留人工配置';assert len([x for x in f.actions if x['path']=='/api/admin/quality-rules'])==1;await p.screenshot(path=str(OUT/'12-rule-conflict-draft.png'),full_page=True);passed('配置保存双击一次，409保留人工草稿');await c.close()
  c,p,f=await page(rules,'minimal');await p.get_by_role('button',name=RULE['ruleName'],exact=True).wait_for();assert not any('/quality-rules/summary' in x['path'] for x in f.calls);passed('无metadata辅助权仍可读取源级规则配置');await c.close()
  c,p,f=await page(status);await p.get_by_role('button',name='orders',exact=False).first.click();await p.get_by_role('button',name='全部设为正常可用',exact=True).wait_for();await p.screenshot(path=str(OUT/'13-status-workspace.png'),full_page=True);passed('公共快照下精确表字段与可用性说明')
  await p.get_by_role('button',name='全部设为正常可用',exact=True).click(click_count=2);await p.locator('.status-receipt').wait_for();assert '更新 0' in await p.locator('.status-receipt').inner_text();assert '排除 2' in await p.locator('.status-receipt').inner_text();assert len([x for x in f.actions if x['path'].endswith('/batch-governance-status')])==1;await p.screenshot(path=str(OUT/'14-status-batch-receipt.png'),full_page=True);passed('批量真实0/排除2与重复一次写');await p.get_by_role('button',name='全部废弃',exact=True).click();await p.get_by_role('dialog').get_by_text('确认批量高影响状态',exact=True).wait_for();await p.get_by_role('button',name='取消',exact=True).click();assert len(f.actions)==1;passed('批量废弃需确认，取消零写');await c.close()
  c,p,f=await page(status,'minimal');await p.get_by_text('没有“查看资产结构”能力，无法读取表与字段。',exact=True).wait_for();assert not any(re.search(r'/snapshots/\d+/tables',x['path']) for x in f.calls);passed('无metadata权状态目录零读取');await c.close()
  for name,path in [('rules',rules),('status',status)]:
   c,p,f=await page(path,width=620);await p.wait_for_timeout(400);assert await p.evaluate('document.documentElement.scrollWidth<=innerWidth');await p.screenshot(path=str(OUT/f'15-{name}-narrow.png'),full_page=True);passed(name+'窄屏无整页溢出');await c.close()
  await b.close()
 result=dict(synthetic=True,realBackendTested=False,checks=checks,browserErrors=errors);(OUT/'rules-status-browser-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));assert not errors
if __name__=='__main__':asyncio.run(main())
