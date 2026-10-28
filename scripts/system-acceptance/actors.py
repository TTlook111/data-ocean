"""Isolated synthetic actors. Credentials stay outside reports and the repository."""
import json
from datetime import datetime
from pathlib import Path
import secrets

REASON = "system-acceptance synthetic roles; isolated fixture only"

def private_path(api):
    return api.output.parent / "system-acceptance-actors.private.json"

def save_private(api, actors):
    private_path(api).write_text(json.dumps(actors, indent=2), encoding="utf-8")

def setup(api):
    if private_path(api).exists():
        actors = json.loads(private_path(api).read_text(encoding="utf-8"))
        if set(actors)!={"reader","no_data"} or any(not a.get("id") or not a.get("bindingId") for a in actors.values()):
            raise ValueError("Synthetic actor setup is incomplete; do not report role coverage")
        return {"reused": True, "actors": [{"name": k, "id": v.get("id")} for k,v in actors.items()]}
    roles = api.call("GET", "/api/iam-s1/roles")
    code = "acceptance_query_20261028"
    role = next((r for r in roles if r["roleCode"] == code), None)
    role_id = role["id"] if role else api.call("POST", "/api/iam-s1/roles", {
        "roleCode": code, "roleName": "合成验收查询角色", "description": REASON, "status": 1,
        "functionCodes": ["query:use","query:sql:view","query:export"], "reason": REASON})
    actors = {}
    for name in ["reader", "no_data"]:
        actor = {"username": "acceptance_" + name + "_20261028", "password": "Aa1" + secrets.token_urlsafe(15), "roleId": role_id}
        actors[name] = actor
        save_private(api, actors)
        actor["id"] = api.call("POST", "/api/admin/users", {"username": actor["username"], "password": actor["password"],
            "realName": "合成验收 " + name, "email": actor["username"] + "@example.test", "roleIds": []})["id"]
        save_private(api, actors)
        binding = api.call("POST", f"/api/iam-s1/users/{actor['id']}/roles", {"roleId": role_id, "reason": REASON})
        api.call("PUT", f"/api/iam-s1/user-roles/{binding}/datasources", {"datasourceIds":[1],"reason":REASON})
        actor["bindingId"] = binding
        if name == "reader":
            actor["grantId"] = api.call("POST", "/api/iam-s1/data-grants", {
                "protocolVersion":"IAM-SIMPLE-1", "subjectType":"USER", "subjectId":actor["id"],
                "datasourceId":1, "resourceScope":"TABLE", "metadataSnapshotId":1, "tableName":"products",
                "effect":"ALLOW", "validFrom":datetime.now().isoformat(timespec="seconds"), "reason":REASON,
                "columns":[{"columnMetaId":1008,"columnName":"product_id"},{"columnMetaId":1009,"columnName":"product_name"},
                           {"columnMetaId":1010,"columnName":"category"}]})
        save_private(api, actors)
    result={"actors":[{"name":name,"id":a["id"],"roleId":role_id,"bindingId":a["bindingId"],
                       "grantedTable":"products" if name=="reader" else None} for name,a in actors.items()]}
    (api.output/"synthetic-actors.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
    return result

def captcha(api):
    actors=json.loads(private_path(api).read_text(encoding="utf-8"))
    api.actor_clients={}
    result=[]
    for name,actor in actors.items():
        cfg={**api.cfg,"LANGGRAPH_ACCEPTANCE_READER_USERNAME":actor["username"],"LANGGRAPH_ACCEPTANCE_READER_PASSWORD":actor["password"]}
        output=api.output/("actor-"+name);output.mkdir(exist_ok=True)
        client=type(api)(cfg,str(api.client.base_url),output)
        client.verify_target_on_login=False
        api.actor_clients[name]=client
        result.append({"actor":name,"captcha":client.captcha()})
    return result

def login(api,name,code):
    actors=json.loads(private_path(api).read_text(encoding="utf-8"))
    client=api.actor_clients[name]
    client.login(code)
    user=client.call("GET","/api/auth/me")
    if not user.get("passwordChanged"):
        changed="Bb2"+secrets.token_urlsafe(15)
        client.call("PUT","/api/auth/password",{"oldPassword":actors[name]["password"],"newPassword":changed})
        actors[name]["password"]=changed
        save_private(api,actors)
        client.cfg["LANGGRAPH_ACCEPTANCE_READER_PASSWORD"]=changed
        client.client.headers.pop("Authorization",None)
        client.authenticated=False
        return {"actor":name,"passwordChangedThroughApi":True,"needsRelogin":True,"captcha":client.captcha()}
    return {"actor":name,"authenticated":True,"passwordChanged":True}

def check(api):
    results=[]
    if set(getattr(api,"actor_clients",{}))!={"reader","no_data"}:
        raise ValueError("Both independent reader/no_data sessions are required")
    expected_actors=json.loads(private_path(api).read_text(encoding="utf-8"))
    completed=None
    for path in sorted(api.output.glob("questions*.json"),key=lambda p:p.stat().st_mtime,reverse=True):
        candidates=[x for x in json.loads(path.read_text(encoding="utf-8"))["checks"]
                    if x.get("passed") and x["task"]["status"]=="COMPLETED"]
        if candidates:
            completed=candidates[0];break
    if completed is None:
        raise ValueError("Run a verified completed question before cross-owner checks")
    for name,client in api.actor_clients.items():
        me=client.call("GET","/api/auth/me")
        assert me.get("id")==expected_actors[name]["id"],"Actor client is authenticated as the wrong identity"
        assert not client.call("GET","/api/iam-s1/capabilities").get("systemAdmin"),"Query actor unexpectedly has administrator privileges"
        assert me.get("passwordChanged"),"Actor still requires its normal first-login password change"
        history=client.call("GET","/api/iam-s1/query/history?pageNo=1&pageSize=10")
        assert history["total"]==0 and not history["records"],"New actor saw another user's query history"
        sources=client.call("GET","/api/iam-s1/query-resources/datasources")
        if name=="no_data":
            assert not sources,"Responsible-source binding incorrectly granted business data"
        else:
            assert len(sources)==1 and sources[0].get("id",sources[0].get("datasourceId"))==1,"Reader lost its positive datasource visibility"
        for path in [f"/api/iam-s1/query/tasks/{completed['taskId']}","/api/admin/business-knowledge/snapshots/1",
                     "/api/iam-s1/data-grants?datasourceId=1","/api/admin/field-semantics/columns/1008?snapshotId=1"]:
            response=client.client.get(path);body=response.json()
            assert response.status_code in {400,403,404} and body.get("data") is None, "Cross-role/owner read unexpectedly succeeded"
            results.append({"actor":name,"path":path,"http":response.status_code,"passed":True})
        results.append({"actor":name,"ownHistoryEmpty":True,"visibleDatasourceCount":len(sources),"passed":True})
    result={"passed":True,"checks":results,"limit":"Admin/query-role and data-grant/owner boundaries; manager/reviewer separation has separate transaction tests"}
    (api.output/"multi-role.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
    return result

def run(api,flow):
    if flow=="setup-actors":return setup(api)
    if flow=="actors-captcha":return captcha(api)
    if flow=="actors-check":return check(api)
    if flow=="actors-logout":
        for client in api.actor_clients.values():client.close()
        return {"loggedOut":True}
    if flow.startswith("actor-login:"):
        _,name,code=flow.split(":",2)
        return login(api,name,code)
    raise ValueError("Unknown actor flow")
