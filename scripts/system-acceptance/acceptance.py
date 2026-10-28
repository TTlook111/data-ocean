"""Current-version acceptance entry. No provisioning, migrations or hidden login bypass.

Reports contain only synthetic data and curated configuration metadata. Credentials
are read from a private dotenv file; access tokens remain in memory and are logged
out on exit. Even a successful question set is not full-project acceptance.
"""
from __future__ import annotations
import argparse
from collections import Counter
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import sqlite3
import subprocess
import sys
import time
import tempfile
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
APP_SCHEMA = "dataocean_e_acceptance_20260927"
FIXTURE_SCHEMA = "langgraph_fixture_e_20260927"
SECONDARY_SCHEMA = "langgraph_fixture_switch_20261028"
TERMINAL = {"COMPLETED", "FAILED", "CANCELLED", "REJECTED", "CLARIFICATION_REQUIRED"}

def dump(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding="utf-8")

def action_label(command):
    action=command.get("flow") or ("questions" if command.get("runQuestions") else
        "login" if "loginCaptcha" in command else "captcha" if command.get("newCaptcha") else command.get("path","command"))
    return ":".join(action.split(":")[:2]) if isinstance(action,str) and action.startswith("actor-login:") else action

def local_url(value):
    parsed = urlsplit(value)
    if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost"} or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Acceptance only permits a loopback HTTP endpoint without credentials")
    return value.rstrip("/")

def private_config(path):
    from dotenv import dotenv_values
    cfg = dotenv_values(path)
    if cfg.get("LANGGRAPH_REVIEW_APP_SCHEMA") != APP_SCHEMA or cfg.get("LANGGRAPH_REVIEW_FIXTURE_SCHEMA") != FIXTURE_SCHEMA:
        raise ValueError("Refusing a non-isolated application or fixture database")
    return cfg

def assert_read_only_grants(grants, schema=FIXTURE_SCHEMA, tables=("products","sales_orders")):
    allowed = {f"`{schema}`.`{table}`" for table in tables}
    actual = set()
    for grant in grants:
        if re.fullmatch(r"GRANT USAGE ON \*\.\* TO .+", grant):
            continue
        match = re.fullmatch(r"GRANT SELECT ON (`[^`]+`\.`[^`]+`) TO .+", grant)
        if not match or match[1] not in allowed:
            raise ValueError("Fixture account is not restricted to the declared synthetic read-only tables")
        actual.add(match[1])
    if actual != allowed:
        raise ValueError("Fixture SELECT grants are incomplete")

def connect(cfg, fixture=False):
    import pymysql
    return pymysql.connect(host="127.0.0.1", port=3306,
        database=FIXTURE_SCHEMA if fixture else APP_SCHEMA,
        user=cfg["LANGGRAPH_REVIEW_FIXTURE_USER" if fixture else "LANGGRAPH_REVIEW_MYSQL_ROOT_USER"],
        password=cfg["LANGGRAPH_REVIEW_FIXTURE_PASSWORD" if fixture else "LANGGRAPH_REVIEW_MYSQL_ROOT_PASSWORD"],
        connect_timeout=5, read_timeout=10)

def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True, encoding="utf-8").strip()

def inventory():
    endpoints, workers = [], []
    for source in sorted((ROOT / "backend/DataOcean/src/main/java").rglob("*.java")):
        text = source.read_text(encoding="utf-8")
        if "@RestController" in text:
            mappings = [{"line": text[:match.start()].count("\n") + 1,
                         "annotation": match.group(0)} for match in re.finditer(
                             r"@(Get|Post|Put|Patch|Delete|Request)Mapping(?:\s*\([^\n]*(?:\n[^@]*?\))?)?", text)]
            endpoints.append({"source": str(source.relative_to(ROOT)), "mappings": mappings})
        for match in re.finditer(r"@Scheduled\([^\n]+", text):
            workers.append({"source": str(source.relative_to(ROOT)), "line": text[:match.start()].count("\n") + 1,
                            "schedule": match.group(0)})
    navigation = (ROOT / "frontend/src/router/adminNavigation.ts").read_text(encoding="utf-8")
    return {"branch": git("branch", "--show-current"), "commit": git("rev-parse", "HEAD"),
            "worktree": git("status", "--porcelain=v1").splitlines(),
            "controllers": endpoints, "workers": workers,
            "frontend_paths": sorted(set(re.findall(r"['\"](/admin/[^'\"]+)['\"]", navigation))),
            "inventory_limit": "Static annotation locations, not proof every route or worker ran"}

