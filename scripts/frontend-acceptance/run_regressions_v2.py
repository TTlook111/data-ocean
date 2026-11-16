"""Sequential final browser regressions, unique artifacts, no prior evidence overwrite.
Requires the existing loopback Vite frontend at 127.0.0.1:5173 and Python Playwright.
Each child reuses the committed page's synthetic fixture, not a real backend.
The unified stateful journey is separate: stateful_journey_v2.py.
"""
import argparse, datetime, json, subprocess, sys
from pathlib import Path

CASES=[('query_conversation_v2',[]),('datasource_workspace_v2',[]),('collection_assets_v2',['--phase','all']),('governance_v2',['--phase','all']),('releases_knowledge_v2',[]),('knowledge_workspace_v2',[]),('lineage_audit_v2',[]),('governance_completion_v2',[]),('governance_rules_status_v2',[]),('governance_field_tools_v2',[]),('governance_business_v2',[]),('governance_feedback_v2',[]),('governance_glossary_v2',[]),('iam_product_v2',[]),('iam_mask_v2',[]),('iam_effective_v2',[]),('iam_approval_v2',[]),('iam_organization_v2',[]),('iam_users_v2',[]),('iam_departments_v2',[])]
CASES += [('product_completion_v2',['--page',page]) for page in ['workbench','runtime','ai','prompts','login','password','profile','query-guide','admin-guide','not-found','notifications','redirects']]

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--output-root',default='output/playwright/final-regressions-v2');ap.add_argument('--only',nargs='*');args=ap.parse_args()
 root=Path(args.output_root)/datetime.datetime.now(datetime.timezone.utc).strftime('run-%Y%m%dT%H%M%S');root.mkdir(parents=True,exist_ok=False)
 scripts=Path(__file__).resolve().parent;results=[]
 for name,argv in CASES:
  case=name+('-'+argv[-1] if '--page' in argv else '')
  if args.only and case not in args.only:continue
  out=(root/case).resolve();out.mkdir()
  # Importing a page module does not run its __main__. Override its output before
  # calling main, including modules whose CLI defaults capture OUT at call time.
  code="""import asyncio,importlib,re,sys
from pathlib import Path
from playwright.async_api import Browser
original_context=Browser.new_context
async def isolated_context(self,*args,**kwargs):
 context=await original_context(self,*args,**kwargs)
 await context.route(re.compile(r'^https?://(?!127\\.0\\.0\\.1:5173/)'),lambda route:route.abort('blockedbyclient'))
 return context
Browser.new_context=isolated_context
sys.path.insert(0,sys.argv[1]);module=importlib.import_module(sys.argv[2]);module.OUT=Path(sys.argv[3]);sys.argv=[sys.argv[2]+'.py',*sys.argv[4:]];asyncio.run(module.main())
"""
  started=datetime.datetime.now(datetime.timezone.utc).isoformat()
  with (out/'run.log').open('w') as log:
   try:p=subprocess.run([sys.executable,'-c',code,str(scripts),name,str(out),*argv],stdout=log,stderr=subprocess.STDOUT,timeout=240);status=p.returncode
   except subprocess.TimeoutExpired:status='timeout'
  evidence=[];checks=[];errors=[]
  for path in out.glob('*.json'):
   data=json.loads(path.read_text())
   if isinstance(data,dict) and isinstance(data.get('checks'),list):
    evidence.append(path.name);checks.extend(data['checks']);errors.extend(data.get('browserErrors',[]))
  result=dict(case=case,startedAt=started,exit=status,checks=len(checks),checkFailures=[x for x in checks if x.get('status')!='passed'],browserErrors=errors,evidence=evidence,passed=status==0 and bool(checks) and not errors and all(x.get('status')=='passed' for x in checks))
  results.append(result);(root/'index.json').write_text(json.dumps(dict(synthetic=True,realBackendTested=False,groups=results),ensure_ascii=False,indent=2));print(case,status,len(checks),'PASS' if result['passed'] else 'FAIL',flush=True)
 assert results and all(r['passed'] for r in results),str(root/'index.json')
 print('RESULT',root,'groups',len(results),'checks',sum(r['checks'] for r in results),flush=True)
if __name__=='__main__':main()
