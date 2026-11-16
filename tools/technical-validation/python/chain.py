"""独立 API / Worker 验证程序；只接收合成任务，不加载旧业务配置。"""

import asyncio
import json
import os
import sys
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import TypedDict

import httpx
import jwt
import redis.asyncio as redis
from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse
from langgraph.checkpoint.redis.aio import AsyncRedisSaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt
from pydantic import BaseModel
from redis.asyncio.retry import Retry
from redis.backoff import ExponentialBackoff

CONFIG = json.loads(Path(os.environ["PROBE_CONFIG"]).read_text(encoding="utf-8"))
PREFIX = CONFIG["prefix"]
REDIS_URL = CONFIG["redis_url"]
CHECKPOINT_REDIS_URL = CONFIG["checkpoint_redis_url"]
JAVA_URL = CONFIG["java_url"]
SECRET_DIRECTORY = Path(CONFIG["secret_directory"])


def service_token():
    now = int(time.time())
    return jwt.encode(
        {
            "iss": "probe-python",
            "aud": "probe-java",
            "sub": "python-service",
            "iat": now,
            "exp": now + 300,
        },
        (SECRET_DIRECTORY / "python-private.pem").read_bytes(),
        algorithm="RS256",
        headers={"kid": "python-probe"},
    )


async def authenticate(authorization: str = Header(default="")):
    try:
        jwt.decode(
            authorization.removeprefix("Bearer "),
            (SECRET_DIRECTORY / "java-public.pem").read_bytes(),
            algorithms=["RS256"],
            audience="probe-python",
            issuer="probe-java",
            options={"require": ["exp", "iat", "iss", "aud"]},
        )
    except jwt.PyJWTError:
        raise HTTPException(401, "invalid service identity") from None


async def java(method, path, **kwargs):
    async with httpx.AsyncClient(timeout=10, trust_env=False) as client:
        response = await client.request(
            method,
            JAVA_URL + path,
            headers={"Authorization": "Bearer " + service_token()},
            **kwargs,
        )
        response.raise_for_status()
        return response.json()


async def emit(client, request_id, stage, version):
    return await client.xadd(
        PREFIX + ":events:" + request_id,
        {
            "data": json.dumps(
                {"requestId": request_id, "runVersion": version, "stage": stage}
            ),
            "event": "stage",
        },
    )


@asynccontextmanager
async def lifespan(app):
    # Redis 重启后旧连接需主动检查并有限重试；命令 Lua 使用稳定编号保证重试幂等。
    app.state.redis = redis.from_url(
        REDIS_URL,
        decode_responses=True,
        health_check_interval=1,
        retry=Retry(ExponentialBackoff(base=0.1, cap=0.5), 2),
        retry_on_error=[redis.ConnectionError, redis.TimeoutError],
        socket_connect_timeout=1,
        socket_timeout=5,
    )
    yield
    await app.state.redis.aclose()


app = FastAPI(lifespan=lifespan)


class TaskCommand(BaseModel):
    requestId: str
    commandId: str
    kind: str
    runVersion: int


@app.get("/health")
async def health():
    return {"status": "ok", "pid": os.getpid()}


@app.post("/internal/tasks", dependencies=[Depends(authenticate)])
async def start(command: TaskCommand):
    payload = command.model_dump_json()
    client = app.state.redis
    # 命令去重与入队在一个 Redis 原子操作内，避免响应丢失后重复入队。
    result = await client.eval(
        """
        local old = redis.call('GET', KEYS[1])
        if old then
            if old == ARGV[1] then return 0 else return -1 end
        end
        redis.call('SET', KEYS[1], ARGV[1])
        redis.call('XADD', KEYS[2], '*', 'command', ARGV[1])
        return 1
        """,
        2,
        PREFIX + ":command:" + command.commandId,
        PREFIX + ":queue",
        payload,
    )
    if result == -1:
        raise HTTPException(409, "command content conflict")
    return {"accepted": True, "new": bool(result)}


@app.post("/internal/tasks/{request_id}/cancel", dependencies=[Depends(authenticate)])
async def cancel(request_id: str):
    await app.state.redis.set(PREFIX + ":cancel:" + request_id, "1")
    return {"accepted": True}


@app.get("/internal/tasks/{request_id}/events", dependencies=[Depends(authenticate)])
async def events(request_id: str, cursor: str = "0-0"):
    async def stream():
        position = cursor
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            rows = await app.state.redis.xread(
                {PREFIX + ":events:" + request_id: position}, block=500, count=20
            )
            for _, entries in rows:
                for event_id, values in entries:
                    position = event_id
                    yield f"id: {event_id}\nevent: {values['event']}\ndata: {values['data']}\n\n"
            if not rows:
                yield ": heartbeat\n\n"

    return StreamingResponse(stream(), media_type="text/event-stream")