def secondary_connection(profile):
    import pymysql
    if profile.get("schema") != SECONDARY_SCHEMA or profile.get("username") != "acc_switch_reader_20261028":
        raise ValueError("Secondary source is not the declared isolated synthetic fixture")
    return pymysql.connect(host="127.0.0.1",port=3306,database=SECONDARY_SCHEMA,
        user=profile["username"],password=profile["password"],connect_timeout=5,read_timeout=10)

def preflight(cfg, secondary=None):
    report = {"application_schema": APP_SCHEMA, "fixture_schema": FIXTURE_SCHEMA}
    versions = sorted(int(p.name.split("__")[0][1:]) for p in (ROOT / "backend/DataOcean/src/main/resources/db/migration").glob("V*__*.sql"))
    assert 53 not in versions
    report["code_migration"] = max(versions)
    with connect(cfg) as db, db.cursor() as cur:
        cur.execute("SET SESSION TRANSACTION READ ONLY")
        cur.execute("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1")
        report["database_migration"] = int(cur.fetchone()[0])
        cur.execute("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0")
        report["failed_migrations"] = cur.fetchone()[0]
        cur.execute("SELECT id,host,port,database_name FROM datasource WHERE deleted=0")
        targets = cur.fetchall()
        permitted = {FIXTURE_SCHEMA}
        if secondary:
            with secondary_connection(secondary) as fixture, fixture.cursor() as reader:
                reader.execute("SET SESSION TRANSACTION READ ONLY")
                reader.execute("SHOW GRANTS FOR CURRENT_USER()")
                assert_read_only_grants([r[0] for r in reader.fetchall()],SECONDARY_SCHEMA,("products",))
            permitted.add(SECONDARY_SCHEMA)
        if not targets or any(row[1] not in {"localhost", "127.0.0.1"} or row[2] != 3306 or row[3] not in permitted for row in targets):
            raise ValueError("Datasource inventory includes a non-fixture target; no live actions permitted")
        report["datasources"] = [{"id": r[0], "database": r[3]} for r in targets]
        cur.execute("SELECT config_key,config_value FROM sys_config WHERE config_key IN ('ai.llm.model','ai.embedding.model','ai.embedding.dimension')")
        report["models"] = dict(cur.fetchall())
        cur.execute("SELECT build_id,datasource_id,source_snapshot_id,collection_name,status FROM rag_index_build ORDER BY created_at DESC LIMIT 10")
        report["builds"] = [dict(zip(["build", "datasource", "snapshot", "collection", "status"], row)) for row in cur.fetchall()]
    with connect(cfg, fixture=True) as db, db.cursor() as cur:
        cur.execute("SET SESSION TRANSACTION READ ONLY")
        cur.execute("SHOW GRANTS FOR CURRENT_USER()")
        assert_read_only_grants([r[0] for r in cur.fetchall()])
        report["fixture_grants_verified"] = True
    report["passed"] = report["database_migration"] == report["code_migration"] and report["failed_migrations"] == 0
    return report

def scalar(value):
    if value is None:
        return ("null", "")
    if isinstance(value, bool):
        return ("bool", str(value))
    try:
        return ("number", Decimal(str(value)).normalize())
    except (InvalidOperation, ValueError):
        return ("text", str(value))

def rows_match(actual, expected):
    # Compare multiplicities and projection order. Do not sort values within a
    # row: that would hide swapped metrics/dimensions or missing duplicates.
    return Counter(tuple(scalar(v) for v in row) for row in actual) == Counter(tuple(scalar(v) for v in row) for row in expected)

