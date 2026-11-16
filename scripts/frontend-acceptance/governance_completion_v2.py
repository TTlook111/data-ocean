"""Phase 7: real Vue/Chromium with synthetic loopback API; never a backend integration test."""
import asyncio,json,re,sys
from pathlib import Path
from urllib.parse import urlparse,parse_qs
sys.path.insert(0,str(Path(__file__).resolve().parent))
from governance_v2 import GovernanceFixture as PreviousFixture, COLS
from playwright.async_api import async_playwright
OUT=Path('output/playwright/governance-completion-v2-20261029')
ISSUES=[dict(id=401,snapshotId=11,datasourceId=1,issueDomain='SOURCE',dimension='COMPLETENESS',severity='HIGH',tableName='orders',columnName='amount',columnMetaId=204,issueDescription='合成：原始金额说明缺少单位',suggestion='核对来源资料；不会由AI自动确认',status='OPEN',createdAt='2026-10-29T10:00:00'),dict(id=402,snapshotId=11,datasourceId=1,issueDomain='SEMANTIC',dimension='ACCURACY',severity='MEDIUM',tableName='orders',columnName='status',columnMetaId=205,issueDescription='合成：订单状态解释尚未通过同修订复查',status='CONFIRMED',createdAt='2026-10-29T10:00:00')]
RULE=dict(id=71,ruleCode='SYNTHETIC-71',templateCode='COMP_COLUMN_COMMENT_MISSING',ruleName='字段原始说明完整性',dimension='COMPLETENESS',severity='HIGH',description='合成来源规则；与人工语义解释分离',enabled=1,deductionPoints=5,builtin=1,checkTarget='COLUMN',checkType='SCHEMA',scopeType='GLOBAL',parameters={},configVersion=2,supported=True)
SUMMARY=dict(datasourceId=1,snapshotId=11,globalCount=1,datasourceCount=1,enabledCount=2,applicableCount=1,unsupportedCount=0,overriddenCount=0,notApplicableCount=1,invalidCount=0,rules=[dict(ruleId=71,ruleName=RULE['ruleName'],configVersion=2,scopeType='GLOBAL',status='READY',targetCount=8,plannedTargetCount=8),dict(ruleId=72,ruleName='订单更新时间诊断',configVersion=1,scopeType='TABLE',status='NOT_APPLICABLE',reason='合成：当前表未指定可核验的更新时间字段',targetCount=0,plannedTargetCount=0)])
BATCH=dict(id=61,datasourceId=1,snapshotId=11,requestedScope='FULL',status='PARTIAL',complete=False,executedRuleCount=0,failedRuleCount=1,notApplicableRuleCount=1,totalScore=None,dimensionScores={},errorSummary='合成：一个检查失败，不能作为完整检查结果',startedAt='2026-10-29T10:00:00',rules=[dict(ruleId=71,ruleName=RULE['ruleName'],ruleVersion=2,dimension='COMPLETENESS',severity='HIGH',deductionPoints=5,scopeType='GLOBAL',parameters={},status='FAILURE',reason='合成：只读数据诊断未执行，需核对连接',targetCount=8,checkedTargetCount=0,issueCount=0)])
class Fixture(PreviousFixture):
 def __init__(self,mode='normal'):
  super().__init__(mode);self.actions=[];self.issue_rows=[dict(x) for x in ISSUES]
 async def route(self,route):
  req=route.request;u=urlparse(req.url);p=u.path;q=parse_qs(u.query)
  async def send(data,status=200,message='ok'):
   self.calls.append(dict(path=p,method=req.method,query=q));await route.fulfill(status=status,content_type='application/json',body=json.dumps(dict(code=status,message=message,data=data),ensure_ascii=False))
  if p=='/api/iam-s1/capabilities':
   codes=['metadata:view','governance:issue:view','governance:issue:manage'] if self.mode=='issue-only' else ['metadata:view','governance:view'] if self.mode=='overview-only' else ['governance:view','governance:issue:view','governance:rule:view'] if self.mode=='minimal' else ['metadata:view','governance:view','governance:issue:view','governance:rule:view'] if self.mode=='readonly' else []
   return await send(dict(protocolVersion='IAM-SIMPLE-1',userId=1,systemAdmin=self.mode not in ['minimal','readonly','issue-only','overview-only'],globalFunctions=[],datasourceCapabilities=[dict(datasourceId=i,datasourceName='销售经营' if i==1 else '供应链',functionCodes=codes,functionNames=[]) for i in [1,2]],queryUse=True,viewSql=True,export=True,permissionRevision=1))
  if p=='/api/admin/users':return await send(dict(records=[dict(id=77,username='synthetic_reviewer',realName='合成审核员',status=1)],total=1))
  if re.fullmatch(r'/api/admin/snapshots/\d+/review-records',p):return await send(dict(records=[],total=0))
  if re.fullmatch(r'/api/admin/snapshots/\d+/quality-check-batches/latest',p):return await send(None,503,'合成批次读取失败') if self.mode=='batch-error' else await send(None if self.mode=='empty' else BATCH)
  if re.fullmatch(r'/api/admin/snapshots/\d+/quality-rules/summary',p):return await send(SUMMARY)
  if re.fullmatch(r'/api/admin/snapshots/\d+/quality-issues/summary',p):return await send(dict(totalUnresolved=2,statusCounts=dict(OPEN=1,CONFIRMED=1,REOPENED=0),domainCounts=dict(SOURCE=1,SEMANTIC=1),recentIssues=self.issue_rows))
  if re.fullmatch(r'/api/admin/snapshots/\d+/quality-issues',p):
   if self.mode=='list-error':return await send(None,503,'合成问题读取失败')
   rows=[] if self.mode=='empty' else [x for x in self.issue_rows if not q.get('issueDomain') or q['issueDomain'][0]=='ALL' or x['issueDomain']==q['issueDomain'][0]]
   return await send(dict(records=rows,total=len(rows)))
  if re.fullmatch(r'/api/admin/snapshots/\d+/quality-check',p):
   self.actions.append(dict(path=p,method=req.method));await asyncio.sleep(.35)
   return await send(dict(snapshotId=11,status='PARTIAL',complete=False,checkBatchId=62,executedRuleCount=0,failedRuleCount=1,notApplicableRuleCount=1,dimensionScores={},totalIssues=2,issueCount={}))
  if p=='/api/admin/quality-rules':return await send(dict(records=[RULE,{**RULE,'id':72,'ruleName':'订单更新时间诊断','scopeType':'TABLE','datasourceId':1,'enabled':0}],total=2))
  if p=='/api/admin/quality-rule-templates':return await send([dict(code=RULE['templateCode'],name='字段注释缺失',dimension='COMPLETENESS',targetType='COLUMN',checkType='SCHEMA',description='合成：检查原始字段说明',allowedScopes=['GLOBAL','DATASOURCE','TABLE','COLUMN'],parameters=[])])
  if re.fullmatch(r'/api/admin/quality-issues/\d+/status',p):
   self.actions.append(dict(path=p,method=req.method,payload=req.post_data_json));await asyncio.sleep(.3)
   if self.mode=='conflict':return await send(None,409,'合成：问题状态或复查证据已变化')
   for row in self.issue_rows:
    if row['id']==int(p.split('/')[-2]):row['status']=req.post_data_json['status']
   return await send(None)
  if p=='/api/admin/quality-issues/batch-status':
   self.actions.append(dict(path=p,method=req.method,payload=req.post_data_json));return await send(dict(updated=1,skipped=1,skippedIssues=[dict(issueId=402,reason='合成：字段语义需逐项核对最新复查和处理依据')]))
  return await super().route(route)
