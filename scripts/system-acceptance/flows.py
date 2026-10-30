"""Explicit test-only business actions through official APIs, never direct SQL writes."""
import json
import time
from fixture_context import primary_context

CONTRACT = "synthetic-sales-v1"
REFERENCE = "scripts/system-acceptance/questions.json#synthetic-sales-v1"

def record(api, name, value):
    (api.output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding="utf-8")

def fields(api):
    context = primary_context(api)
    sid = context["snapshotId"]
    definitions = [
        ("sales_orders.order_id", "订单编号", "sales_orders.order_id 唯一标识一笔订单，一行一笔。", "笔"),
        ("sales_orders.order_date", "下单日期", "sales_orders.order_date 为业务下单日期；按半开时间区间筛选。", "日"),
        ("sales_orders.region", "区域", "sales_orders.region 是销售区域，测试值 North/South/East。", ""),
        ("sales_orders.product_id", "订单商品编号", "sales_orders.product_id 关联 products.product_id；每笔订单恰好一个商品。", ""),
        ("sales_orders.quantity", "购买数量", "sales_orders.quantity 为该订单商品件数；总数量为 SUM(sales_orders.quantity)。", "件"),
        ("sales_orders.unit_price", "成交单价", "sales_orders.unit_price 为每件成交单价；订单销售额为 quantity*unit_price。", "测试币/件"),
        ("sales_orders.status", "订单状态", "sales_orders.status 的 COMPLETED 表示已完成，RETURNED 表示退货；完成销售额只计 COMPLETED。", ""),
        ("products.product_id", "商品编号", "products.product_id 是商品表唯一主键；一个商品可对应多笔订单。", ""),
        ("products.product_name", "商品名称", "products.product_name 是商品名称。", ""),
        ("products.category", "商品类别", "products.category 是商品分类；Furniture 为家具，Accessories 为配件。", ""),
    ]
    evidence = []
    for qualified, name, meaning, unit in definitions:
        column = context["columns"][qualified]["id"]
        path = f"/api/admin/field-semantics/columns/{column}"
        state = api.call("GET", path + f"/details?snapshotId={sid}")
        approved = state.get("currentApproved") or {}
        if CONTRACT in approved.get("evidence", "") and state.get("effectiveStatus") in {"APPROVED", "QUALIFIED", "VALID", "AI_READY"}:
            evidence.append({"column": column, "state": state, "reused": True})
            continue
        if state.get("workingStatus") == "PENDING_REVIEW":
            state = api.call("POST", path + "/withdraw", {"expectedVersion": state["rowVersion"], "reason": "合成验收创建新修订，原修订保留追溯"})
        state = api.call("PUT", path + "/draft", {
            "snapshotId": sid, "expectedVersion": state.get("rowVersion") or 0,
            "businessName": name, "meaning": meaning, "unit": unit,
            "enumSemantics": "COMPLETED=已完成; RETURNED=退货" if qualified == "sales_orders.status" else "",
            "evidence": CONTRACT + "；依据六笔合成订单与三件商品及独立预期；非真实业务口径",
            "applicability": "仅当前隔离合成数据集", "unresolvedItems": [], "glossaryReviewIds": []})
        state = api.call("POST", path + "/submit", {"expectedVersion": state["rowVersion"]})
        state = api.call("POST", path + "/review", {"expectedVersion": state["rowVersion"],
            "revisionId": state["workingRevisionId"], "action": "APPROVE", "evidenceConfirmed": True,
            "evidenceReference": REFERENCE, "reason": "自动联调按明确合成测试口径执行审核动作；不代表真实业务人工验收"})
        if state.get("currentApprovedRevisionId") is None:
            raise ValueError("Field approval did not create an exact approved revision")
        evidence.append({"column": column, "state": state})
        record(api, "field-governance.json", evidence)
    check = api.call("POST", f"/api/admin/semantic-checks/snapshots/{sid}")
    record(api, "semantic-check.json", check)
    return {"fields": len(evidence), "semanticCheck": check}

