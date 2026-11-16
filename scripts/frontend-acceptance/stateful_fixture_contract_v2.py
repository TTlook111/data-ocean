"""No-network contract probes for the synthetic journey harness; not backend tests."""
import asyncio, json, sys, hashlib
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from stateful_journey_fixture import JourneyFixture, ROLES
class Request:
 def __init__(self,method,path,body=None):
  self.url='http://127.0.0.1:5173'+path;self.method=method;self.headers={'authorization':'Bearer synthetic-probe','content-type':'application/json'};self.post_data=json.dumps(body) if body is not None else None;self.post_data_json=body
class Route:
 def __init__(self,method,path,body=None):self.request=Request(method,path,body);self.response={}
 async def fulfill(self,**kw):self.response=kw
 async def abort(self,*a):self.response={'status':'aborted'}
async def probe(name,method,path,expected,actor='operator',body=None,prepare=None):
 f=JourneyFixture();f.tokens['synthetic-probe']=actor
 if prepare:prepare(f)
 r=Route(method,path,body)
 try:
  await f.route(r)
  status=r.response.get('status');data=json.loads(r.response['body']).get('data') if 'body' in r.response else None
  return dict(name=name,expected=expected,actual=status,passed=(status in expected if isinstance(expected,list) else status==expected),transitions=f.transitions,unhandled=f.unhandled,data=data if name.startswith('capability') else None)
 except Exception as e:return dict(name=name,expected=expected,actual='exception',passed=False,error=str(e))
async def main():
 checks=[]
 checks.append(await probe('unhandled POST notification rejects','POST','/api/notifications',404))
 checks.append(await probe('DELETE auth logout rejects','DELETE','/api/auth/logout',404))
 checks.append(await probe('GET connection test does not write','GET','/api/admin/datasources/test-connection',[404,405]))
 checks.append(await probe('candidate stale query version conflicts','POST','/api/admin/field-semantics/columns/204/candidates?snapshotId=11&expectedVersion=999',409))
 checks.append(await probe('draft wrong body snapshot rejects','PUT','/api/admin/field-semantics/columns/204/draft',[404,409],body={'snapshotId':22,'expectedVersion':1,'businessName':'合成','meaning':'合成','evidence':'合成'}))
 checks.append(await probe('readonly draft denies','PUT','/api/admin/field-semantics/columns/204/draft',403,actor='readonly',body={'snapshotId':11,'expectedVersion':1,'businessName':'合成','meaning':'合成','evidence':'合成'}))
 checks.append(await probe('unknown business write rejects','POST','/api/admin/not-implemented',404))
 original=ROLES['operator'].get('sources')
 ROLES['operator']['sources']=[1]
 cap=await probe('capability respects same binding source filter','GET','/api/iam-s1/capabilities',200)
 cap['passed']=cap['passed'] and all(x['datasourceId']==1 for x in cap['data']['datasourceCapabilities'])
 cap['actualSources']=[x['datasourceId'] for x in cap['data']['datasourceCapabilities']];del cap['data'];checks.append(cap)
 if original is None:del ROLES['operator']['sources']
 else:ROLES['operator']['sources']=original
 assert all(x['passed'] for x in checks),checks
 print(json.dumps({'kind':'pure in-memory synthetic fixture contract probes; no browser/network/API','sourceHash':hashlib.sha256(Path(__file__).with_name('stateful_journey_fixture.py').read_bytes()).hexdigest(),'checks':checks},ensure_ascii=False,indent=2))
if __name__=='__main__':asyncio.run(main())