def leaked_identifiers(evidence, forbidden):
    text = json.dumps(evidence, ensure_ascii=False).casefold()
    return sorted(name for name in forbidden if re.search(r"(?<![\w])" + re.escape(name.casefold()) + r"(?![\w])", text))

def target_fingerprints_match(expected_sources, actual_sources, expected_builds, actual_builds):
    for source in [*expected_sources,*actual_sources]:
        if (type(source.get("id")) is not int or not source.get("host")
                or type(source.get("port")) is not int
                or not source.get("databaseName",source.get("database"))):
            return False
    sources=lambda rows:{(r["id"],r["host"],r["port"],r.get("databaseName",r.get("database"))) for r in rows}
    builds=lambda rows:{(r.get("buildId",r.get("build")),r.get("datasourceId",r.get("datasource")),
                        r.get("sourceSnapshotId",r.get("snapshot"))) for r in rows if r.get("status")=="ACTIVE"}
    return sources(expected_sources)==sources(actual_sources) and builds(expected_builds)==builds(actual_builds)

def oracle(cfg, question_set):
    checks = []
    with connect(cfg, fixture=True) as db, db.cursor() as cur:
        cur.execute("SET SESSION TRANSACTION READ ONLY")
        cur.execute("SHOW GRANTS FOR CURRENT_USER()")
        assert_read_only_grants([r[0] for r in cur.fetchall()])
        for case in question_set["questions"]:
            if case["kind"] != "rows":
                continue
            cur.execute(case["sql"])
            rows = [list(row) for row in cur.fetchall()]
            checks.append({"id": case["id"], "passed": rows_match(rows, case["expected"]), "rows": rows,
                           "expected": case["expected"], "oracle_sql": case["sql"]})
    return {"kind": "real-mysql-independent-oracle", "checks": checks, "passed": all(c["passed"] for c in checks)}

def protected_attempt_evidence(cfg, task):
    trace = task.get("sourceTrace") or {}
    with connect(cfg) as db, db.cursor() as cur:
        cur.execute("SET SESSION TRANSACTION READ ONLY")
        cur.execute("SELECT task_id,attempt_id,sql_hash,safe_sql,status,protected_data,protected_columns,source_trace,row_count FROM query_attempt WHERE task_id=%s AND attempt_id=%s",
                    (task["taskId"], trace.get("attemptId")))
        row = cur.fetchone()
        if not row:
            return {"verified": False, "reason": "No matching protected attempt exists"}
        task_id, attempt, digest, sql, status, data, columns, entries, row_count = row
        cur.execute("SELECT trace_projection_status,field_semantic_refs_json,business_knowledge_refs_json,reviewed_knowledge_refs_json FROM query_task WHERE task_id=%s", (task["taskId"],))
        state = cur.fetchone()
        if any(not isinstance(value, str) or not value.strip() for value in (data, columns, entries, sql, digest)):
            return {"verified": False, "reason": "Persisted protected data/columns/source evidence is absent"}
        try:
            protected_data, protected_columns, source_entries = json.loads(data), json.loads(columns), json.loads(entries)
        except (TypeError, ValueError):
            return {"verified": False, "reason": "Persisted protected evidence is malformed"}
        if (not isinstance(protected_data, list) or not all(isinstance(r, dict) for r in protected_data)
                or not isinstance(protected_columns, list) or not protected_columns
                or not all(isinstance(c, dict) and c.get("name") for c in protected_columns)
                or not isinstance(source_entries, list) or not source_entries):
            return {"verified": False, "reason": "Persisted protection evidence has no usable schema or source trace"}
        verified = task_id == task["taskId"] and attempt == trace.get("attemptId") and digest == trace.get("sqlHash") and sql == task.get("sql") and status == "PROTECTED"
        verified = verified and type(row_count) is int and row_count == len(protected_data) and result_count_matches(task) and row_count == task["rowCount"]
        verified = verified and source_entries == trace.get("entries") and protected_columns == task.get("columns")
        names = [c["name"] for c in task.get("columns") or []]
        verified = verified and rows_match([[r.get(n) for n in names] for r in protected_data], [[r.get(n) for n in names] for r in task.get("data") or []])
        return {"verified": verified, "attemptId": attempt, "sqlHash": digest, "status": status,
            "rowCount":row_count,"protectedDataLength":len(protected_data),
            "safeSql": sql, "traceProjectionStatus": state[0] if state else None,
            "fieldSemanticReferences": json.loads(state[1] or "[]") if state else [],
            "businessKnowledgeReferences": json.loads(state[2] or "[]") if state else [],
            "reviewedKnowledgeReferences": json.loads(state[3] or "[]") if state else [],
            "limit": "SQL literals are redacted; original SQL hash is linked to the protected execution receipt, not recomputed from safeSql"}