class GraphState(TypedDict):
    request_id: str
    kind: str
    prepared: bool
    completed: bool


class TaskCancelled(Exception):
    pass


def build_graph(saver, client, version):
    async def stage(state, name):
        await java(
            "POST",
            "/internal/stage",
            json={
                "requestId": state["request_id"],
                "runVersion": version,
                "stage": name,
            },
        )
        await emit(client, state["request_id"], name, version)

    async def prepare(state):
        await stage(state, "PREPARED")
        return {"prepared": True}

    async def pause_or_work(state):
        if state["kind"] == "checkpoint":
            # interrupt 的节点会重入；其前面已完成节点由真实 checkpointer 保留。
            interrupt({"reason": "probe pause"})
        if state["kind"] == "cancel":
            for _ in range(300):
                if await client.exists(PREFIX + ":cancel:" + state["request_id"]):
                    raise TaskCancelled
                await asyncio.sleep(0.1)
        else:
            await asyncio.sleep(0.3)
        return {}

    async def complete(state):
        # Java 从 MySQL 读取权威取消意图及代次，拒绝迟到结果。
        await stage(state, "COMPLETED")
        return {"completed": True}

    builder = StateGraph(GraphState)
    builder.add_node("prepare", prepare)
    builder.add_node("pause_or_work", pause_or_work)
    builder.add_node("complete", complete)
    builder.add_edge(START, "prepare")
    builder.add_edge("prepare", "pause_or_work")
    builder.add_edge("pause_or_work", "complete")
    builder.add_edge("complete", END)
    return builder.compile(checkpointer=saver)


async def worker():
    client = redis.from_url(REDIS_URL, decode_responses=True)
    queue = PREFIX + ":queue"
    group = PREFIX + ":workers"
    try:
        await client.xgroup_create(queue, group, id="0", mkstream=True)
    except redis.ResponseError as error:
        if "BUSYGROUP" not in str(error):
            raise
    async with AsyncRedisSaver.from_conn_string(
        CHECKPOINT_REDIS_URL,
        checkpoint_prefix=PREFIX + ":checkpoint",
        checkpoint_write_prefix=PREFIX + ":checkpoint_write",
    ) as saver:
        await saver.asetup()
        await client.set(PREFIX + ":worker-ready", str(os.getpid()))
        print(json.dumps({"worker_ready": True, "pid": os.getpid()}), flush=True)
        while True:
            rows = await client.xreadgroup(
                group, str(os.getpid()), {queue: ">"}, count=1, block=500
            )
            for _, entries in rows:
                for entry_id, values in entries:
                    command = TaskCommand.model_validate_json(values["command"])
                    request_id = command.requestId
                    graph = build_graph(saver, client, command.runVersion)
                    config = {"configurable": {"thread_id": request_id}}
                    try:
                        # 每次执行前核对真实任务；重复入队不能重启终态任务。
                        current = await java("GET", "/internal/tasks/" + request_id)
                        if current["status"] in ("CANCELLED", "COMPLETED"):
                            await client.xack(queue, group, entry_id)
                            continue
                        value = (
                            Command(resume="continue")
                            if command.kind == "resume"
                            else {
                                "request_id": request_id,
                                "kind": command.kind,
                                "prepared": False,
                                "completed": False,
                            }
                        )
                        await graph.ainvoke(value, config)
                        snapshot = await graph.aget_state(config)
                        if snapshot.next:
                            await java(
                                "POST",
                                "/internal/stage",
                                json={
                                    "requestId": request_id,
                                    "runVersion": command.runVersion,
                                    "stage": "WAITING",
                                },
                            )
                            await emit(
                                client, request_id, "WAITING", command.runVersion
                            )
                        await client.xack(queue, group, entry_id)
                    except TaskCancelled:
                        await java(
                            "POST",
                            "/internal/stage",
                            json={
                                "requestId": request_id,
                                "runVersion": command.runVersion,
                                "stage": "CANCELLED",
                            },
                        )
                        await emit(client, request_id, "CANCELLED", command.runVersion)
                        await client.xack(queue, group, entry_id)
                    except Exception as error:
                        # 不输出认证头、任务正文或异常的请求内容。
                        print(
                            json.dumps(
                                {
                                    "worker_error_type": type(error).__name__,
                                    "request_id": request_id,
                                }
                            ),
                            flush=True,
                        )
                        raise


if __name__ == "__main__":
    if sys.argv[1:] == ["worker"]:
        asyncio.run(worker())
    else:
        import uvicorn

        uvicorn.run(app, host="127.0.0.1", port=CONFIG["python_port"], access_log=False)
