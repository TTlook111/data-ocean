"""Phase7 D: real Chromium, synthetic loopback responses."""
import asyncio,json,re,sys
from pathlib import Path
from urllib.parse import urlparse,parse_qs
sys.path.insert(0,str(Path(__file__).resolve().parent))
from governance_business_v2 import Fixture as Base,OUT
from playwright.async_api import async_playwright
class Fixture(Base):
 async def route(self,route):
  req=route.request;u=urlparse(req.url);p=u.path;q=parse_qs(u.query)
  async def send(data,status=200,message='ok'):
   self.calls.append(dict(path=p,method=req.method,query=q));await route.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=message,data=data),ensure_ascii=False))
  if p=='/api/field-tags/predefined':return await send([dict(id=1,tagCode='MONEY',tagName='金额',category='BUSINESS',description='合成标签')])
  if re.fullmatch(r'/api/field-tags/column/\d+',p):return await send([dict(id=1,columnMetaId=int(p.split('/')[-1]),tagCode='MONEY',tagName='金额',source='MANUAL',createdAt='2026-10-29T10:00:00')])
  if p=='/api/field-tags/batch':self.actions.append(dict(path=p,payload=req.post_data_json));await asyncio.sleep(.25);return await send(dict(tagged=0))
  if p=='/api/admin/fields/auto-tag':self.actions.append(dict(path=p,query=q));return await send(dict(tagged=0,skipped=5))
  if re.fullmatch(r'/api/admin/field-profiles/snapshots/11/columns/\d+',p):
   cid=int(p.split('/')[-1]);return await send(dict(columnMetaId=cid,fieldName='orders.'+str(cid),revisionId=901,reviewStatus='APPROVED',score=0,evidenceCount=1,positiveCount=0,negativeCount=1,tags=['金额']))
  if p.endswith('/evidence'):return await send(dict(revisionId=902 if self.mode=='revision-changed' else 901,records=[],total=0))
  if p=='/api/field-confidence':return await send(dict(records=[] if q.get('pageSize')==['1'] else [dict(columnMetaId=204,columnName='amount',tableName='orders',score=0,level='LOW',reason='合成旧来源政策，非当前使用档案',lastUpdated='2026-10-29T10:00:00')],total=0 if q.get('pageSize')==['1'] else 1))
  if p=='/api/field-confidence/204':self.actions.append(dict(path=p,payload=req.post_data_json));await asyncio.sleep(.25);return await send(None,409,'合成：旧分已经变化，原输入保留')
  if re.fullmatch(r'/api/admin/fields/\d+/confidence-trend',p):await asyncio.sleep(.15);return await send([dict(time='2026-10-28T10:00:00',cumulativeScore=0,deltaScore=0,eventType='SYNTHETIC_MANUAL'),dict(time='2026-10-29T10:00:00',cumulativeScore=10,deltaScore=10,eventType='SYNTHETIC_MANUAL')])
  return await super().route(route)
