"""Independent synthetic sessions with positive queries and explicit boundaries."""
import json
import secrets
from datetime import datetime

import acceptance as entry
from flows import record
import second_source

CONTRACT="synthetic-roles-v1"
NAMES={"reader","no_data","secondary","limited","split","manager","reviewer"}


def private_path(api):return api.output.parent/"system-acceptance-expanded-actors.private.json"
def load(api):return json.loads(private_path(api).read_text(encoding="utf-8"))
def save(api,value):private_path(api).write_text(json.dumps(value,indent=2),encoding="utf-8")


def setup(api):
    source=second_source.state(api);sid=source["snapshotId"]
    actors=load(api) if private_path(api).exists() else {}
    original=json.loads((api.output.parent/"system-acceptance-actors.private.json").read_text(encoding="utf-8"))
    for key in ("reader","no_data"):actors.setdefault(key,original[key])
    roles=api.call("GET","/api/iam-s1/roles")
    def role(key,functions):
        code="acceptance_expansion_"+key+"_20261028"
        existing=next((r for r in roles if r["roleCode"]==code),None)
        if existing:
            if key=="query_only":
                detail=api.call("GET",f"/api/iam-s1/roles/{existing['id']}")
                if set(detail.get("functionCodes",[]))!=set(functions):
                    api.call("PUT",f"/api/iam-s1/roles/{existing['id']}",{"roleCode":code,"roleName":"合成扩展 "+key,"description":CONTRACT,
                        "status":1,"functionCodes":functions,"reason":CONTRACT+"; explicit management-binding test"})
            return existing["id"]
        rid=api.call("POST","/api/iam-s1/roles",{"roleCode":code,"roleName":"合成扩展 "+key,
            "description":CONTRACT,"status":1,"functionCodes":functions,"reason":CONTRACT})
        roles.append({"roleCode":code,"id":rid});return rid
    query=role("query",["query:use","query:sql:view","query:export"])
    minimal=role("query_only",["query:use","knowledge:view"])
    view=role("view_only",["metadata:view"])
    manager=role("manager",["knowledge:view","knowledge:manage"])
    reviewer=role("reviewer",["knowledge:view","knowledge:approve"])
    definitions={"secondary":[(query,[2])],"limited":[(query,[1])],"split":[(minimal,[1]),(view,[2])],
                 "manager":[(manager,[2])],"reviewer":[(reviewer,[2])]}
    for name,bindings in definitions.items():
        actor=actors.setdefault(name,{"username":"acceptance_exp_"+name+"_20261028","password":"Dd4"+secrets.token_urlsafe(15),"bindings":[]})
        save(api,actors)
        if not actor.get("id"):
            actor["id"]=api.call("POST","/api/admin/users",{"username":actor["username"],"password":actor["password"],
                "realName":"合成扩展 "+name,"email":actor["username"]+"@example.test","roleIds":[]})["id"]
            save(api,actors)
        for rid,sources in bindings:
            if any(b["roleId"]==rid for b in actor["bindings"]):continue
            bid=api.call("POST",f"/api/iam-s1/users/{actor['id']}/roles",{"roleId":rid,"reason":CONTRACT})
            api.call("PUT",f"/api/iam-s1/user-roles/{bid}/datasources",{"datasourceIds":sources,"reason":CONTRACT})
            actor["bindings"].append({"roleId":rid,"bindingId":bid,"datasourceIds":sources});save(api,actors)
    primary=[{"columnMetaId":1008,"columnName":"product_id"},{"columnMetaId":1009,"columnName":"product_name"},{"columnMetaId":1010,"columnName":"category"}]
    secondary=[{"columnMetaId":c["id"],"columnName":c["columnName"]} for c in source["columns"]]
    grants=[("secondary",2,sid,secondary,[]),("limited",1,1,[c for c in primary if c["columnName"]!="product_name"],
             [{"columnMetaId":1008,"columnName":"product_id","operatorCode":"EQ","valueType":"INTEGER","structuredValueJson":"101"}]),
            ("split",1,1,primary,[]),("split",2,sid,secondary,[])]
    for name,ds,snapshot,columns,conditions in grants:
        actor=actors[name];key=str(ds)
        if key in actor.get("grantIds",{}):continue
        gid=api.call("POST","/api/iam-s1/data-grants",{"protocolVersion":"IAM-SIMPLE-1","subjectType":"USER","subjectId":actor["id"],
            "datasourceId":ds,"metadataSnapshotId":snapshot,"resourceScope":"TABLE","tableName":"products","effect":"ALLOW",
            "columns":columns,"rowMatchType":"ALL","rowConditions":conditions,"validFrom":datetime.now().isoformat(timespec="seconds"),"reason":CONTRACT})
        actor.setdefault("grantIds",{})[key]=gid;save(api,actors)
    assert set(actors)==NAMES and len({a["id"] for a in actors.values()})==len(NAMES)
    api.verify_target()
    result={"passed":True,"actors":[{"name":key,"id":v["id"],"bindings":v.get("bindings"),"grantIds":v.get("grantIds")} for key,v in actors.items()],
            "limit":"Synthetic test accounts only; normal captcha login and password change still required"}
    record(api,"expanded-role-setup.json",result);return result