def smoke(api):
    context = primary_context(api)
    ds, sid = context["datasourceId"], context["snapshotId"]
    paths = [
        ("auth", "/api/auth/me"),
        ("workbench", "/api/admin/dashboard/stats"),
        ("datasource", "/api/admin/datasources?pageNo=1&pageSize=10"),
        ("readiness", f"/api/admin/datasources/{ds}/readiness"),
        ("metadata", "/api/admin/metadata/snapshots?pageNo=1&pageSize=10"),
        ("quality-rules", "/api/admin/quality-rules?pageNo=1&pageSize=10"),
        ("quality-issues", f"/api/admin/snapshots/{sid}/quality-issues"),
        ("semantic-check", f"/api/admin/semantic-checks/snapshots/{sid}/latest"),
        ("glossary", "/api/admin/glossary"),
        ("knowledge", f"/api/admin/knowledge-docs?datasourceId={ds}&pageSize=100"),
        ("rag", f"/api/admin/knowledge-docs/rag-builds?datasourceId={ds}"),
        ("iam-roles", "/api/iam-s1/roles"),
        ("iam-grants", f"/api/iam-s1/data-grants?datasourceId={ds}"),
        ("query-resources", "/api/iam-s1/query-resources/datasources"),
        ("query-history", "/api/iam-s1/query/history?pageNo=1&pageSize=10"),
        ("query-audit", "/api/admin/audit-logs?pageNo=1&pageSize=10"),
        ("lineage", f"/api/lineage/table/sales_orders?datasourceId={ds}"),
        ("impact", f"/api/lineage/impact/sales_orders?datasourceId={ds}"),
        ("prompts", "/api/admin/prompt-templates/scenes"),
        ("operation-log", "/api/admin/operation-logs?pageNo=1&pageSize=10"),
    ]
    results = []
    for name, path in paths:
        try:
            value = api.call("GET", path)
            results.append({"module": name, "path": path, "passed": True,
                "shape": "list" if isinstance(value, list) else "object",
                "count": len(value) if isinstance(value, list) else value.get("total") if isinstance(value, dict) else None})
        except Exception as error:
            results.append({"module": name, "path": path, "passed": False, "error": str(error)})
        record(api, "module-smoke.json", {"checks": results, "passed": all(r["passed"] for r in results),
            "limit": "Only API smoke under the current actor; HTTP success is not module business acceptance"})
    return {"passed": all(r["passed"] for r in results), "checks": results}

def quality(api):
    sid = primary_context(api)["snapshotId"]
    started = api.call("POST", f"/api/admin/snapshots/{sid}/quality-check", {"snapshotId":sid,"tableNames":["sales_orders","products"]})
    record(api,"source-quality-check.json",started)
    return started

def resume_completed(api):
    import acceptance as entry
    case=None
    for path in sorted(api.output.glob("questions*.json"),key=lambda p:p.stat().st_mtime,reverse=True):
        candidates=[c for c in json.loads(path.read_text(encoding="utf-8"))["checks"] if c.get("passed") and c["task"]["status"]=="COMPLETED"]
        if candidates:case=candidates[0];break
    if not case:raise ValueError("A verified completed task is required")
    task=api.call("GET",f"/api/iam-s1/query/tasks/{case['taskId']}")
    if task["status"]!="COMPLETED":raise ValueError("Task is not completed")
    def counts():
        with entry.connect(api.cfg) as db,db.cursor() as cur:
            cur.execute("SET SESSION TRANSACTION READ ONLY")
            cur.execute("SELECT COUNT(*) FROM query_attempt WHERE task_id=%s",(case["taskId"],));attempts=cur.fetchone()[0]
            cur.execute("SELECT COUNT(*) FROM query_audit_log WHERE query_task_id=(SELECT id FROM query_task WHERE task_id=%s)",(case["taskId"],));audits=cur.fetchone()[0]
            return attempts,audits
    before=counts()
    api.call("POST",f"/api/iam-s1/query/tasks/{case['taskId']}/resume",expected=400)
    after=counts()
    assert before==after and after[1]==1,"Completed-task resume changed execution or audit counts"
    result={"passed":True,"taskId":case["taskId"],"attemptCountBefore":before[0],"attemptCountAfter":after[0],"auditCount":after[1],"resumeRejectedHttp":400}
    record(api,"completed-resume.json",result);return result

