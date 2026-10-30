"""Real native-process crash/restart against the already verified acceptance DB.

Never stops infrastructure or resets durable state. The explicit fault launcher
only pauses one task; SQL, IAM, model, checkpoint and projection remain real.
"""
import json
from pathlib import Path
import subprocess
import time
import queue
import threading
import re
from datetime import datetime

import httpx
import acceptance as entry
from flows import record
import second_source
from control_io import read_control,write_control

MODES={"before-executing","executing-before-sql","after-protected","after-result-settlement","projection"}


def ps(code):
    # Start-Process descendants may retain a pipe handle on Windows. Read the
    # explicit single-line receipt instead of waiting for all descendants' EOF.
    profile=json.loads((entry.ROOT.parent/"DataOcean-runtime"/"crash-runtime-profile.json").read_text(encoding="utf-8"))
    shell=profile["powershell"]
    if not Path(shell).is_file():raise ValueError("Verified PowerShell runtime is missing")
    process=subprocess.Popen([shell,"-NoProfile","-NonInteractive","-Command",code],stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding="utf-8",errors="replace")
    receipts=queue.Queue(maxsize=1)
    threading.Thread(target=lambda:receipts.put(process.stdout.readline()),daemon=True).start()
    try:line=receipts.get(timeout=15)
    except queue.Empty:
        process.terminate()
        raise RuntimeError("Native process operation produced no receipt within 15 seconds")
    if not line:
        process.wait(timeout=10)
        raise RuntimeError("Native acceptance process operation failed: "+process.stderr.read()[-500:])
    return json.loads(line)


def quote(value):return "'"+str(value).replace("'","''")+"'"


def stop_native(kind):
    port=8080 if kind=="java" else 8000
    profile=json.loads((entry.ROOT.parent/"DataOcean-runtime"/"crash-runtime-profile.json").read_text(encoding="utf-8"))
    marker=Path(profile["jar"]).name if kind=="java" and profile.get("jar") else "DataOceanApplication" if kind=="java" else "fault_runtime.py"
    return ps(f"$owned=Get-NetTCPConnection -State Listen -LocalPort {port} -ErrorAction Stop; "
        "$ids=@($owned.OwningProcess | Select-Object -Unique); if ($ids.Count -ne 1) {throw 'Ambiguous listener'}; "
        "$p=Get-CimInstance Win32_Process -Filter ('ProcessId = '+$ids[0]); "
        f"if ($p.CommandLine -notlike {quote('*'+marker+'*')} -or $p.CommandLine -notlike '*DataOcean*') {{throw 'Process is not the owned acceptance app'}}; "
        "Stop-Process -Id $p.ProcessId -ErrorAction Stop; @{stoppedPid=$p.ProcessId;parentPid=$p.ParentProcessId;verified=$true}|ConvertTo-Json -Compress")


def start_native(api,kind,run_id):
    profile=json.loads((api.output.parent/"crash-runtime-profile.json").read_text(encoding="utf-8"))
    runtime=api.output.parent
    assert Path(profile["javaHome"]).is_dir() and Path(profile["python"]).is_file()
    stdout=runtime/"logs"/("crash-"+kind+"-"+run_id+".log")
    stderr=runtime/"logs"/("crash-"+kind+"-"+run_id+".err.log")
    if kind=="java":
        launcher=entry.HERE/"java_runtime.py";assert launcher.is_file()
        args=[str(launcher)]
        executable=profile["python"];directory=entry.ROOT
    else:
        executable=profile["python"];directory=entry.ROOT/"python-service"
        args=[str(entry.HERE/"fault_runtime.py"),"--control",str(api.output/"crash-control.json")]
    array="@("+",".join(quote(a) for a in args)+")"
    code="$env:LANGCHAIN_TRACING_V2='false';$env:DATAOCEAN_GOVERNANCE_WORKER_ENABLED='false';$env:PYTHONIOENCODING='utf-8';"
    code+="$env:NO_PROXY=(@($env:NO_PROXY,'127.0.0.1','localhost','dashscope.aliyuncs.com') | Where-Object {$_}) -join ',';"
    code+=f"$p=Start-Process -FilePath {quote(executable)} -ArgumentList {array} -WorkingDirectory {quote(directory)} -WindowStyle Hidden -RedirectStandardOutput {quote(stdout)} -RedirectStandardError {quote(stderr)} -PassThru; "
    code+="@{processId=$p.Id;started=$true}|ConvertTo-Json -Compress"
    return {**ps(code),"stdout":str(stdout),"stderr":str(stderr)}


