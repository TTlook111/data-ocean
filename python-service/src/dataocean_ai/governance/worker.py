"""消费表说明生成任务，持久保存结果后确认队列消息。"""

import asyncio
import logging
import socket
import time
from uuid import uuid4

import httpx
from langsmith import tracing_context
from redis.exceptions import RedisError, ResponseError

from dataocean_ai.adapters.chat import ModelFailure
from dataocean_ai.core.config import GovernanceSettings, Settings
from dataocean_ai.core.logging import configure_logging
from dataocean_ai.governance.queue import QueueState, connection, stream, task_key
from dataocean_ai.governance.table_description import GenerationResult, graph

# 诊断只使用任务编号及稳定状态，不回显请求内容。
logger = logging.getLogger(__name__)

# 消费组保留原有待交付任务。
GROUP = "governance-workers"


async def execute_task(workflow, task: str, run: int, base: str) -> GenerationResult:
    """为本次图执行提供已配置的内部服务依赖。"""
    async with httpx.AsyncClient(timeout=12, trust_env=False) as client:
        # 客户端和任务标识放在 Runtime，业务图状态只保存临时处理所需内容。
        with tracing_context(enabled=False):
            # 含业务资料的步骤不提交到外部追踪后端。
            state = await workflow.ainvoke(
                {},
                context={
                    "java_url": base,
                    "request_id": task,
                    "run_version": run,
                    "client": client,
                },
            )
            return state["result"]


def process(entry: dict[str, str], workflow, base: str) -> QueueState:
    """把工作流结果转换成 Java 可可靠回读的队列状态。"""
    task = entry["task"]
    run = int(entry["run"])
    try:
        # 当前工作流在单次执行内完成。
        result = asyncio.run(execute_task(workflow, task, run, base))
        return QueueState(
            runVersion=run, state="SUCCEEDED", result=result.model_dump(by_alias=True)
        )
    except ModelFailure as failure:
        # 受控诊断保留可核查状态。
        logger.info("表说明生成未完成，task=%s，reason=%s", task, failure)
        return QueueState(runVersion=run, state="FAILED", failureCode=str(failure))
    except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError):
        # 不输出原始模型响应或业务资料。
        logger.info("表说明工作流未完成，task=%s", task)
        return QueueState(runVersion=run, state="FAILED", failureCode="MODEL_FAILED")


def main() -> None:
    """以独立进程消费队列，先持久保存实际结果再确认消费。"""
    logging_settings = Settings.from_environment()
    configure_logging(logging_settings.log_level, logging_settings.log_output)
    settings = GovernanceSettings.from_environment()
    settings.validate_startup(worker=True)
    base = (settings.java_url or "").rstrip("/")
    client = connection()
    key = stream()
    consumer = f"{socket.gethostname()}:{uuid4()}"
    try:
        client.xgroup_create(key, GROUP, id="0", mkstream=True)
    except ResponseError as error:
        # 已存在的组沿用原消费进度。
        if "BUSYGROUP" not in str(error):
            raise
    workflow = graph()
    cursor = "0-0"
    while True:
        try:
            # 先接管崩溃进程留下的消息，再读取新任务。
            # 等待超过 Java 的 180 秒总时限才接管，避免抢走活跃调用。
            cursor, claimed, *_ = client.xautoclaim(key, GROUP, consumer, 190000, cursor, count=1)
            if not claimed:
                # 没有待接管消息时等待新的工作。
                incoming = client.xreadgroup(GROUP, consumer, {key: ">"}, count=1, block=1500)
                claimed = incoming[0][1] if incoming else []
            for entry_id, entry in claimed:
                # 逐条核验已保存状态后才执行。
                # 已完成响应丢失或重投时直接确认原队列项。
                job = task_key(entry["task"], int(entry["run"]))
                saved = client.get(job)
                if saved is None or QueueState.model_validate_json(saved).state in {
                    "FAILED",
                    "SUCCEEDED",
                }:
                    client.xack(key, GROUP, entry_id)
                    client.xdel(key, entry_id)
                    continue
                run = int(entry["run"])
                running = QueueState(runVersion=run, state="RUNNING")
                client.set(job, running.model_dump_json(), ex=3600)
                result = process(entry, workflow, base)
                with client.pipeline(transaction=True) as pipe:
                    # 状态写入与确认同事务完成，回读丢失时沿用原结果。
                    pipe.set(job, result.model_dump_json(), ex=3600)
                    pipe.xack(key, GROUP, entry_id)
                    pipe.xdel(key, entry_id)
                    pipe.execute()
        except RedisError:
            # 结果未写入时保持队列待接管，连接故障有界退避且不输出配置。
            logger.warning("治理工作者暂时无法读取 Redis 队列")
            time.sleep(1)