def result_count_matches(task):
    return (isinstance(task.get("data"),list) and type(task.get("rowCount")) is int
            and task["rowCount"]==len(task["data"]))


def projection_rows_match(task, tables, columns, audits):
    if not result_count_matches(task):
        return False
    expected_tables={(name,"USAGE") for name in task.get("usedTables") or []}
    expected_columns={(field.split(".",1)[0],field.split(".",1)[1],None) for field in task.get("usedColumns") or []}
    for item in (task.get("sourceTrace") or {}).get("entries",[]):
        for field in item.get("sources",[]):
            table,column=field.split(".",1)
            expected_columns.add((table,column,item["outputColumn"]))
    if len(tables)!=len(set(tables)) or set(tables)!=expected_tables:
        return False
    if len(columns)!=len(set(columns)) or set(columns)!=expected_columns or len(audits)!=1:
        return False
    sql,row_count,success=audits[0]
    return sql==task.get("sql") and row_count==task.get("rowCount") and bool(success)

def projection_evidence(cfg, task):
    deadline=time.monotonic()+10
    while True:
        with connect(cfg) as db,db.cursor() as cur:
            cur.execute("SET SESSION TRANSACTION READ ONLY")
            cur.execute("SELECT id,trace_projection_status FROM query_task WHERE task_id=%s AND status='COMPLETED'",(task["taskId"],))
            state=cur.fetchone()
            if state and state[1]=="COMPLETED":
                cur.execute("SELECT source_table,relation_type FROM query_lineage_table WHERE query_task_id=%s",(state[0],));tables=cur.fetchall()
                cur.execute("SELECT source_table,source_column,alias_name FROM query_lineage_column WHERE query_task_id=%s",(state[0],));columns=cur.fetchall()
                cur.execute("SELECT sql_text,row_count,is_success FROM query_audit_log WHERE query_task_id=%s",(state[0],));audits=cur.fetchall()
                return {"verified":projection_rows_match(task,tables,columns,audits),"queryTaskRowId":state[0],"status":state[1],
                        "tableUsageRows":tables,"columnUsageAndOutputRows":columns,"auditCount":len(audits)}
        if time.monotonic()>=deadline:
            return {"verified":False,"reason":"Trace projection not completed within observation window"}
        time.sleep(.25)