async def main():
 import argparse
 parser=argparse.ArgumentParser();parser.add_argument('--baseline',action='store_true');parser.add_argument('--remaining-baseline',action='store_true');args=parser.parse_args();OUT.mkdir(parents=True,exist_ok=True);checks=[];errors=[]
 def passed(name):checks.append(dict(name=name,status='passed'))
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/usr/bin/chromium')
  async def open_page(path='/admin/governance?datasourceId=1&snapshotId=11',mode='normal',width=1600):
   c=await b.new_context(viewport=dict(width=width,height=1050),reduced_motion='reduce');await c.add_init_script("localStorage.setItem('dataocean_token','synthetic-manager');localStorage.setItem('dataocean_user',JSON.stringify({username:'fixture_manager',passwordChanged:true}))");f=Fixture(mode);await c.route('http://127.0.0.1:5173/api/**',f.route);page=await c.new_page();page.on('pageerror',lambda e:errors.append(str(e)));await page.goto('http://127.0.0.1:5173'+path);await page.wait_for_selector('h1');await page.evaluate("""() => {const mark=document.createElement('div');mark.textContent='合成 API · 设计验收 · 非真实联调';mark.style.cssText='position:fixed;bottom:8px;right:16px;z-index:99999;background:#fff6e9;border:1px solid #b9a48b;color:#725034;border-radius:5px;padding:5px 9px;font:11px sans-serif;pointer-events:none';document.body.append(mark)}""");return c,page,f
  if args.baseline or args.remaining_baseline:
   routes=[('overview','/admin/governance?datasourceId=1&snapshotId=11'),('issues','/admin/governance/issues?datasourceId=1&snapshotId=11'),('rules','/admin/governance/rules?datasourceId=1&snapshotId=11'),('status','/admin/governance/status?datasourceId=1&snapshotId=11'),('business','/admin/semantics/business-knowledge?datasourceId=1&snapshotId=11'),('tags','/admin/governance/fields?datasourceId=1&snapshotId=11&tab=tags'),('profiles','/admin/governance/fields?datasourceId=1&snapshotId=11&tab=confidence'),('feedback','/admin/governance/fields?datasourceId=1&snapshotId=11&tab=feedback'),('glossaries','/admin/semantics/glossaries')]
   for name,path in routes:
    if args.remaining_baseline and (OUT/('baseline-'+name+'.png')).exists():continue
    c,page,f=await open_page(path);await page.wait_for_timeout(350);await page.screenshot(path=str(OUT/('baseline-'+name+'.png')),full_page=True);await c.close()
   await b.close();return
  c,p,f=await open_page();await p.get_by_text('校验部分完成',exact=True).wait_for();assert 'SOURCE' in await p.locator('.quality-contract').inner_text();await p.locator('.quality-page__details summary').click();assert '0 项实际执行' in await p.locator('.quality-page__record').inner_text();await p.screenshot(path=str(OUT/'01-overview-source-coverage.png'),full_page=True);passed('来源/语义独立、部分失败及真实0执行覆盖')
  await p.get_by_role('button',name='执行质量校验',exact=True).click(click_count=2);await p.wait_for_timeout(450);assert len([x for x in f.actions if x['path'].endswith('/quality-check')])==1;passed('来源检查重复点击一次请求');assert not any('/semantic-checks/' in x['path'] and x['method']=='POST' for x in f.actions);passed('来源检查不自动语义复查/审核或奖励')
  await p.get_by_role('button',name='开始知识治理',exact=True).click();await p.wait_for_url('**/admin/governance/fields?**');assert 'tab=tasks' in p.url;passed('开始知识治理只导航到当前源快照任务');await c.close()
  c,p,f=await open_page(mode='overview-only');await p.get_by_text('校验部分完成',exact=True).wait_for();assert not any('quality-rules/summary' in x['path'] or 'quality-issues/summary' in x['path'] for x in f.calls);passed('总览权不隐含规则或问题查看权');await c.close()
  c,p,f=await open_page(mode='minimal');await p.wait_for_timeout(350);assert not any('quality-check-batches' in x['path'] for x in f.calls);assert 'metadata:view' in await p.locator('.quality-page').inner_text();await p.screenshot(path=str(OUT/'02-overview-missing-snapshot-helper.png'),full_page=True);passed('最小治理角色公共快照合同缺口明确展示不补权');await c.close()
  c,p,f=await open_page(mode='batch-error');await p.get_by_text('校验状态读取失败',exact=True).wait_for();await p.screenshot(path=str(OUT/'03-overview-read-error.png'),full_page=True);passed('批次失败与无批次分开');await c.close()
  c,p,f=await open_page('/admin/governance/issues?datasourceId=1&snapshotId=11');await p.get_by_text(ISSUES[0]['issueDescription'],exact=True).wait_for();await p.screenshot(path=str(OUT/'04-issue-workspace.png'),full_page=True);passed('来源和语义问题域与服务端总数')
  row=p.locator('.el-table__row').filter(has_text=ISSUES[1]['issueDescription']);await row.get_by_role('button',name='解决',exact=True).click();dialog=p.get_by_role('dialog');await dialog.get_by_label('处理依据',exact=True).fill('合成：修订R1、语义复查61、资料第3节');await dialog.get_by_role('button',name='确认处理',exact=True).click(click_count=2);await p.wait_for_timeout(450);writes=[x for x in f.actions if x['path'].endswith('/402/status')];assert len(writes)==1 and writes[0]['payload']['resolutionNote'].startswith('合成：');passed('语义问题处理发送冻结目标及依据，双击一次')
  table=p.locator('.issue-table-scroll');await table.locator('thead .el-checkbox').click();await p.get_by_role('button',name='批量驳回',exact=True).click();await p.locator('.issue-batch-receipt').wait_for();assert '402' in await p.locator('.issue-batch-receipt').inner_text();await p.screenshot(path=str(OUT/'05-issue-partial-receipt.png'),full_page=True);passed('批量部分成功逐项保留原因，不报全成功');await c.close()
  c,p,f=await open_page('/admin/governance/issues?datasourceId=1&snapshotId=11','conflict');await p.get_by_text(ISSUES[1]['issueDescription'],exact=True).wait_for();await p.locator('.el-table__row').filter(has_text=ISSUES[1]['issueDescription']).get_by_role('button',name='解决',exact=True).click();d=p.get_by_role('dialog');await d.get_by_label('处理依据',exact=True).fill('合成：409后保留原依据');await d.get_by_role('button',name='确认处理',exact=True).click();await d.get_by_text('合成：问题状态或复查证据已变化',exact=True).wait_for();assert await d.get_by_label('处理依据',exact=True).input_value()=='合成：409后保留原依据';await p.screenshot(path=str(OUT/'06-issue-conflict-draft.png'),full_page=True);passed('409保留原问题依据，不自动重发');await c.close()
  c,p,f=await open_page('/admin/governance/issues?datasourceId=1&snapshotId=11','issue-only');await p.get_by_text(ISSUES[0]['issueDescription'],exact=True).wait_for();assert not any(x['path']=='/api/admin/users' for x in f.calls);passed('问题管理权不隐含用户目录查看权');await c.close()
  for mode,name in [('empty','07-issue-empty.png'),('list-error','08-issue-error.png')]:
   c,p,f=await open_page('/admin/governance/issues?datasourceId=1&snapshotId=11'+('&tableName=synthetic_no_match' if mode=='empty' else ''),mode);await p.wait_for_timeout(350);await p.screenshot(path=str(OUT/name),full_page=True);passed('问题'+mode+'实际页面状态');await c.close()
  c,p,f=await open_page('/admin/governance/issues?datasourceId=1&snapshotId=11',width=620);await p.get_by_text(ISSUES[0]['issueDescription'],exact=True).wait_for();assert await p.evaluate('document.documentElement.scrollWidth<=window.innerWidth');await p.screenshot(path=str(OUT/'09-issue-narrow.png'),full_page=True);passed('窄屏筛选换行与表内滚动');await c.close()
  await b.close()
 result=dict(synthetic=True,realBackendTested=False,checks=checks,browserErrors=errors);(OUT/'overview-issues-browser-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False));assert not errors
if __name__=='__main__':asyncio.run(main())