def wait_service(port,seconds=50):
    deadline=time.monotonic()+seconds
    path="/health" if port==8000 else "/api/auth/captcha"
    while time.monotonic()<deadline:
        try:
            if httpx.get(f"http://127.0.0.1:{port}"+path,trust_env=False,timeout=2).status_code==200:return
        except httpx.HTTPError:pass
        time.sleep(.25)
    raise RuntimeError(f"Acceptance app {port} did not restart")


def snapshot(api,task_id):
    with entry.connect(api.cfg) as db,db.cursor() as cur:
        cur.execute("SET SESSION TRANSACTION READ ONLY")
        cur.execute("SELECT id,status,trace_projection_status,llm_call_count,embedding_call_count,permission_revision,user_id,datasource_id FROM query_task WHERE task_id=%s",(task_id,))
        r=cur.fetchone();assert r and r[6]==9005 and r[7]==2
        result=dict(zip(("rowId","status","projection","llmCalls","embeddingCalls","permissionRevision","userId","datasourceId"),r))
        cur.execute("SELECT attempt_id,status,sql_hash,row_count FROM query_attempt WHERE task_id=%s ORDER BY attempt_no",(task_id,))
        result["attempts"]=[dict(zip(("attemptId","status","sqlHash","rowCount"),r)) for r in cur.fetchall()]
        cur.execute("SELECT call_id,node_name,status FROM query_model_call WHERE task_id=%s ORDER BY id",(task_id,));result["modelCalls"]=cur.fetchall()
        cur.execute("SELECT count(*) FROM query_audit_log WHERE query_task_id=%s",(result["rowId"],));result["auditCount"]=cur.fetchone()[0]
        cur.execute("SELECT count(*) FROM query_lineage_table WHERE query_task_id=%s",(result["rowId"],));result["tableLineageCount"]=cur.fetchone()[0]
        cur.execute("SELECT count(*) FROM query_lineage_column WHERE query_task_id=%s",(result["rowId"],));result["columnLineageCount"]=cur.fetchone()[0]
        return result


def event_count(api,task_id,event="SQL_INVOKED"):
    path=api.output/"crash-execution-journal.jsonl"
    return sum(json.loads(line).get("taskId")==task_id and json.loads(line).get("event")==event
               for line in path.read_text(encoding="utf-8").splitlines()) if path.exists() else 0


