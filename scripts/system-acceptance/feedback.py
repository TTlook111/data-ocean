"""Synthetic negative feedback, exact revision repair and original-scenario replay."""
import json
import time
from decimal import Decimal
from pathlib import Path
import acceptance as entry
from fixture_context import primary_context

def save(api,state):
    (api.output/"feedback.json").write_text(json.dumps(state,ensure_ascii=False,indent=2,default=str),encoding="utf-8")

def load(api):
    return json.loads((api.output/"feedback.json").read_text(encoding="utf-8"))

def knowledge_failure_evidence(row,total_attempts):
    return (row[0] in {"FAILED","REJECTED"} and not row[1] and total_attempts==0
            and row[2] in {"查询所引用的知识已变化，请重新提问", "任务引用的字段知识已失效，请重新发起问数",
                           "查询所引用的知识已变化，请重新发起"})

def completed_case(api,case_id):
    for path in sorted(api.output.glob("questions*.json"),key=lambda p:p.stat().st_mtime,reverse=True):
        rows=[x for x in json.loads(path.read_text(encoding="utf-8"))["checks"] if x["id"]==case_id and x.get("passed")]
        if rows:return rows[0]
    raise ValueError("No verified original scenario result")

def query_actor(api):
    """Task reads/feedback/asks stay with its normally authenticated owner."""
    return getattr(api, "query_api", api)


def unit_price_context(api, state=None):
    current = primary_context(api, published=True)
    identity = {"datasourceId": current["datasourceId"], "snapshotId": current["snapshotId"],
                "columnMetaId": current["columns"]["sales_orders.unit_price"]["id"]}
    if state is not None and any(state.get(key) != value for key, value in identity.items()):
        raise ValueError("Feedback fixture context changed; refusing a different source, snapshot or column")
    return identity


def start(api):
    context = unit_price_context(api)
    query = query_actor(api)
    if (api.output/"feedback.json").exists():
        state = load(api)
        unit_price_context(api, state)
        return state
    original=completed_case(api,"Q03")
    task=original["taskId"]
    targets=query.call("GET",f"/api/iam-s1/query/tasks/{task}/feedback-targets")
    target=next((t for t in targets if t.get("columnMetaId")==context["columnMetaId"]),None)
    if not target:raise ValueError("Unit-price field has no actual-use evidence in this task")
    feedback=query.call("POST",f"/api/iam-s1/query/tasks/{task}/feedback",{"feedbackType":"DISLIKE","reasonCode":"FIELD_MEANING",
        "columnMetaId":context["columnMetaId"],"comment":"仅合成流程验收：成交单价解释需明确测试口径不含税费、运费；原题金额预期仍为180.00，不代表真实业务用户发现数值错误。"})
    state={**context,"originalTaskId":task,"feedbackId":feedback["id"],"oldRevisionId":target["revisionId"],"expectedResult":"180.00","stage":"SUBMITTED"}
    save(api,state)
    body={"expectedVersion":feedback["rowVersion"],"approved":True,"columnMetaId":context["columnMetaId"],"reasonCode":"FIELD_MEANING",
          "scenario":original["task"]["question"],"expectedResult":"180.00","note":"合成情景确认：新增单价费用边界解释，保留原题与预期；此操作不是企业真实业务验收。"}
    api.call("POST",f"/api/admin/governance-feedback/{feedback['id']}/review",body)
    api.call("POST",f"/api/admin/governance-feedback/{feedback['id']}/review",body,expected=409)
    own=query.call("GET",f"/api/iam-s1/query/tasks/{task}/feedback")
    assert own["status"]=="APPLIED" and own["followupStatus"]=="QUEUED"
    with entry.connect(api.cfg) as db,db.cursor() as cur:
        cur.execute("SET SESSION TRANSACTION READ ONLY")
        cur.execute("SELECT COUNT(*) FROM governance_confidence_event WHERE feedback_id=%s AND active=1",(feedback["id"],))
        count=cur.fetchone()[0]
    assert count==1,"Repeated review produced duplicate active contributions"
    state.update(stage="APPLIED",feedback=own,activeContributions=count,repeatedReviewRejected=True)
    save(api,state)
    return state

