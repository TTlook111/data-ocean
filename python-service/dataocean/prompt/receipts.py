"""进程生命周期管理的有界回执队列；不阻塞业务请求，不保存提示词正文。"""
from __future__ import annotations

import asyncio
import logging
from contextlib import suppress

import httpx

from dataocean.prompt.service import JAVA_BASE_URL, INTERNAL_TOKEN

logger = logging.getLogger(__name__)


class ReceiptDispatcher:
    def __init__(self, capacity: int = 128):
        self.queue: asyncio.Queue[dict] = asyncio.Queue(maxsize=capacity)
        self.accepting = True
        self.worker = asyncio.create_task(self._run(), name="prompt-receipts")

    def enqueue(self, payload: dict) -> bool:
        if not self.accepting or self.worker.done():
            logger.warning("策略回执队列未运行，丢弃回执 scene=%s", payload["sceneCode"])
            return False
        try:
            self.queue.put_nowait(dict(payload))
            return True
        except asyncio.QueueFull:
            logger.warning("策略回执队列已满，丢弃回执 scene=%s invocation=%s", payload["sceneCode"], payload["invocationId"])
            return False

    async def _run(self):
        try:
            async with httpx.AsyncClient(timeout=2.0, trust_env=False) as client:
                while True:
                    payload = await self.queue.get()
                    try:
                        response = await client.post(f"{JAVA_BASE_URL}/internal/prompts/invocations", json=payload,
                                                     headers={"X-Internal-Token": INTERNAL_TOKEN})
                        response.raise_for_status()
                    except Exception:
                        logger.warning("策略调用回执保存失败 scene=%s invocation=%s", payload["sceneCode"], payload["invocationId"])
                    finally:
                        self.queue.task_done()
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.warning("策略回执工作任务异常停止")
        finally:
            self.accepting = False

    async def close(self, drain_timeout: float = 2.0):
        self.accepting = False
        try:
            await asyncio.wait_for(self.queue.join(), timeout=drain_timeout)
        except TimeoutError:
            logger.warning("策略回执关闭排空超时，剩余回执被丢弃 count=%d", self.queue.qsize())
        finally:
            self.worker.cancel()
            with suppress(asyncio.CancelledError):
                await self.worker
            while not self.queue.empty():
                self.queue.get_nowait()
                self.queue.task_done()


_dispatcher: ReceiptDispatcher | None = None


def start_receipt_dispatcher() -> ReceiptDispatcher:
    global _dispatcher
    if _dispatcher is None:
        _dispatcher = ReceiptDispatcher()
    return _dispatcher


def enqueue_invocation(payload: dict) -> bool:
    if _dispatcher is None:
        logger.warning("策略回执队列尚未初始化，丢弃回执 scene=%s", payload["sceneCode"])
        return False
    return _dispatcher.enqueue(payload)


async def close_receipt_dispatcher():
    global _dispatcher
    current, _dispatcher = _dispatcher, None
    if current is not None:
        await current.close()