def run(api,mode):
    import importlib
    importlib.reload(entry)
    if mode not in MODES:raise ValueError("Unknown crash window")
    api.verify_target()
    source=second_source.state(api);client=api.expanded_actor_clients["secondary"]
    assert client.target_verified and client.authenticated
    with entry.connect(api.cfg) as db,db.cursor() as cur:
        cur.execute("SET SESSION TRANSACTION READ ONLY");cur.execute("SELECT COUNT(*) FROM query_task WHERE status='PROCESSING'")
        assert cur.fetchone()[0]==0,"Crash exercise requires no unrelated running query"
    run_id=datetime.now().strftime("%Y%m%dT%H%M%S%f");report_name="crash-"+mode+"-"+run_id+".json"
    proof={"mode":mode,"passed":False,"status":"RUNNING","events":[]}
    control=api.output/"crash-control.json";lock_db=None;stopped=set()
    if not control.exists():write_control(control,{"schema":entry.APP_SCHEMA,"armed":False})
    def save(event,**values):
        proof.update(values);proof["events"].append(event);record(api,report_name,proof)
        print(json.dumps({"crashWindow":mode,"event":event,"taskId":proof.get("taskId")}),flush=True)
    save("started")
    try:
        if mode=="projection":
            lock_db=entry.connect(api.cfg);cur=lock_db.cursor()
            cur.execute("LOCK TABLES query_audit_log WRITE")
        created=client.call("POST","/api/iam-s1/query/ask",{"protocolVersion":"IAM-SIMPLE-1","datasourceId":2,
            "question":"当前所选数据源中 products 的 category='SecondSource' 商品有多少条？只返回一行计数。" if mode=="after-result-settlement"
                else "列出 products 中所有记录的 product_id，按 product_id 升序。" if mode=="projection"
                else "products 中所有记录有多少条？只返回一行计数。"})
        task_id=created["taskId"];proof["taskId"]=task_id
        write_control(control,{"schema":entry.APP_SCHEMA,"taskId":task_id,"mode":mode,"armed":mode!="projection"})
        deadline=time.monotonic()+55
        while time.monotonic()<deadline:
            c=read_control(control)
            if mode=="projection":
                # Do not inspect the locked audit table on another connection.
                with entry.connect(api.cfg) as db,db.cursor() as cur:
                    cur.execute("SET SESSION TRANSACTION READ ONLY")
                    cur.execute("SELECT id,status,trace_projection_status FROM query_task WHERE task_id=%s",(task_id,));row=cur.fetchone()
                if row and row[1]=="COMPLETED" and row[2]=="PENDING":
                    with entry.connect(api.cfg) as db,db.cursor() as cur:
                        cur.execute("SET SESSION TRANSACTION READ ONLY")
                        cur.execute("SELECT ID,STATE,INFO FROM information_schema.PROCESSLIST WHERE DB=%s",(entry.APP_SCHEMA,))
                        waiting=[(pid,status) for pid,status,statement in cur.fetchall() if statement
                            and re.search(r"(?:INSERT\s+INTO|FROM)\s+`?query_audit_log\b",statement,re.I)
                            and "query_task_id" in statement.lower() and re.search(r"(?<!\d)"+str(row[0])+r"(?!\d)",statement)
                            and "wait" in (status or "").lower()]
                    if waiting:
                        proof["projectionTransactionBlockedAtAuditDedupOrInsert"]=[{"connectionId":p,"state":s} for p,s in waiting]
                        with entry.connect(api.cfg) as db,db.cursor() as cur:
                            cur.execute("SET SESSION TRANSACTION ISOLATION LEVEL READ UNCOMMITTED")
                            cur.execute("SET SESSION TRANSACTION READ ONLY")
                            cur.execute("SELECT count(*) FROM query_lineage_table WHERE query_task_id=(SELECT id FROM query_task WHERE task_id=%s)",(task_id,))
                            proof["uncommittedLineageTableRows"]=cur.fetchone()[0]
                            cur.execute("SELECT count(*) FROM query_lineage_column WHERE query_task_id=(SELECT id FROM query_task WHERE task_id=%s)",(task_id,))
                            proof["uncommittedLineageColumnRows"]=cur.fetchone()[0]
                        assert proof["uncommittedLineageTableRows"]>0,"Projection transaction had not written lineage before audit wait"
                        assert proof["uncommittedLineageColumnRows"]>0,"Projection query did not exercise nonempty column lineage"
                        break
                if row and row[1] in {"FAILED","TIMEOUT","CLARIFICATION_REQUIRED"}:raise ValueError("Query stopped before projection crash window")
            elif c.get("barrier")==mode:break
            else:
                row=snapshot(api,task_id)
                if row["status"]!="PROCESSING":raise ValueError("Query stopped before requested crash barrier")
            time.sleep(.1)
        else:raise ValueError("Real query did not reach the requested crash window")
        if mode!="projection":proof["before"]=snapshot(api,task_id)
        save("barrier-reached",sqlInvocationsBefore=event_count(api,task_id),
             actualProviderBefore={e:event_count(api,task_id,e) for e in ("MODEL_INVOKED","EMBEDDING_INVOKED","DBAPI_SQL_INVOKED")})
        # Stop Java first, preventing a live SSE error handler from finalizing
        # the task before the Python process dies. No service container is touched.
        proof["javaStop"]=stop_native("java");stopped.add("java")
        if mode!="projection":proof["pythonStop"]=stop_native("python");stopped.add("python")
        if lock_db:
            lock_db.cursor().execute("UNLOCK TABLES");lock_db.close();lock_db=None
        if mode=="projection":
            proof["before"]=snapshot(api,task_id)
            assert proof["before"]["tableLineageCount"]==0 and proof["before"]["columnLineageCount"]==0 and proof["before"]["auditCount"]==0,"Interrupted projection did not roll back atomically"
        save("native-processes-killed")
        if "python" in stopped:
            proof["pythonRestart"]=start_native(api,"python",run_id);wait_service(8000);stopped.remove("python")
        proof["javaRestart"]=start_native(api,"java",run_id);wait_service(8080);stopped.remove("java")
        save("native-processes-restarted")
        api.verify_target()
        if mode=="executing-before-sql":
            response=client.client.post(f"/api/iam-s1/query/tasks/{task_id}/resume");body=response.json()
            assert response.status_code==400 and "执行状态无法安全确认" in body.get("message","")
        elif mode!="projection":client.call("POST",f"/api/iam-s1/query/tasks/{task_id}/resume")
        deadline=time.monotonic()+25
        while time.monotonic()<deadline:
            task=client.call("GET",f"/api/iam-s1/query/tasks/{task_id}")
            if task["status"] in entry.TERMINAL:break
            time.sleep(.2)
        else:raise ValueError("Resumed task did not become terminal")
        if mode=="projection":
            started=time.monotonic();projection_deadline=started+40
            while time.monotonic()<projection_deadline:
                if snapshot(api,task_id)["projection"]=="COMPLETED":break
                time.sleep(.25)
            else:raise ValueError("Default 30s projection recovery worker did not finish within 40s")
            proof["projectionRecoveryObservedMs"]=round((time.monotonic()-started)*1000)
        after=snapshot(api,task_id);count=event_count(api,task_id)
        actual_after={e:event_count(api,task_id,e) for e in ("MODEL_INVOKED","EMBEDDING_INVOKED","DBAPI_SQL_INVOKED")}
        if mode=="executing-before-sql":
            assert task["status"]=="FAILED" and not task.get("data") and count==0 and after["auditCount"]==0
            assert actual_after["DBAPI_SQL_INVOKED"]==0
            assert len(after["attempts"])==1 and after["attempts"][0]["status"] in {"EXECUTING","UNCERTAIN"}
        else:
            evidence=entry.protected_attempt_evidence(api.cfg,task)
            evidence["projection"]=entry.projection_evidence(api.cfg,task,evidence.get("safeSql"))
            case={"kind":"rows","expected":[[1],[2]] if mode=="projection" else [[2]],"ordered":mode=="projection",
                "tables":["products"],"expectedSnapshotId":source["snapshotId"],"expectedBuildId":source["build"]["buildId"]}
            problems=entry.compare_task(case,task,evidence);assert not problems,problems
            assert count==1 and len(after["attempts"])==1
            assert actual_after["DBAPI_SQL_INVOKED"]==1
            if mode in {"after-protected","after-result-settlement","projection"}:
                assert after["llmCalls"]==proof["before"]["llmCalls"]
                assert actual_after==proof["actualProviderBefore"]
                assert after["attempts"][0]["attemptId"]==proof["before"]["attempts"][0]["attemptId"]
                assert after["attempts"][0]["sqlHash"]==proof["before"]["attempts"][0]["sqlHash"]
            # Normal completion and a later duplicate resume never rerun SQL.
            client.call("POST",f"/api/iam-s1/query/tasks/{task_id}/resume",expected=400)
            assert event_count(api,task_id)==1
            proof["protectedAttemptEvidence"]=evidence
        assert after["llmCalls"]>=proof["before"]["llmCalls"] and after["llmCalls"]<=8
        assert after["embeddingCalls"]>=proof["before"]["embeddingCalls"] and after["embeddingCalls"]<=2
        save("completed",status="COMPLETED",passed=True,after=after,task=task,sqlInvocationsAfter=count,
            actualProviderAfter=actual_after,
            limit="Real app process death and existing MySQL/Redis recovery; no infrastructure crash or storage corruption")
        return {"passed":True,"mode":mode,"taskId":task_id,"report":report_name,"sqlInvocations":count}
    except Exception as exc:
        save("failed",status="FAILED",errorType=type(exc).__name__,error=str(exc)[:400])
        raise
    finally:
        if lock_db:
            try:lock_db.cursor().execute("UNLOCK TABLES")
            finally:lock_db.close()
        if control.exists():
            c=read_control(control);c.update(armed=False,release=True);write_control(control,c)
        # Recover owned apps after a failed assertion too; preserve the failure.
        for kind in ("python","java"):
            if kind in stopped:
                start_native(api,kind,run_id+"-repair");wait_service(8000 if kind=="python" else 8080)