def compare_task(case, task, attempt_evidence=None):
    status = task.get("status")
    problems = []
    if case["kind"] == "rows":
        if status != "COMPLETED":
            return ["Expected COMPLETED, received " + str(status)]
        if task.get("finalProtectionStatus") not in {"FINAL_PROTECTED", "FINAL_MASKED"}:
            problems.append("Result lacks final Java protection")
        if not task.get("ragBuildId") or not task.get("sourceTrace"):
            problems.append("Result lacks pinned build or source evidence")
        trace = task.get("sourceTrace") or {}
        if not trace.get("attemptId") or not trace.get("sqlHash"):
            problems.append("Missing protected attempt identity or SQL hash")
        if not task.get("sql") or not attempt_evidence or not attempt_evidence.get("verified"):
            problems.append("SQL, result or source evidence does not match the same task's protected attempt")
        if not result_count_matches(task):
            problems.append("Reported result count disagrees with the actual result array")
        if not attempt_evidence or not attempt_evidence.get("projection",{}).get("verified"):
            problems.append("Committed audit and query-lineage projection is missing or inconsistent")
        names = [column["name"] for column in task.get("columns") or []]
        if len(names) != len(set(names)) or not names:
            problems.append("Result column identities are missing or duplicate")
        actual = [[row.get(name) for name in names] for row in task.get("data") or []]
        if not rows_match(actual, case["expected"]):
            problems.append("Result values or projection order disagree with independent expectation")
        if set(task.get("usedTables") or []) != set(case["tables"]):
            problems.append("Actual table sources disagree with expected scope")
    else:
        allowed = {"CLARIFICATION_REQUIRED"} if case["kind"] == "clarification" else {"CLARIFICATION_REQUIRED", "REJECTED"}
        if status not in allowed:
            problems.append("Expected a business clarification or refusal, received " + str(status))
        if task.get("data") or task.get("sql"):
            problems.append("Refusal exposed SQL or result rows")
        if not task.get("errorMessage") and not task.get("progressMessage"):
            problems.append("Refusal/clarification has no reason")
    return problems