def captchas(api):
    assert api.target_verified,"Parent target must be verified first"
    api.expanded_actor_clients={}
    result=[]
    for name,actor in load(api).items():
        cfg={**api.cfg,"LANGGRAPH_ACCEPTANCE_READER_USERNAME":actor["username"],"LANGGRAPH_ACCEPTANCE_READER_PASSWORD":actor["password"]}
        output=api.output/("expanded-actor-"+name);output.mkdir(exist_ok=True)
        client=type(api)(cfg,str(api.client.base_url),output);client.verify_target_on_login=False
        api.expanded_actor_clients[name]=client
        result.append({"actor":name,"captcha":client.captcha()})
    return result


def login(api,name,code):
    actors=load(api);client=api.expanded_actor_clients[name];client.login(code)
    user=client.call("GET","/api/auth/me")
    if not user.get("passwordChanged"):
        changed="Ee5"+secrets.token_urlsafe(15)
        client.call("PUT","/api/auth/password",{"oldPassword":actors[name]["password"],"newPassword":changed})
        actors[name]["password"]=changed;save(api,actors)
        client.cfg["LANGGRAPH_ACCEPTANCE_READER_PASSWORD"]=changed
        client.client.headers.pop("Authorization",None);client.authenticated=False
        return {"actor":name,"needsRelogin":True,"captcha":client.captcha()}
    if (not api.target_verified or str(api.client.base_url)!=str(client.client.base_url)
            or user.get("id")!=actors[name]["id"]):
        raise ValueError("Actor target/identity does not match the verified parent origin")
    assert not client.call("GET","/api/iam-s1/capabilities").get("systemAdmin")
    client.target_verified=True;client.output=api.output
    return {"actor":name,"authenticated":True,"targetBoundToParent":True,"userId":user["id"]}


def refuse(client,method,path,body=None,allowed=(400,403,404)):
    response=client.client.request(method,path,json=body);data=response.json()
    assert response.status_code in allowed and data.get("data") is None,"Boundary request unexpectedly succeeded"
    assert data.get("message"),"Refusal has no attributable reason"
    return {"path":path,"http":response.status_code,"reason":data.get("message"),"passed":True}


