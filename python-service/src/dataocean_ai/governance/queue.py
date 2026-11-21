"""可靠接收表说明生成任务，Redis 仅保存任务引用和受控结果。"""

from functools import lru_cache
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Path
from pydantic import BaseModel, ConfigDict, Field
from redis import Redis
from redis.exceptions import RedisError, WatchError

from dataocean_ai.core.config import GovernanceSettings
from dataocean_ai.governance.security import verify_java

# 内部端点由机器身份保护，只接受单个真实任务。
router = APIRouter(
    prefix="/internal/v1/tasks/governance",
    dependencies=[Depends(verify_java)],
)

# 知识构建沿用同一可靠投递机制，独立保留名空间和类型标识。
knowledge_router = APIRouter(
    prefix="/internal/v1/tasks/knowledge", dependencies=[Depends(verify_java)]
)


class QueueState(BaseModel):
    """供 Java 回读的执行状态与实际模型结果。"""

    # 拒绝将未识别的队列字段作为成功结果。
    model_config = ConfigDict(extra="forbid")

    # 同一请求的固定执行代次。
    runVersion: int = Field(ge=1)  # noqa: N815

    # 排队、在途或终态。
    state: str

    # 终态错误的可披露稳定原因。
    failureCode: str | None = None  # noqa: N815

    # 成功时为类型化业务模型的 JSON；Java 再次核对具体内容。
    result: dict | None = None


@lru_cache(maxsize=1)
def connection() -> Redis:
    """复用当前进程的受控 Redis 客户端与连接池。"""
    url = GovernanceSettings.from_environment().redis_url
    if url is None:
        # 启动最小 API 时不会隐式接入外部队列。
        raise KeyError("AI_QUEUE_URL")
    return Redis.from_url(url.get_secret_value(), decode_responses=True, socket_timeout=4)


def prefix() -> str:
    """在固定配置下隔离本服务的队列和任务状态。"""
    return GovernanceSettings.from_environment().namespace


def stream() -> str:
    """返回本模块实际使用的 Redis Stream。"""
    return prefix() + ":tasks"


def task_key(task: UUID | str, run: int, kind: str = "governance") -> str:
    """接收重发沿用同一任务和执行代次。"""
    family = "task" if kind == "governance" else "knowledge-task"
    return f"{prefix()}:{family}:{task}:{run}"


@router.put("/{task}/{run}", response_model=QueueState)
def enqueue(task: UUID, run: Annotated[int, Path(ge=1)]) -> QueueState:
    """实际接收记录与入队用同一 Redis 事务保存。"""
    return enqueue_work(task, run, "governance")


@knowledge_router.put("/{task}/{run}", response_model=QueueState)
def enqueue_knowledge(task: UUID, run: Annotated[int, Path(ge=1)]) -> QueueState:
    """以固定任务类型接收或回读知识构建。"""
    return enqueue_work(task, run, "knowledge")


def enqueue_work(task: UUID, run: int, kind: str) -> QueueState:
    """同一编号和类型的重投只回读原有状态。"""
    key = task_key(task, run, kind)
    try:
        # WATCH 保持同一请求代次在并发重发中只有一笔实际消息。
        client = connection()
        while True:
            try:
                with client.pipeline() as pipe:
                    pipe.watch(key)
                    saved = pipe.get(key)
                    if saved is not None:
                        # 回包丢失时保留原状态及执行资格。
                        return QueueState.model_validate_json(saved)
                    pending = QueueState(runVersion=run, state="QUEUED")
                    pipe.multi()
                    pipe.set(key, pending.model_dump_json(), ex=3600)
                    pipe.xadd(stream(), {"task": str(task), "run": str(run), "kind": kind})
                    pipe.execute()
                    return pending
            except WatchError:
                # 竞争写入后回读已知任务，避免新建请求。
                continue
    except (RedisError, KeyError):
        # Redis 不可用时不假装已经接收任务。
        raise HTTPException(status_code=503, detail="QUEUE_UNAVAILABLE") from None


@router.get("/{task}/{run}", response_model=QueueState)
def status(task: UUID, run: Annotated[int, Path(ge=1)]) -> QueueState:
    """以确切任务代次读取已提交结果。"""
    try:
        saved = connection().get(task_key(task, run))
    except (RedisError, KeyError):
        # 连接故障保留原任务状态待上游核实。
        raise HTTPException(status_code=503, detail="QUEUE_UNAVAILABLE") from None
    if saved is None:
        # 未接收与执行失败分别表达。
        raise HTTPException(status_code=404, detail="TASK_NOT_FOUND")
    return QueueState.model_validate_json(saved)