def business(api):
    sid = primary_context(api, published=True)["snapshotId"]
    evidence = []
    facts = [
        ("DATASOURCE", "source", "合成销售数据用途", {"description": "三件商品六笔订单的隔离销售测试数据", "scope": "仅合成订单统计；无成本、税费、利润口径"}, ["table:sales_orders", "table:products"]),
        ("TABLE", "orders", "合成订单粒度", {"description": "每笔订单只含一种商品", "grain": "一行一个 order_id，一笔订单对应一个商品"}, ["table:sales_orders"]),
        ("TABLE", "products", "合成商品粒度", {"description": "每个商品唯一一行", "grain": "一行一个 product_id，主键唯一"}, ["table:products"]),
        ("RELATION", "order-product", "合成订单到商品关系", {"description": "订单多对一连接商品", "joinCondition": "sales_orders.product_id = products.product_id", "cardinality": "MANY_TO_ONE", "aggregationNotes": "订单为事实粒度；products.product_id 唯一；SUM订单金额不能因商品维度关联放大。"}, ["table:sales_orders", "table:products", "column:sales_orders.product_id", "column:products.product_id"]),
        ("METRIC", "completed-sales", "合成已完成销售额", {"description": "已完成订单的成交金额，单位为测试币；缺少成本不可推算利润", "formula": "SUM(sales_orders.quantity * sales_orders.unit_price)", "conditions": "sales_orders.status = 'COMPLETED'", "grain": "订单"}, ["table:sales_orders", "column:sales_orders.quantity", "column:sales_orders.unit_price", "column:sales_orders.status"]),
    ]
    rows = api.call("GET", f"/api/admin/business-knowledge/snapshots/{sid}")
    for kind, key, title, body, deps in facts:
        key = "acceptance.synthetic." + key
        previous = [r for r in rows if r["knowledgeKey"] == kind + ":" + key]
        previous = max(previous, key=lambda r: r["id"]) if previous else None
        if previous and previous.get("effectiveStatus") == "VALID" and previous["body"] == body:
            evidence.append(previous)
            continue
        request = {"kind": kind, "knowledgeKey": key, "title": title, "body": body,
            "dependencies": deps, "unresolvedItems": [], "evidenceConfirmed": True,
            "evidenceReference": REFERENCE, "evidence": CONTRACT + "；仅基于独立题集和固定合成数据的测试审核依据"}
        if previous:
            request.update(expectedRevisionId=previous["id"], expectedVersion=previous["rowVersion"])
            source=api.call("GET",f"/api/admin/business-knowledge/{previous['id']}/source-status")
            if source.get("sourceChanged"):
                request["confirmedSourceHash"]=source["currentHash"]
        row = api.call("POST", f"/api/admin/business-knowledge/snapshots/{sid}/drafts", request)
        for action in ["SUBMIT", "APPROVE", "RECHECK"]:
            api.call("POST", f"/api/admin/business-knowledge/{row['id']}/actions", {
                "expectedVersion": row["rowVersion"], "action": action,
                "note": "依据明确合成口径执行" + action + "，不代表真实业务口径确认"})
            current = api.call("GET", f"/api/admin/business-knowledge/snapshots/{sid}")
            row = next(r for r in current if r["id"] == row["id"])
        if row.get("effectiveStatus") != "VALID":
            raise ValueError("Business fact is not valid after exact-revision recheck")
        evidence.append(row)
        record(api, "business-governance.json", evidence)
    return {"facts": [{"id": r["id"], "kind": r["kind"], "rowVersion": r["rowVersion"], "status": r["status"]} for r in evidence]}