def check(api):
    api.verify_target()
    clients=getattr(api,"expanded_actor_clients",{});actors=load(api)
    assert set(clients)==NAMES and all(c.authenticated and c.target_verified for c in clients.values()),"All seven independently bound sessions are required"
    results=[]
    visibility={"reader":{1},"no_data":set(),"secondary":{2},"limited":{1},"split":{1,2}}
    for name,expected in visibility.items():
        me=clients[name].call("GET","/api/auth/me");assert me["id"]==actors[name]["id"]
        rows=clients[name].call("GET","/api/iam-s1/query-resources/datasources")
        assert {r.get("id",r.get("datasourceId")) for r in rows}==expected,f"{name} expected visible sources {expected}, received {rows}"
        results.append({"actor":name,"visibleDatasources":sorted(expected),"passed":True})
    for name,ds in (("no_data",1),("secondary",1),("manager",2),("reviewer",2)):
        result=refuse(clients[name],"POST","/api/iam-s1/query/ask",{"protocolVersion":"IAM-SIMPLE-1","datasourceId":ds,"question":"products 中商品有多少条？"},(400,403))
        results.append({"actor":name,"datasourceId":ds,**result})
    split=clients["split"]
    assert split.call("GET","/api/admin/business-knowledge/snapshots/1")
    assert split.call("GET",f"/api/admin/metadata/snapshots/{second_source.state(api)['snapshotId']}")
    results.append({"actor":"split","managementBinding":"knowledge only on source1",**refuse(split,"GET",f"/api/admin/business-knowledge/snapshots/{second_source.state(api)['snapshotId']}",(None),(403,))})
    results.append({"actor":"split","managementBinding":"metadata only on source2",**refuse(split,"GET","/api/admin/metadata/snapshots/1",(None),(403,))})
    for name,expected in (("reader",3),("limited",1),("split",3)):
        client=clients[name]
        cases={"contract":CONTRACT,"deniedTables":["employee_pay","sales_orders"],"questions":[{
            "id":"R-"+name,"kind":"rows","question":"products 中所有记录有多少条？只返回一行计数。",
            "tables":["products"],"expected":[[expected]],"expectedSnapshotId":1}]}
        if name=="split":cases["questions"][0]["expectedCanViewSql"]=False
        result=entry.Api.questions(client,cases,datasource=1,report_prefix="role-"+name+"-questions")
        results.append({"actor":name,"positiveQuery":result,"passed":result["passed"]})
        task=json.loads((api.output/("role-"+name+"-questions.json")).read_text(encoding="utf-8"))["checks"][0]
        assert task["passed"],"Positive role query did not pass its independent expectation"
        other=clients["secondary"]
        results.append({"actor":"secondary","crossOwner":True,**refuse(other,"GET",f"/api/iam-s1/query/tasks/{task['taskId']}")})
        if name=="split":
            for suffix in ("sql","export","export.csv"):
                results.append({"actor":name,"capabilitySeparation":suffix,**refuse(client,"GET",f"/api/iam-s1/query/tasks/{task['taskId']}/{suffix}",(None),(400,403))})
        else:
            sql=client.call("GET",f"/api/iam-s1/query/tasks/{task['taskId']}/sql")
            exported=client.call("GET",f"/api/iam-s1/query/tasks/{task['taskId']}/export")
            assert sql["sql"]==task["task"]["sql"] and exported==task["task"]["data"]
        history=client.call("GET","/api/iam-s1/query/history?pageNo=1&pageSize=100")
        with entry.connect(api.cfg) as db,db.cursor() as cur:
            cur.execute("SET SESSION TRANSACTION READ ONLY")
            cur.execute("SELECT task_id FROM query_task WHERE user_id=%s",(actors[name]["id"],));owned={r[0] for r in cur.fetchall()}
        assert history["records"] and all(r["taskId"] in owned for r in history["records"])
        assert task["taskId"] in {r["taskId"] for r in history["records"]}
    limited=clients["limited"]
    policy_only={"contract":CONTRACT,"deniedTables":["employee_pay"],"questions":[{"id":"R-policy-only","kind":"rows",
        "question":"列出 products 中所有记录的 category，按 category 升序。","expected":[["Furniture"]],"tables":["products"],"expectedSnapshotId":1}]}
    policy_result=entry.Api.questions(limited,policy_only,datasource=1,report_prefix="role-policy-only-projection")
    results.append({"actor":"limited","nonProjectedPolicyDependency":True,"passed":policy_result["passed"]})
    hidden={"contract":CONTRACT,"deniedTables":["employee_pay"],"questions":[{"id":"R-hidden","kind":"refusal",
        "question":"读取 products.product_name 的完整商品名称。","expected":[],"tables":[]}]}
    result=entry.Api.questions(limited,hidden,datasource=1,report_prefix="role-hidden-column")
    results.append({"actor":"limited","hiddenColumnRefusal":True,"passed":result["passed"]})
    results.extend(review_separation(api))
    outcome={"passed":all(r.get("passed") for r in results),"checks":results,
        "limit":"Seven real independent sessions; query, row/column, source, owner and function combinations, not every organization policy"}
    record(api,"expanded-role-matrix.json",outcome);return outcome