async def main():
 checks=[];errors=[]
 def passed(x):checks.append(dict(name=x,status='passed'))
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/usr/bin/chromium')
  async def page(tab,mode='normal',width=1600):
   c=await b.new_context(viewport=dict(width=width,height=1050),reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-manager');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))");f=Fixture(mode);await c.route('http://127.0.0.1:5173/api/**',f.route);p=await c.new_page();p.on('pageerror',lambda e:errors.append(str(e)));await p.goto('http://127.0.0.1:5173/admin/governance/fields?datasourceId=1&snapshotId=11&tab='+tab);await p.wait_for_selector('h1');await p.evaluate("""()=>{let d=document.createElement('div');d.textContent='合成 API · 非真实联调';d.style.cssText='position:fixed;bottom:8px;right:16px;z-index:99999;background:#fff6e9;color:#725034;padding:6px;font:11px sans-serif;pointer-events:none';document.body.append(d)}""");return c,p,f
  async def table(p):await p.locator('.field-tag-page .toolbar .el-select').click();await p.get_by_role('option',name='orders',exact=False).click();await p.get_by_role('button',name='查看标签',exact=True).first.wait_for()
  c,p,f=await page('tags');await table(p);await p.screenshot(path=str(OUT/'21-field-tags-workspace.png'),full_page=True);await p.get_by_role('button',name='查看标签',exact=True).first.click();await p.get_by_role('dialog').get_by_text('MONEY',exact=True).wait_for();await p.get_by_role('dialog').get_by_role('button',name='关闭',exact=True).click();passed('精确快照字段标签与来源按需查看')
  await p.locator('.field-tag-page .el-table thead .el-checkbox').click();await p.get_by_role('button',name='批量打标',exact=True).click();d=p.get_by_role('dialog');await d.locator('.el-select').click();await p.get_by_role('option',name='金额（MONEY）',exact=True).click();await d.get_by_role('button',name='确认打标',exact=True).click(click_count=2);await p.locator('.tag-receipt').wait_for();await d.wait_for(state='hidden');assert len(f.actions)==1 and len(f.actions[0]['payload']['columnMetaIds'])==5;await p.screenshot(path=str(OUT/'22-field-tags-receipt.png'),full_page=True);passed('批量冻结5字段，双击一次，新增0保留')
  await p.get_by_role('button',name='自动打标',exact=True).click();await p.get_by_role('dialog').get_by_text('确认源级模式打标',exact=True).wait_for();assert '全部采集版本' in await p.get_by_role('dialog').inner_text();await p.screenshot(path=str(OUT/'23-source-pattern-confirmation.png'),full_page=True);await p.get_by_role('button',name='取消',exact=True).click();assert len(f.actions)==1;passed('模式打标明确源级全版本，取消零写');await c.close()
  c,p,f=await page('confidence','revision-changed');await p.locator('.usage-profiles>.el-select').click();await p.get_by_role('option',name='orders',exact=False).click();await p.get_by_role('button',name='证据明细',exact=True).first.wait_for();await p.screenshot(path=str(OUT/'24-current-usage-profiles.png'),full_page=True);await p.get_by_role('button',name='证据明细',exact=True).first.click();await p.get_by_text('字段修订已变化',exact=False).wait_for();await p.screenshot(path=str(OUT/'25-usage-evidence-revision-mismatch.png'),full_page=True);passed('当前修订0分与真实证据，错版证据不归属当前字段');await p.get_by_role('dialog').get_by_role('button',name='关闭',exact=True).click()
  await p.get_by_role('button',name='查看既有来源可信度记录',exact=False).click();await p.get_by_role('heading',name='既有来源可信度记录',exact=True).wait_for();await p.screenshot(path=str(OUT/'26-legacy-source-score.png'),full_page=True);await p.locator('.confidence-page').get_by_role('button',name='趋势',exact=True).click();await p.get_by_role('dialog').locator('canvas').wait_for();await p.screenshot(path=str(OUT/'26a-legacy-trend-canvas.png'),full_page=True);passed('旧趋势数据加载后真实canvas渲染');await p.keyboard.press('Escape');await p.locator('.confidence-page').get_by_role('button',name='设置',exact=True).click();d=p.get_by_role('dialog');await d.locator('textarea').fill('合成：人工追溯调整');await d.get_by_role('button',name='确认设置',exact=True).click(click_count=2);await d.get_by_text('合成：旧分已经变化，原输入保留',exact=True).wait_for();assert await d.locator('textarea').input_value()=='合成：人工追溯调整';assert len(f.actions)==1;await p.screenshot(path=str(OUT/'27-legacy-score-conflict.png'),full_page=True);passed('旧政策与当前分分开，人工手调409保留0分和理由');await c.close()
  for tab in ['tags','confidence']:
   c,p,f=await page(tab,width=620);await p.wait_for_timeout(400);assert await p.evaluate('document.documentElement.scrollWidth<=innerWidth');await p.screenshot(path=str(OUT/f'28-{tab}-narrow.png'),full_page=True);passed(tab+'窄屏不整页溢出');await c.close()
  await b.close()
 result=dict(synthetic=True,realBackendTested=False,checks=checks,browserErrors=errors);(OUT/'field-tools-browser-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));assert not errors
if __name__=='__main__':asyncio.run(main())