class Api:
    def __init__(self, cfg, base, output):
        import httpx
        self.client = httpx.Client(base_url=local_url(base), timeout=30, trust_env=False)
        self.cfg, self.output, self.authenticated = cfg, output, False
        self.records = []
        self.target_verified=False
        self.verify_target_on_login=True

    def call(self, method, path, body=None, expected=200):
        if not path.startswith("/api/") or ".." in path or "://" in path:
            raise ValueError("Only official relative /api/ routes are allowed")
        if (method.upper() not in {"GET","HEAD","OPTIONS"} and not self.target_verified
                and path not in {"/api/auth/login","/api/auth/logout","/api/auth/password"}):
            raise ValueError("API target must be verified against the isolated database before any business mutation")
        response = self.client.request(method, path, json=body)
        result = response.json()
        self.records.append({"method": method, "path": path, "http": response.status_code, "code": result.get("code")})
        if response.status_code != expected or (expected == 200 and result.get("code") != 200):
            raise ValueError(f"{method} {path}: HTTP {response.status_code}, code {result.get('code')}, {result.get('message')}")
        return result.get("data")

    def captcha(self):
        import base64
        challenge = self.call("GET", "/api/auth/captcha")
        self.captcha_key = challenge["captchaKey"]
        image = self.output / "login-captcha.png"
        image.write_bytes(base64.b64decode(challenge["captchaImage"].split(",")[-1]))
        return str(image)

    def login(self, code):
        data = self.call("POST", "/api/auth/login", {
            "username": self.cfg["LANGGRAPH_ACCEPTANCE_READER_USERNAME"],
            "password": self.cfg["LANGGRAPH_ACCEPTANCE_READER_PASSWORD"],
            "captchaKey": self.captcha_key, "captchaCode": code})
        token = data.get("accessToken") or data.get("token")
        if not token:
            raise ValueError("Login did not return an access token")
        self.client.headers["Authorization"] = "Bearer " + token
        self.authenticated = True
        if self.verify_target_on_login:
            self.verify_target()
        return {"authenticated": True}

    def verify_target(self):
        with connect(self.cfg) as db,db.cursor() as cur:
            cur.execute("SET SESSION TRANSACTION READ ONLY")
            cur.execute("SELECT id,host,port,database_name FROM datasource WHERE deleted=0")
            sources=[dict(zip(("id","host","port","databaseName"),r)) for r in cur.fetchall()]
            cur.execute("SELECT build_id,datasource_id,source_snapshot_id,status FROM rag_index_build WHERE status='ACTIVE'")
            builds=[dict(zip(("buildId","datasourceId","sourceSnapshotId","status"),r)) for r in cur.fetchall()]
            cur.execute("SELECT id FROM sys_user WHERE username=%s AND deleted=0",(self.cfg["LANGGRAPH_ACCEPTANCE_READER_USERNAME"],))
            user=cur.fetchone()
        actual_sources=self.call("GET","/api/admin/datasources?pageNo=1&pageSize=100")
        actual_builds=[]
        for source in sources:
            actual_builds.extend(self.call("GET",f"/api/admin/knowledge-docs/rag-builds?datasourceId={source['id']}"))
        me=self.call("GET","/api/auth/me")
        if (not user or me.get("id")!=user[0] or actual_sources.get("total")!=len(sources)
                or not target_fingerprints_match(sources,actual_sources["records"],builds,actual_builds)):
            raise ValueError("API identity, datasource targets or active builds do not match the declared isolated application database")
        self.target_verified=True
        dump(self.output/"api-target-binding.json",{"verified":True,"userId":me["id"],"datasourceIds":[s["id"] for s in sources],
                                                  "activeBuildIds":[b["buildId"] for b in builds]})

    def questions(self, question_set, datasource=1):
        checks = []
        cases = question_set["questions"]
        expected_ids = [q["id"] for q in cases]
        if not cases or len(expected_ids) != len(set(expected_ids)):
            raise ValueError("Question batch must be nonempty with unique declared IDs")
        run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")

        def save_report():
            complete = [c["id"] for c in checks] == expected_ids
            report = {"kind": "real-services-model-questions", "contract": question_set["contract"],
                "expectedIds": expected_ids, "completedIds": [c["id"] for c in checks],
                "status": "COMPLETED" if complete else "INCOMPLETE", "checks": checks,
                "passed": complete and all(c["passed"] for c in checks), "full_project_acceptance": False}
            dump(self.output / "questions.json", report)
            dump(self.output / ("questions-" + run_id + ".json"), report)

        save_report()
        for case in cases:
            created = self.call("POST", "/api/iam-s1/query/ask", {"protocolVersion": "IAM-SIMPLE-1", "datasourceId": datasource, "question": case["question"]})
            task_id = created["taskId"]
            deadline = time.monotonic() + 120
            while True:
                task = self.call("GET", f"/api/iam-s1/query/tasks/{task_id}")
                if task.get("status") in TERMINAL:
                    break
                if time.monotonic() >= deadline:
                    raise ValueError("Task polling exceeded 120 seconds")
                time.sleep(1)
            attempt_evidence = protected_attempt_evidence(self.cfg, task) if task.get("status") == "COMPLETED" else None
            if attempt_evidence is not None:
                attempt_evidence["projection"]=projection_evidence(self.cfg,task)
            problems = compare_task(case, task, attempt_evidence)
            messages = self.call("GET", f"/api/iam-s1/query/conversations/{created['conversationId']}/messages") if created.get("conversationId") else []
            if isinstance(messages, dict):
                messages = messages.get("records", messages.get("messages", []))
            security_evidence = {"result": {key: task.get(key) for key in ("data", "columns", "usedTables", "usedColumns", "sourceTrace")},
                "assistantHistory": [{"metadata": m.get("metadata")} for m in messages if str(m.get("role", "")).upper() == "ASSISTANT"]}
            leaks = leaked_identifiers(security_evidence, question_set["deniedTables"] + ["phone", "salary"])
            if leaks:
                problems.append("Restricted identifiers leaked in protected result/history metadata: " + ",".join(leaks))
            entry = {"id": case["id"], "taskId": task_id, "passed": not problems, "problems": problems, "task": task,
                     "protectedAttemptEvidence": attempt_evidence}
            checks.append(entry)
            save_report()
            print(json.dumps({"id": case["id"], "status": task.get("status"), "passed": not problems, "problems": problems}), flush=True)
        return {"checks": len(checks), "passed": all(c["passed"] for c in checks)}

    def close(self):
        try:
            for actor in getattr(self,"actor_clients",{}).values():
                actor.close()
            if self.authenticated:
                self.call("POST", "/api/auth/logout")
        finally:
            self.client.headers.pop("Authorization", None)
            self.authenticated = False
            self.client.close()
            dump(self.output / "requests.json", self.records)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=["inventory", "preflight", "oracle", "session"])
    parser.add_argument("--private-env", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--base-url", default="http://127.0.0.1:5173")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    questions = json.loads((HERE / "questions.json").read_text(encoding="utf-8"))
    if args.phase == "inventory":
        report = inventory()
    else:
        if not args.private_env:
            parser.error("--private-env is required; credentials are never command-line arguments")
        cfg = private_config(args.private_env)
        secondary_path=args.output.parent/"system-acceptance-secondary.private.json"
        secondary=json.loads(secondary_path.read_text(encoding="utf-8")) if secondary_path.exists() else None
        report = preflight(cfg,secondary) if secondary else preflight(cfg)
        dump(args.output / "preflight.json", report)
        if not report["passed"]:
            raise SystemExit("Database/code migration mismatch; no live action performed")
        if args.phase == "oracle":
            report = oracle(cfg, questions)
        elif args.phase == "session":
            oracle_report = oracle(cfg, questions)
            dump(args.output / "oracle.json", oracle_report)
            if not oracle_report["passed"]:
                raise SystemExit("Synthetic fixture drift; refusing model calls")
            api = Api(cfg, args.base_url, args.output)
            actions = []
            complete = False
            session_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
            session_path = args.output / ("session-" + session_id + ".json")
            def save_session():
                passed = complete and bool(actions) and all(a["passed"] for a in actions)
                dump(session_path, {"status": "COMPLETED" if complete else "INCOMPLETE",
                    "actions": actions, "passed": passed, "full_project_acceptance": False})
                return passed
            save_session()
            try:
                print(json.dumps({"captcha": api.captcha()}), flush=True)
                for line in sys.stdin:
                    command = json.loads(line)
                    try:
                        if "loginCaptcha" in command:
                            result = api.login(command["loginCaptcha"])
                        elif command.get("newCaptcha"):
                            result = {"captcha": api.captcha()}
                        elif command.get("runQuestions"):
                            selected = command.get("questionIds")
                            batch = dict(questions)
                            if selected is not None:
                                if (not isinstance(selected, list) or not selected or len(selected) != len(set(selected))
                                        or not set(selected) <= {q["id"] for q in questions["questions"]}):
                                    raise ValueError("Unknown question ID; no model call performed")
                                batch["questions"] = [q for q in questions["questions"] if q["id"] in selected]
                            result = api.questions(batch)
                        elif command.get("flow"):
                            import importlib.util
                            spec = importlib.util.spec_from_file_location("acceptance_flows", HERE / "flows.py")
                            module = importlib.util.module_from_spec(spec)
                            spec.loader.exec_module(module)
                            result = module.run(api, command["flow"])
                        elif command.get("exit"):
                            complete = True
                            break
                        else:
                            result = api.call(command.get("method", "GET"), command["path"], command.get("body"), command.get("expected", 200))
                        action = action_label(command)
                        actions.append({"action": action, "passed": not isinstance(result, dict) or result.get("passed") is not False})
                        # Never expose login tokens or configuration credentials.
                        if command.get("path", "").startswith(("/api/auth/login", "/api/admin/ai")):
                            result = {"redacted": True}
                        if command.get("summary"):
                            records = result.get("records") if isinstance(result, dict) and "records" in result else result
                            if isinstance(records, list):
                                result = [{k: row.get(k) for k in ("id", "title", "kind", "knowledgeKey", "status", "rowVersion", "effectiveStatus", "buildId")} if isinstance(row, dict) else row for row in records]
                        print(json.dumps({"result": result}, ensure_ascii=False, default=str), flush=True)
                    except Exception as error:
                        actions.append({"action": action_label(command),
                                        "passed": False, "errorType": type(error).__name__})
                        print(json.dumps({"error": str(error)}, ensure_ascii=False), flush=True)
                    save_session()
            finally:
                try:
                    api.close()
                finally:
                    save_session()
            raise SystemExit(0 if save_session() else 1)
    dump(args.output / (args.phase + ".json"), report)
    print(json.dumps({"phase": args.phase, "passed": report.get("passed"), "output": str(args.output)}, ensure_ascii=False))
    if report.get("passed") is False:
        raise SystemExit(1)

if __name__ == "__main__":
    main()
