"""Explicit acceptance-only process launcher. No mocks of SQL/model/Java calls.

Run instead of uvicorn only for the isolated crash exercises. A local control
file pauses one named synthetic task; the harness then kills the real process.
Production imports never load this module and no application safety gate changes.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import sys
import time
import hashlib
from contextvars import ContextVar
from control_io import read_control,write_control

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/"python-service"))


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--control",type=Path,required=True)
    args=parser.parse_args()
    control=args.control.resolve()
    runtime=(ROOT.parent/"DataOcean-runtime").resolve()
    if not control.is_relative_to(runtime):raise ValueError("Crash control must stay inside the verified sibling runtime directory")
    def read():
        return read_control(control)
    def target(task):
        c=read()
        return c if c.get("schema")=="dataocean_e_acceptance_20260927" and c.get("taskId")==task else None
    def journal(task,event,**evidence):
        c=read()
        if not task or c.get("schema")!="dataocean_e_acceptance_20260927":return
        # No credentials, prompts, raw SQL or result cells are recorded.
        value={"taskId":task,"event":event,"pid":os.getpid(),"at":time.time(),**evidence}
        path=control.parent/"crash-execution-journal.jsonl"
        with path.open("a",encoding="utf-8") as stream:
            stream.write(json.dumps(value)+"\n");stream.flush();os.fsync(stream.fileno())
    async def pause(task,point):
        c=target(task)
        if not c or not c.get("armed") or c.get("mode")!=point:return
        c.update(armed=False,barrier=point,barrierPid=os.getpid(),barrierAt=time.time())
        write_control(control,c)
        journal(task,"BARRIER",point=point)
        while not read().get("release"):
            await asyncio.sleep(.05)
    from dataocean.iam_s1 import graph as g
    original_mark=g.mark_attempt_executing
    original_execute=g.execute_validated
    original_protect=g.protect_attempt_result
    original_verify=g._verify_result_node
    original_settle=g.settle_model_call
    original_model=g.call_llm_with_usage
    original_budget=g._budgeted_llm
    original_retrieve=g._retrieve_node
    active_task=ContextVar("acceptance_task",default=None)
    active_sql=ContextVar("acceptance_sql",default=None)
    async def budget(state,*args,**kwargs):
        token=active_task.set(state["taskId"])
        try:return await original_budget(state,*args,**kwargs)
        finally:active_task.reset(token)
    async def model(*args,**kwargs):
        journal(active_task.get(),"MODEL_INVOKED")
        return await original_model(*args,**kwargs)
    async def retrieve(state):
        token=active_task.set(state["taskId"])
        try:return await original_retrieve(state)
        finally:active_task.reset(token)
    from dataocean.rag import service as rag_service
    original_embed=rag_service.embed_single
    async def embed(*args,**kwargs):
        journal(active_task.get(),"EMBEDDING_INVOKED")
        return await original_embed(*args,**kwargs)
    from sqlalchemy import event
    from sqlalchemy.engine import Engine
    @event.listens_for(Engine,"before_cursor_execute")
    def dbapi_started(_connection,_cursor,statement,_parameters,context,_many):
        compiled=getattr(context,"compiled",None)
        if active_sql.get() and compiled is not None and str(compiled.statement).strip()==active_sql.get().strip():
            journal(active_task.get(),"DBAPI_SQL_INVOKED",statementHash=hashlib.sha256(statement.encode("utf-8")).hexdigest())
    async def mark(task,*args,**kwargs):
        await pause(task,"before-executing")
        return await original_mark(task,*args,**kwargs)
    async def execute(request):
        await pause(request.taskId,"executing-before-sql")
        journal(request.taskId,"SQL_INVOKED")
        task_token=active_task.set(request.taskId);sql_token=active_sql.set(request.validatedSql)
        try:result=await original_execute(request)
        finally:active_task.reset(task_token);active_sql.reset(sql_token)
        journal(request.taskId,"SQL_RETURNED",success=bool(result.get("success")),rowCount=result.get("rowCount"))
        return result
    async def protect(task,payload):
        result=await original_protect(task,payload)
        if result.get("status")=="PROTECTED":journal(task,"PROTECTED",attemptId=result.get("attemptId"),sqlHash=result.get("sqlHash"))
        return result
    async def verify(state):
        await pause(state["taskId"],"after-protected")
        return await original_verify(state)
    async def settle(task,call_id,*args,**kwargs):
        result=await original_settle(task,call_id,*args,**kwargs)
        if ":result:" in call_id and result.get("allowed"):
            await pause(task,"after-result-settlement")
        return result
    g.mark_attempt_executing=mark;g.execute_validated=execute
    g.protect_attempt_result=protect;g._verify_result_node=verify;g.settle_model_call=settle
    g._budgeted_llm=budget;g.call_llm_with_usage=model;g._retrieve_node=retrieve;rag_service.embed_single=embed
    import uvicorn
    uvicorn.run("dataocean.main:app",host="127.0.0.1",port=8000,access_log=False)


if __name__=="__main__":main()
