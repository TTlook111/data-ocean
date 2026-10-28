"""Current acceptance contracts; no network, database, model or old B5 scripts."""
from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import sqlite3
from io import StringIO
from types import SimpleNamespace
import pytest

HERE = Path(__file__).resolve().parents[2] / "scripts/system-acceptance"

def runner():
    spec = importlib.util.spec_from_file_location("system_acceptance", HERE / "acceptance.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def test_fixed_questions_have_independent_expected_results_and_refusals():
    cases = json.loads((HERE / "questions.json").read_text(encoding="utf-8"))
    assert len(cases["questions"]) == 12
    assert len({q["id"] for q in cases["questions"]}) == 12
    assert sum(q["kind"] == "rows" for q in cases["questions"]) == 9
    assert sum(q["kind"] == "clarification" for q in cases["questions"]) == 1
    assert sum(q["kind"] == "refusal" for q in cases["questions"]) == 2
    assert set(cases["allowedTables"]).isdisjoint(cases["deniedTables"])
    fixture = json.loads((HERE / "synthetic-data.json").read_text(encoding="utf-8"))
    with sqlite3.connect(":memory:") as db:
        db.execute("CREATE TABLE products(product_id INTEGER PRIMARY KEY,product_name TEXT,category TEXT)")
        db.execute("CREATE TABLE sales_orders(order_id INTEGER PRIMARY KEY,order_date TEXT,region TEXT,product_id INTEGER,quantity INTEGER,unit_price NUMERIC,status TEXT)")
        for table in cases["allowedTables"]:
            for row in fixture[table]:
                db.execute(f"INSERT INTO {table} VALUES ({','.join('?' for _ in row)})", tuple(row.values()))
        for case in cases["questions"]:
            if case["kind"] == "rows":
                assert runner().rows_match(db.execute(case["sql"]).fetchall(), case["expected"]), case["id"]

def test_non_fixture_database_is_rejected_before_any_connection(tmp_path):
    config = tmp_path / "private.env"
    config.write_text("LANGGRAPH_REVIEW_APP_SCHEMA=production\nLANGGRAPH_REVIEW_FIXTURE_SCHEMA=langgraph_fixture_e_20260927\n")
    with pytest.raises(ValueError, match="non-isolated"):
        runner().private_config(config)

@pytest.mark.parametrize("url", ["https://example.test", "http://192.0.2.1", "http://user:password@127.0.0.1", "http://localhost?token=x"])
def test_only_credential_free_loopback_http_endpoints_are_allowed(url):
    with pytest.raises(ValueError):
        runner().local_url(url)

def grants():
    return ["GRANT USAGE ON *.* TO `fixture`@`%`",
            "GRANT SELECT ON `langgraph_fixture_e_20260927`.`products` TO `fixture`@`%`",
            "GRANT SELECT ON `langgraph_fixture_e_20260927`.`sales_orders` TO `fixture`@`%`"]

def test_fixture_account_requires_exact_read_only_table_grants():
    module = runner()
    module.assert_read_only_grants(grants())
    for extra in ["GRANT INSERT ON `langgraph_fixture_e_20260927`.`sales_orders` TO `fixture`@`%`",
                  "GRANT SELECT ON `langgraph_fixture_e_20260927`.* TO `fixture`@`%`",
                  "GRANT SELECT ON *.* TO `fixture`@`%`"]:
        with pytest.raises(ValueError):
            module.assert_read_only_grants(grants() + [extra])

def test_row_comparison_preserves_projection_order_duplicates_and_nulls():
    match = runner().rows_match
    assert match([["Furniture", "180.00"]], [["Furniture", 180]])
    assert not match([[180, "Furniture"]], [["Furniture", 180]])
    assert not match([[1]], [[1], [1]])
    assert not match([[None]], [[0]])

def test_assistant_metadata_leak_is_detected_even_with_no_result_rows():
    evidence = {"protectedResult": {"data": []}, "assistantHistory": [
        {"content": "本轮查询已完成", "metadata": {"note": "hidden phone value"}}]}
    assert runner().leaked_identifiers(evidence, ["salary", "phone"]) == ["phone"]

def test_partial_batch_cannot_leave_a_passing_report(tmp_path, monkeypatch):
    module = runner()
    monkeypatch.setattr(module, "compare_task", lambda *_: [])
    monkeypatch.setattr(module, "protected_attempt_evidence", lambda *_: {"verified": True})
    monkeypatch.setattr(module, "projection_evidence", lambda *_: {"verified": True})
    calls = 0
    def call(method, path, body=None):
        nonlocal calls
        if method == "POST":
            calls += 1
            if calls == 2:
                raise RuntimeError("second task failed before submission")
            return {"taskId": "t1"}
        return {"taskId": "t1", "status": "COMPLETED"}
    fake = SimpleNamespace(output=tmp_path, cfg={}, call=call)
    cases = {"contract": "test", "deniedTables": [], "questions": [
        {"id": "Q01", "question": "q1"}, {"id": "Q02", "question": "q2"}]}
    with pytest.raises(RuntimeError):
        module.Api.questions(fake, cases)
    report = json.loads((tmp_path / "questions.json").read_text(encoding="utf-8"))
    assert report["status"] == "INCOMPLETE" and report["passed"] is False
    assert report["expectedIds"] == ["Q01", "Q02"] and report["completedIds"] == ["Q01"]
    assert report["full_project_acceptance"] is False

@pytest.mark.parametrize("cases", [[], [{"id":"Q1"},{"id":"Q1"}]])
def test_empty_or_duplicate_batches_are_rejected(cases, tmp_path):
    with pytest.raises(ValueError, match="nonempty"):
        runner().Api.questions(SimpleNamespace(output=tmp_path), {"questions": cases})

@pytest.mark.parametrize("tamper", [None, "status", "hash", "sql", "data", "task", "missingData", "missingColumns", "missingTrace", "wrongColumns", "rowCount", "missingRowCount"])
def test_execution_receipt_must_match_task_hash_sql_and_protected_data(tamper, monkeypatch):
    module = runner()
    task = {"taskId":"t1", "status":"COMPLETED", "sql":"SELECT COUNT(*) AS s1_c1 FROM products WHERE category=? LIMIT 10000",
            "columns":[{"name":"s1_c1"}], "data":[{"s1_c1":2}], "rowCount":1,
            "sourceTrace":{"attemptId":"a1","sqlHash":"a"*64,"entries":[{"outputColumn":"s1_c1","sources":[],"sourceKind":"NO_COLUMN_SOURCE"}]}}
    receipt = ["t1","a1","a"*64,task["sql"],"PROTECTED",'[{"s1_c1":2}]','[{"name":"s1_c1"}]',json.dumps(task["sourceTrace"]["entries"]),1]
    if tamper:
        index,value={"status":(4,"EXECUTING"),"hash":(2,"b"*64),"sql":(3,"SELECT 1"),
                     "data":(5,'[{"s1_c1":3}]'),"task":(0,"foreign-task"),"missingData":(5,None),
                     "missingColumns":(6,None),"missingTrace":(7,None),"wrongColumns":(6,'[{"name":"other"}]'),
                     "rowCount":(8,999),"missingRowCount":(8,None)}[tamper]
        receipt[index]=value
    class Cursor:
        def __enter__(self): return self
        def __exit__(self,*_): pass
        def execute(self,*_): pass
        def fetchone(self):
            if getattr(self,"read",False): return ("COMPLETED","[]","[]","[]")
            self.read=True
            return receipt
    class Connection:
        def __enter__(self): return self
        def __exit__(self,*_): pass
        def cursor(self): return Cursor()
    monkeypatch.setattr(module,"connect",lambda *_:Connection())
    assert module.protected_attempt_evidence({},task)["verified"] is (tamper is None)


@pytest.mark.parametrize("outcome,explicit_exit,expected", [(False,True,1),("error",True,1),(True,True,0),(True,False,1)])
def test_session_exit_code_matches_result_and_completion(outcome, explicit_exit, expected, tmp_path, monkeypatch):
    module = runner()
    monkeypatch.setattr(module,"private_config",lambda *_:{})
    monkeypatch.setattr(module,"preflight",lambda *_:{"passed":True})
    monkeypatch.setattr(module,"oracle",lambda *_:{"passed":True})
    class FakeApi:
        def __init__(self,*_): pass
        def captcha(self): return "synthetic.png"
        def questions(self,*_):
            if outcome=="error": raise RuntimeError("synthetic-command-failure")
            return {"passed":outcome}
        def close(self): pass
    monkeypatch.setattr(module,"Api",FakeApi)
    monkeypatch.setattr(module.sys,"argv",["acceptance.py","session","--private-env","private.env","--output",str(tmp_path)])
    commands=[{"runQuestions":True}]+([{"exit":True}] if explicit_exit else [])
    monkeypatch.setattr(module.sys,"stdin",StringIO("\n".join(json.dumps(c) for c in commands)))
    with pytest.raises(SystemExit) as stopped:
        module.main()
    assert stopped.value.code==expected
    report=json.loads(next(tmp_path.glob("session-*.json")).read_text(encoding="utf-8"))
    assert report["passed"] is (expected==0)
    assert report["status"]==("COMPLETED" if explicit_exit else "INCOMPLETE")


def test_projection_requires_one_committed_audit_and_exact_usage_output_sources():
    module=runner()
    task={"sql":"SELECT SUM(quantity) AS s1_c1 FROM orders LIMIT 10000","rowCount":1,
          "usedTables":["orders"],"usedColumns":["orders.quantity"],"data":[{"s1_c1":7}],
          "sourceTrace":{"entries":[{"outputColumn":"s1_c1","sources":["orders.quantity"]}]}}
    tables=[("orders","USAGE")]
    columns=[("orders","quantity",None),("orders","quantity","s1_c1")]
    audit=[(task["sql"],1,True)]
    assert module.projection_rows_match(task,tables,columns,audit)
    assert not module.projection_rows_match(task,tables,columns,audit*2)
    assert not module.projection_rows_match(task,tables*2,columns,audit)
    assert not module.projection_rows_match(task,tables,columns[:1],audit)


def test_count_projection_has_table_usage_and_explicit_column_free_result():
    task={"sql":"SELECT COUNT(*) AS s1_c1 FROM orders LIMIT 10000","rowCount":1,"data":[{"s1_c1":6}],"usedTables":["orders"],"usedColumns":[],
          "sourceTrace":{"entries":[{"outputColumn":"s1_c1","sourceKind":"NO_COLUMN_SOURCE","sources":[]}]}}
    assert runner().projection_rows_match(task,[("orders","USAGE")],[],[(task["sql"],1,True)])


def test_no_sql_view_query_uses_observer_receipt_without_exposing_sql_to_actor():
    module=runner()
    task={"taskId":"t","status":"COMPLETED","sql":None,"canViewSql":False,"columns":[{"name":"s1_c1"}],
          "rowCount":1,"data":[{"s1_c1":1}],"usedTables":["orders"],"usedColumns":[],"ragBuildId":"build",
          "sourceTrace":{"attemptId":"a","sqlHash":"hash","entries":[{"outputColumn":"s1_c1","sources":[],"sourceKind":"NO_COLUMN_SOURCE"}]},
          "finalProtectionStatus":"FINAL_PROTECTED"}
    sql="SELECT COUNT(*) AS s1_c1 FROM orders LIMIT 10000"
    assert module.projection_rows_match(task,[("orders","USAGE")],[],[(sql,1,True)],sql)
    assert not module.projection_rows_match(task,[("orders","USAGE")],[],[("SELECT other",1,True)],sql)
    case={"kind":"rows","tables":["orders"],"expected":[[1]],"expectedCanViewSql":False}
    evidence={"verified":True,"projection":{"verified":True}}
    assert module.compare_task(case,task,evidence)==[]
    assert any("SQL was exposed" in p for p in module.compare_task(case,{**task,"sql":sql},evidence))


def test_declared_row_order_is_checked_separately_from_unordered_value_comparison():
    module=runner()
    task={"status":"COMPLETED","sql":"SELECT id FROM orders ORDER BY id","ragBuildId":"build","finalProtectionStatus":"FINAL_PROTECTED",
          "columns":[{"name":"id"}],"data":[{"id":2},{"id":1}],"rowCount":2,"usedTables":["orders"],
          "sourceTrace":{"attemptId":"a","sqlHash":"hash"}}
    case={"kind":"rows","tables":["orders"],"expected":[[1],[2]],"ordered":True}
    evidence={"verified":True,"projection":{"verified":True}}
    assert any("row order" in p for p in module.compare_task(case,task,evidence))
    assert module.compare_task({**case,"ordered":False},task,evidence)==[]


@pytest.mark.parametrize("reported", [999,None,True,"1"])
def test_projection_cannot_accept_matching_but_false_audit_and_api_counts(reported):
    task={"sql":"SELECT id FROM orders","rowCount":reported,"data":[{"id":1}],"usedTables":["orders"],"usedColumns":[]}
    assert not runner().projection_rows_match(task,[("orders","USAGE")],[],[(task["sql"],reported,True)])


def test_empty_actor_matrix_is_not_role_coverage():
    spec=importlib.util.spec_from_file_location("acceptance_actors",HERE/"actors.py")
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    with pytest.raises(ValueError,match="Both independent"):
        module.check(SimpleNamespace(actor_clients={}))


def test_unrelated_failure_does_not_prove_inflight_knowledge_rejection():
    spec=importlib.util.spec_from_file_location("acceptance_feedback",HERE/"feedback.py")
    module=importlib.util.module_from_spec(spec)
    import sys
    monkey_path=str(HERE)
    sys.path.insert(0,monkey_path)
    try:
        spec.loader.exec_module(module)
    finally:
        sys.path.remove(monkey_path)
    assert not module.knowledge_failure_evidence(("FAILED",None,"AI service unavailable"),0)
    assert not module.knowledge_failure_evidence(("FAILED",None,"查询所引用的知识已变化，请重新提问"),1)
    assert module.knowledge_failure_evidence(("FAILED",None,"查询所引用的知识已变化，请重新提问"),0)


def test_api_target_binding_rejects_wrong_datasource_or_build_identity():
    match=runner().target_fingerprints_match
    sources=[{"id":1,"host":"127.0.0.1","port":3306,"databaseName":"langgraph_fixture_e_20260927"}]
    builds=[{"buildId":"isolated-build","datasourceId":1,"sourceSnapshotId":1,"status":"ACTIVE"}]
    assert match(sources,sources,builds,builds)
    assert not match(sources,[{**sources[0],"databaseName":"different_application"}],builds,builds)
    assert not match(sources,sources,builds,[{**builds[0],"buildId":"unrelated-build"}])
    for missing in ("host","port","databaseName","id"):
        assert not match(sources,[{k:v for k,v in sources[0].items() if k!=missing}],builds,builds)