def review_separation(api):
    sid=second_source.state(api)["snapshotId"]
    manager=api.expanded_actor_clients["manager"];reviewer=api.expanded_actor_clients["reviewer"]
    key="acceptance.roles.review."+datetime.now().strftime("%Y%m%d%H%M%S%f")
    request={"kind":"METRIC","knowledgeKey":key,"title":"合成角色分权计数定义","body":{"description":"仅第二源两件商品的计数",
        "formula":"COUNT(products.product_id)","conditions":"products.product_id IS NOT NULL","grain":"一行一商品"},"dependencies":["table:products","column:products.product_id"],
        "unresolvedItems":[],"evidenceConfirmed":True,"evidenceReference":"role_matrix.py / synthetic-roles-v1 / two fixed products","evidence":CONTRACT}
    checks=[{"actor":"reviewer","cannotCreate":True,**refuse(reviewer,"POST",f"/api/admin/business-knowledge/snapshots/{sid}/drafts",request,(403,))}]
    row=manager.call("POST",f"/api/admin/business-knowledge/snapshots/{sid}/drafts",request)
    rid=row["id"]
    def reload():return next(r for r in api.call("GET",f"/api/admin/business-knowledge/snapshots/{sid}") if r["id"]==rid)
    def action(client,kind):
        current=reload()
        client.call("POST",f"/api/admin/business-knowledge/{rid}/actions",{"expectedVersion":current["rowVersion"],"action":kind,"note":CONTRACT})
        checks.append({"actor":"manager" if client is manager else "reviewer" if client is reviewer else "admin","action":kind,"revisionId":rid,"passed":True})
    action(manager,"SUBMIT")
    checks.append({"actor":"manager","cannotApprove":True,**refuse(manager,"POST",f"/api/admin/business-knowledge/{rid}/actions",
        {"expectedVersion":reload()["rowVersion"],"action":"APPROVE","note":CONTRACT},(403,))})
    action(reviewer,"APPROVE")
    checks.append({"actor":"reviewer","cannotRecheck":True,**refuse(reviewer,"POST",f"/api/admin/business-knowledge/{rid}/actions",
        {"expectedVersion":reload()["rowVersion"],"action":"RECHECK","note":CONTRACT},(403,))})
    action(manager,"RECHECK");assert reload()["effectiveStatus"]=="VALID"
    for name,client in (("manager",manager),("reviewer",reviewer)):
        checks.append({"actor":name,"cannotRevokeAlone":True,**refuse(client,"POST",f"/api/admin/business-knowledge/{rid}/actions",
            {"expectedVersion":reload()["rowVersion"],"action":"REVOKE","note":CONTRACT},(403,))})
    action(api,"REVOKE");assert reload()["status"]=="REVOKED"
    record(api,"expanded-role-review.json",{"passed":True,"revisionId":rid,"checks":checks,
        "limit":"New synthetic metric reviewed/rechecked then revoked through official APIs; never part of an active RAG build"})
    return checks


def run(api,flow):
    # The interactive acceptance process can outlive harness repairs. Load the
    # current verification contract without replacing any authenticated client.
    import importlib
    importlib.reload(entry)
    if flow=="roles-setup":return setup(api)
    if flow=="roles-captchas":return captchas(api)
    if flow=="roles-check":return check(api)
    if flow=="roles-split-secondary":
        source=second_source.state(api)
        return entry.Api.questions(api.expanded_actor_clients["split"],{"contract":CONTRACT,"deniedTables":["sales_orders","employee_pay"],
            "questions":[{"id":"R-split-secondary","kind":"rows","expectedCanViewSql":False,"expectedSnapshotId":source["snapshotId"],
                "expectedBuildId":source["build"]["buildId"],"question":"products 中所有记录有多少条？只返回一行计数。","tables":["products"],"expected":[[2]]}]},
            datasource=2,report_prefix="role-split-secondary-questions")
    if flow=="roles-inspect":
        result=[]
        for name in ("reader","no_data","secondary","limited","split"):
            result.append({"actor":name,"sources":api.expanded_actor_clients[name].call("GET","/api/iam-s1/query-resources/datasources")})
        return result
    if flow=="roles-logout":
        for client in api.expanded_actor_clients.values():client.close()
        return {"loggedOut":True}
    if flow.startswith("roles-captcha:"):
        name=flow.split(":",1)[1]
        return {"actor":name,"captcha":api.expanded_actor_clients[name].captcha()}
    if flow.startswith("roles-login:"):
        _,name,code=flow.split(":",2);return login(api,name,code)
    raise ValueError("Unknown expanded-role flow")