def publish(api):
    context = primary_context(api, published=True)
    ds, sid = context["datasourceId"], context["snapshotId"]
    before = api.call("GET", f"/api/admin/knowledge-docs/rag-builds?datasourceId={ds}")
    docs = api.call("POST", f"/api/admin/knowledge-docs/generate-from-snapshot?datasourceId={ds}", {"snapshotId": sid})
    record(api, "generated-documents.json", docs)
    result = []
    for generated in docs:
        doc_id = generated.get("id") or generated.get("docId")
        if not doc_id:
            raise ValueError("Generated document has no persisted identity")
        state = api.call("GET", f"/api/admin/knowledge-docs/{doc_id}")
        if state["status"] != "DRAFT":
            raise ValueError("Newly generated knowledge is not a draft")
        api.call("POST", f"/api/admin/knowledge-docs/{doc_id}/publish", expected=400)
        api.call("POST", f"/api/admin/knowledge-docs/{doc_id}/submit-review")
        api.call("POST", f"/api/admin/knowledge-docs/{doc_id}/approve", {"reason": "合成联调：核对完整表字段、审核来源与五项明确合成业务事实"})
        api.call("POST", f"/api/admin/knowledge-docs/{doc_id}/publish")
        state = api.call("GET", f"/api/admin/knowledge-docs/{doc_id}")
        result.append({"id": doc_id, "version": state["currentVersion"], "status": state["status"]})
    after = api.call("GET", f"/api/admin/knowledge-docs/rag-builds?datasourceId={ds}")
    if before != after:
        raise ValueError("Publishing changed RAG build state before explicit confirmation")
    record(api, "publication.json", {"documents": result, "buildsUnchanged": True})
    return {"documents": result, "buildsUnchanged": True}

def build(api):
    context = primary_context(api, published=True)
    ds, sid = context["datasourceId"], context["snapshotId"]
    # Replace obsolete published inputs through ordinary versioning. Old
    # versions and active/in-flight build collections remain intact.
    generated = json.loads((api.output / "generated-documents.json").read_text(encoding="utf-8"))
    current_ids = {d["id"] for d in generated}
    for doc_id in current_ids:
        doc = api.call("GET", f"/api/admin/knowledge-docs/{doc_id}")
        if doc.get("datasourceId") != ds:
            raise ValueError("Generated document belongs to a different fixture datasource")
        version = api.call("GET", f"/api/admin/knowledge-docs/{doc_id}/versions/{doc['currentVersion']}")
        if version.get("metadataSnapshotId") != sid:
            raise ValueError("Generated document belongs to a different fixture snapshot")
    docs = api.call("GET", f"/api/admin/knowledge-docs?datasourceId={ds}&pageSize=100")["records"]
    retired = []
    for doc in docs:
        if doc["id"] in current_ids or doc["status"] != "PUBLISHED":
            continue
        version = api.call("GET", f"/api/admin/knowledge-docs/{doc['id']}/versions/{doc['currentVersion']}")
        if version.get("metadataSnapshotId") != sid:
            continue
        api.call("PUT", f"/api/admin/knowledge-docs/{doc['id']}", {
            "title": doc["title"], "content": doc["content"] + "\n\n> 此旧文档已由 synthetic-sales-v1 当前主题文档组替代；本版仅作为待修订草稿，旧发布版本保留追溯。\n",
            "version": doc["version"], "changeSummary": "合成验收保留旧版本，当前构建使用已审核新主题组"})
        after = api.call("GET", f"/api/admin/knowledge-docs/{doc['id']}")
        assert after["status"] == "DRAFT" and after["currentVersion"] > doc["currentVersion"]
        retired.append({"docId": doc["id"], "preservedPublishedVersion": doc["currentVersion"], "newDraftVersion": after["currentVersion"]})
    if retired or not (api.output / "superseded-document-inputs.json").exists():
        record(api, "superseded-document-inputs.json", retired)
    created = api.call("POST", "/api/admin/knowledge-docs/rag-builds", {"datasourceId": ds, "snapshotId": sid, "confirmed": True})
    record(api, "rag-build.json", created)
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        rows = api.call("GET", f"/api/admin/knowledge-docs/rag-builds?datasourceId={ds}")
        current = next(r for r in rows if r["buildId"] == created["buildId"])
        record(api, "rag-build.json", current)
        if current["status"] == "ACTIVE":
            if current["actualVectorCount"] != current["expectedChunkCount"]:
                raise ValueError("Activated build count mismatch")
            return current
        if current["status"] in {"FAILED", "CANCELLED", "SUPERSEDED"}:
            raise ValueError("Build did not activate: " + json.dumps(current, ensure_ascii=False))
        time.sleep(2)
    raise ValueError("Build polling exceeded 180 seconds")