def revise(api):
    state=load(api)
    context=unit_price_context(api,state)
    query=query_actor(api)
    if state.get("newRevisionId"):return finish_recheck(api,state)
    path=f"/api/admin/field-semantics/columns/{context['columnMetaId']}"
    workspace=api.call("GET",path+f"/details?snapshotId={context['snapshotId']}")
    old=workspace["currentApproved"]
    assert old and old["id"]==state["oldRevisionId"]
    # A real in-flight query pins the old revision before it is invalidated.
    question=json.loads((Path(__file__).with_name("questions.json")).read_text(encoding="utf-8"))["questions"][2]["question"]
    pending=query.call("POST","/api/iam-s1/query/ask",{"protocolVersion":"IAM-SIMPLE-1","datasourceId":context["datasourceId"],"question":question})
    pinned=False
    for _ in range(50):
        with entry.connect(api.cfg) as db,db.cursor() as cur:
            cur.execute("SET SESSION TRANSACTION READ ONLY")
            cur.execute("SELECT field_semantic_refs_json,status FROM query_task WHERE task_id=%s",(pending["taskId"],))
            refs,status=cur.fetchone()
        if refs and str(state["oldRevisionId"]) in {str(r["factSourceIds"][1]) for r in json.loads(refs)}:
            pinned=True;break
        time.sleep(.1)
    assert pinned and status=="PROCESSING","Could not establish the intended in-flight revision window"
    draft={"snapshotId":context["snapshotId"],"expectedVersion":workspace["rowVersion"],"businessName":old["businessName"],
        "meaning":old["meaning"]+" 合成验收修订：本测试成交单价不包含税费和运费。",
        "unit":old.get("unit"),"currency":old.get("currency"),"enumSemantics":old.get("enumSemantics"),
        "metricDefinition":old.get("metricDefinition"),"applicability":old.get("applicability"),
        "evidence":"synthetic-sales-v1 feedback-boundary-v2；六笔合成订单、原情景预期180.00不变。",
        "unresolvedItems":[],"glossaryReviewIds":[]}
    changed=api.call("PUT",path+"/draft",draft)
    changed=api.call("POST",path+"/submit",{"expectedVersion":changed["rowVersion"]})
    changed=api.call("POST",path+"/review",{"expectedVersion":changed["rowVersion"],"revisionId":changed["workingRevisionId"],
        "action":"APPROVE","reason":"仅合成验收明确费用边界，不能把程序确认当成真实业务验真",
        "evidenceReference":"scripts/system-acceptance/questions.json#feedback-boundary-v2","evidenceConfirmed":True})
    state.update(stage="REVISED",newRevisionId=changed["currentApprovedRevisionId"],inFlightTaskId=pending["taskId"],oldRevisionWasPinned=True)
    save(api,state)
    return finish_recheck(api,state)

def finish_recheck(api,state):
    context=unit_price_context(api,state)
    query=query_actor(api)
    tags=api.call("GET",f"/api/field-tags/column/{context['columnMetaId']}")
    if not any(t.get("tagCode")=="AMOUNT" for t in tags):
        # This is the existing, semantically correct price/amount category;
        # do not invent a placeholder label merely to open the progress gate.
        tag=api.call("POST","/api/field-tags",{"columnMetaId":context["columnMetaId"],"tagCode":"AMOUNT"})
        state["amountClassification"]=tag
    check=api.call("POST",f"/api/admin/semantic-checks/snapshots/{context['snapshotId']}")
    own=query.call("GET",f"/api/iam-s1/query/tasks/{state['originalTaskId']}/feedback")
    assert own["followupStatus"]=="AWAITING_VERIFICATION","Repair has not reached the exact revision recheck stage"
    for _ in range(120):
        with entry.connect(api.cfg) as db,db.cursor() as cur:
            cur.execute("SET SESSION TRANSACTION READ ONLY")
            cur.execute("SELECT status,result_data,error_message FROM query_task WHERE task_id=%s",(state["inFlightTaskId"],));row=cur.fetchone()
            cur.execute("SELECT COUNT(*) FROM query_attempt WHERE task_id=%s AND status IN ('EXECUTING','PROTECTED')",(state["inFlightTaskId"],));executed=cur.fetchone()[0]
            cur.execute("SELECT COUNT(*) FROM query_attempt WHERE task_id=%s",(state["inFlightTaskId"],));total_attempts=cur.fetchone()[0]
        if row[0] in entry.TERMINAL:break
        time.sleep(1)
    assert knowledge_failure_evidence(row,total_attempts),"In-flight result does not prove rejection caused by knowledge invalidation before attempt creation"
    state.update(stage="AWAITING_VERIFICATION",feedback=own,semanticCheck=check,
        inFlight={"status":row[0],"error":row[2],"hasResult":bool(row[1]),"executingOrProtectedAttempts":executed,
                  "attemptRecordCount":total_attempts,"knowledgeInvalidationCauseConfirmed":True})
    save(api,state)
    return state

def close(api):
    state=load(api)
    unit_price_context(api,state)
    query=query_actor(api)
    replay=completed_case(api,"Q03")
    assert replay["taskId"]!=state["originalTaskId"] and replay.get("protectedAttemptEvidence",{}).get("verified")
    refs=replay["protectedAttemptEvidence"]["fieldSemanticReferences"]
    assert str(state["newRevisionId"]) in {str(r["factSourceIds"][1]) for r in refs},"Replay did not use the repaired revision"
    own=query.call("GET",f"/api/iam-s1/query/tasks/{state['originalTaskId']}/feedback")
    if own["followupStatus"]=="VERIFIED" and state.get("passed"):
        return state
    names=[c["name"] for c in replay["task"]["columns"]]
    assert len(names)==1 and len(replay["task"]["data"])==1
    actual=str(Decimal(str(replay["task"]["data"][0][names[0]])).quantize(Decimal("0.01")))
    assert actual==state["expectedResult"],"Original expected result cannot be changed to close feedback"
    api.call("POST",f"/api/admin/governance-feedback/{state['feedbackId']}/verification",{
        "expectedVersion":own["rowVersion"],"targetRevisionId":state["newRevisionId"],"actualResult":actual,
        "evidenceReference":"synthetic original-scenario replay task "+replay["taskId"],"confirmed":True})
    after=query.call("GET",f"/api/iam-s1/query/tasks/{state['originalTaskId']}/feedback")
    assert after["followupStatus"]=="VERIFIED"
    state.update(stage="VERIFIED",feedback=after,replayTaskId=replay["taskId"],passed=True,
        limit="Programmatic synthetic-scenario verification; not real business human acceptance")
    save(api,state);return state

def run(api,flow):
    return {"feedback-start":start,"feedback-revise":revise,"feedback-close":close}[flow](api)
