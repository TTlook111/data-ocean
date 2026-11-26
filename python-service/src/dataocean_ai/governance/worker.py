"""消费表说明生成任务，持久保存结果后确认队列消息。"""

import asyncio
import logging
import socket
import time
from threading import Event, Thread
from uuid import uuid4

import httpx
from langsmith import tracing_context
from redis import Redis
from redis.exceptions import LockNotOwnedError, RedisError, ResponseError

from dataocean_ai.adapters.chat import ModelFailure
from dataocean_ai.core.config import GovernanceSettings, Settings
from dataocean_ai.core.logging import configure_logging
from dataocean_ai.governance.queue import QueueState, connection, stream, task_key
from dataocean_ai.governance.table_description import GenerationResult, graph
from dataocean_ai.knowledge.index import execute_index
from dataocean_ai.knowledge.validation import execute_validation

# 诊断只使用任务编号及稳定状态，不回显请求内容。
logger = logging.getLogger(__name__)

# 消费组保留原有待交付任务。
GROUP = "governance-workers"

# 租约在任务总时限内失效，活跃工作者定期续租。
LEASE_SECONDS = 30


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
        if entry.get("kind") == "knowledge":
            # 当前工作者复用可靠队列与执行租约，成果使用知识构建自己的协议。
            receipt = asyncio.run(execute_index(task, run, base))
            return QueueState(
                runVersion=run, state="SUCCEEDED", result=receipt.model_dump(by_alias=True)
            )
        if entry.get("kind") == "validation":
            # 逐题召回只回交固定索引命中，真实源数据留在 Java。
            recall = asyncio.run(execute_validation(task, run, base))
            return QueueState(runVersion=run, state="SUCCEEDED", result=recall.model_dump())
        # 当前工作流在单次执行内完成。
        result = asyncio.run(execute_task(workflow, task, run, base))
        return QueueState(
            runVersion=run, state="SUCCEEDED", result=result.model_dump(by_alias=True)
        )
    except ModelFailure as failure:
        # 受控诊断保留可核查状态。
        logger.info("AI 任务未完成，task=%s，reason=%s", task, failure)
        return QueueState(runVersion=run, state="FAILED", failureCode=str(failure))
    except Exception as error:
        # 最外层保留稳定故障和异常类别，避免任务持续重投或异常正文泄露。
        logger.error("AI 工作流异常，task=%s，type=%s", task, type(error).__name__)
        return QueueState(runVersion=run, state="FAILED", failureCode="MODEL_FAILED")


def consume(
    client: Redis, key: str, entry_id: str, entry: dict[str, str], workflow, base: str
) -> None:
    """
    持有当次执行租约时处理消息，保存结果后确认交付。
    """
    run = int(entry["run"])
    job = task_key(entry["task"], run, entry.get("kind", "governance"))
    token = uuid4().hex
    lease = client.lock(f"{job}:lease", timeout=LEASE_SECONDS, thread_local=False)
    if not lease.acquire(blocking=False, token=token):
        # 待处理消息可以接管，正在被续租的执行仍归原工作者。
        return

    stopped = Event()

    def renew() -> None:
        """
        续租只延续当前执行；失去租约后由其他工作者接手。
        """
        while not stopped.wait(LEASE_SECONDS / 3):
            # 延续窗口起点，避免持续累加导致崩溃后无法及时接管。
            try:
                lease.extend(LEASE_SECONDS, replace_ttl=True)
            except RedisError:
                # 回写会再次校验租约，短暂断线时不伪造已完成状态。
                logger.warning("治理任务续租失败，task=%s", entry["task"])
                return

    def save(state: QueueState) -> bool:
        """
        只保存本次租约的状态，锁过期或被接管会使更新失败。
        """
        with client.pipeline() as pipe:
            # 并发失去租约时，旧工作者不能降级或覆盖新状态。
            pipe.watch(lease.name)
            if pipe.get(lease.name) != token:
                return False
            pipe.multi()
            pipe.set(job, state.model_dump_json(), ex=3600)
            pipe.execute()
            return True

    try:
        saved = client.get(job)
        if saved is not None and QueueState.model_validate_json(saved).state not in {
            "FAILED",
            "SUCCEEDED",
        }:
            # 只有一个有效租约执行。Java 的领取记录继续保证同代次不会重复外发。
            running = QueueState(runVersion=run, state="RUNNING")
            if not save(running):
                return
            heartbeat = Thread(target=renew, daemon=True)
            heartbeat.start()
            try:
                result = process(entry, workflow, base)
            finally:
                # 先停续租，再用 WATCH 保护写回中的锁过期或转移。
                stopped.set()
                heartbeat.join()

            if not save(result):
                # 失去租约后保留原消息，由当前工作者核实。
                return
        with client.pipeline() as pipe:
            # 完成结果已持久化，确认重投只清理消息。
            pipe.xack(key, GROUP, entry_id)
            pipe.xdel(key, entry_id)
            pipe.execute()
    finally:
        # 只释放当前持有的租约，超时后的新租约由接管者管理。
        try:
            lease.release()
        except LockNotOwnedError:
            logger.info("治理任务执行租约已转移，task=%s", entry["task"])


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
            # 恢复候选消息后再查执行租约，留出原任务剩余时间完成回写。
            cursor, claimed, *_ = client.xautoclaim(
                key, GROUP, consumer, LEASE_SECONDS * 1000, cursor, count=1
            )
            if not claimed:
                # 没有待接管消息时等待新的工作。
                incoming = client.xreadgroup(GROUP, consumer, {key: ">"}, count=1, block=1500)
                claimed = incoming[0][1] if incoming else []
            for entry_id, entry in claimed:
                # 成功执行和终态重投均沿用同一持久化规则。
                consume(client, key, entry_id, entry, workflow, base)
        except RedisError:
            # 结果未写入时保持队列待接管，连接故障有界退避且不输出配置。
            logger.warning("治理工作者暂时无法读取 Redis 队列")
            time.sleep(1)