def run(api, flow):
    if flow=="injection-check":
        import importlib.util
        path=__import__("pathlib").Path(__file__).with_name("injection_cases.py")
        spec=importlib.util.spec_from_file_location("acceptance_injection_cases",path)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        return module.run(api)
    if flow.startswith("crash-"):
        import importlib.util
        path=__import__("pathlib").Path(__file__).with_name("crash_cases.py")
        spec=importlib.util.spec_from_file_location("acceptance_crash_cases",path)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        return module.run(api,flow.removeprefix("crash-"))
    if flow.startswith("roles-"):
        import importlib.util
        path=__import__("pathlib").Path(__file__).with_name("role_matrix.py")
        spec=importlib.util.spec_from_file_location("acceptance_role_matrix",path)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        return module.run(api,flow)
    if flow in {"secondary-prepare","secondary-questions"}:
        import importlib.util
        path=__import__("pathlib").Path(__file__).with_name("second_source.py")
        spec=importlib.util.spec_from_file_location("acceptance_second_source",path)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        return module.prepare(api) if flow=="secondary-prepare" else module.questions(api)
    if flow.startswith("feedback-"):
        import importlib.util
        path=__import__("pathlib").Path(__file__).with_name("feedback.py")
        spec=importlib.util.spec_from_file_location("acceptance_feedback",path)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        return module.run(api,flow)
    if flow=="sources":
        import importlib.util
        path=__import__("pathlib").Path(__file__).with_name("sources.py")
        spec=importlib.util.spec_from_file_location("acceptance_sources",path)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        return module.run(api)
    if flow in {"setup-actors","actors-captcha","actors-check","actors-logout"} or flow.startswith("actor-login:"):
        import importlib.util
        path=__import__("pathlib").Path(__file__).with_name("actors.py")
        spec=importlib.util.spec_from_file_location("acceptance_actors",path)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        return module.run(api,flow)
    if flow == "questions" or flow.startswith("question-"):
        import importlib.util
        path = __import__("pathlib").Path(__file__).with_name("acceptance.py")
        spec = importlib.util.spec_from_file_location("current_acceptance_runner", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        cases = json.loads(path.with_name("questions.json").read_text(encoding="utf-8"))
        if flow.startswith("question-"):
            cases["questions"] = [q for q in cases["questions"] if q["id"] == flow.split("-", 1)[1]]
            if not cases["questions"]:
                raise ValueError("Unknown declared question")
        context = primary_context(api, published=True)
        for case in cases["questions"]:
            case["expectedSnapshotId"] = context["snapshotId"]
        return module.Api.questions(api, cases, datasource=context["datasourceId"])
    functions = {"smoke": smoke, "quality": quality, "resume-completed":resume_completed,"fields": fields, "business": business, "publish": publish, "build": build}
    if flow not in functions:
        raise ValueError("Unknown declared acceptance flow")
    return functions[flow](api)
