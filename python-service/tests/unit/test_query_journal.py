"""核对问数回执的输入约束和同一请求的并发写入。"""

import asyncio
import os
from uuid import uuid4

import pytest
from fastapi import HTTPException, Request

from dataocean_ai.adapters.chat import ModelFailure
from dataocean_ai.query import journal


def envelope(request_id: str, step: str, lookup: bool = False) -> Request:
    """构造不携带业务原文的内部节点调用标记。"""
    return Request(
        {
            "type": "http",
            "headers": [
                (b"x-query-id", request_id.encode()),
                (b"x-query-step", step.encode()),
                (b"x-query-lookup", str(lookup).lower().encode()),
            ],
        }
    )


def test_bad_identity_and_offline_checkpoint_fail_before_dispatch(monkeypatch):
    """格式错误或检查点失效不能在内存中模拟已外发。"""
    monkeypatch.delenv("AI_CHECKPOINT_URL", raising=False)
    request_id = str(uuid4())

    async def no_dispatch():
        """失败条件下外部调用不可到达。"""
        pytest.fail("失去检查点也不可继续外发")

    def offline(*_args, **_options):
        """无法连接已指定的检查点实例。"""
        raise OSError("offline")

    for request, status, detail in [
        (envelope("bad", "plan"), 400, "QUERY_REQUEST_REQUIRED"),
        (envelope(request_id.upper(), "plan"), 400, "QUERY_REQUEST_REQUIRED"),
        (envelope(request_id, "invalid step"), 400, "QUERY_STEP_REQUIRED"),
        (envelope(request_id, "plan"), 503, "QUERY_CHECKPOINT_UNAVAILABLE"),
    ]:
        # 错误响应使用封闭类别，不返回原始模型输入。
        with pytest.raises(HTTPException) as error:
            asyncio.run(journal.journal(request, {"lists": [3, 5]}, no_dispatch))
        assert (error.value.status_code, error.value.detail) == (status, detail)
    monkeypatch.setenv("AI_CHECKPOINT_URL", "redis://127.0.0.1:1")
    monkeypatch.setattr(journal.Redis, "from_url", offline)
    with pytest.raises(HTTPException) as error:
        asyncio.run(journal.journal(envelope(request_id, "plan"), {}, no_dispatch))
    assert error.value.detail == "QUERY_CHECKPOINT_UNAVAILABLE"


def test_replays_failure_and_rejects_read_of_unissued_step(monkeypatch):
    """真实 Redis 保留已确认的失败，拒绝为未知动作生成新回执。"""
    url = os.getenv("DO_QUERY_TEST_REDIS_URL")
    if not url:
        pytest.skip("需要本机独立 Redis 检查点实例")
    monkeypatch.setenv("AI_CHECKPOINT_URL", url)
    monkeypatch.setenv("AI_QUERY_CHECKPOINT_PREFIX", "dataocean:query-review-test")
    request_id = str(uuid4())
    calls = 0

    async def failure():
        """模拟外部服务实际返回失败。"""
        nonlocal calls
        calls += 1
        raise ModelFailure("TIMEOUT")

    async def run():
        """首次失败落盘后再次读取，调用次数必须保持不变。"""
        with pytest.raises(HTTPException) as missing:
            await journal.journal(envelope(request_id, "semantic", True), {}, failure)
        assert missing.value.detail == "QUERY_STEP_OUTCOME_UNKNOWN"
        for lookup in (False, True):
            # 第二次只回放已经确认的失败类型。
            with pytest.raises(HTTPException) as failed:
                await journal.journal(envelope(request_id, "plan", lookup), {"flags": [1]}, failure)
            assert failed.value.status_code == 503
            assert failed.value.detail == "TIMEOUT"

    asyncio.run(run())
    assert calls == 1


def test_step_digest_excludes_credentials_and_budget() -> None:
    """原任务重启后预算可缩减，秘密和剩余时间都不参与输入摘要。"""
    original = {"model": {"apiKey": "private", "timeoutSeconds": 22, "revisionId": "4"}}
    resumed = {"model": {"apiKey": "new-secret", "timeoutSeconds": 6, "revisionId": "4"}}
    assert (
        journal.stable(original)
        == journal.stable(resumed)
        == {
            "model": {"revisionId": "4"},
        }
    )


def test_confirmed_receipt_cannot_be_used_for_a_changed_payload() -> None:
    """回执属于实际提交的输入，其他条件不能读取旧结果。"""
    with pytest.raises(HTTPException) as rejected:
        journal.receipt({"digest": "original", "value": 85, "error": None}, "changed")
    assert rejected.value.status_code == 409


def test_concurrent_steps_keep_independent_receipts(monkeypatch: pytest.MonkeyPatch) -> None:
    """两个独立调用写同一个 LangGraph 线程时，保留两份实际回执。"""
    url = os.getenv("DO_QUERY_TEST_REDIS_URL")
    if not url:
        pytest.skip("需要本机独立 Redis 检查点实例")
    monkeypatch.setenv("AI_CHECKPOINT_URL", url)
    monkeypatch.setenv("AI_QUERY_CHECKPOINT_PREFIX", "dataocean:query-review-test")
    request_id = str(uuid4())

    def request(step: str, lookup: bool = False) -> Request:
        """生成纯测试任务的内部标识。"""
        return Request(
            {
                "type": "http",
                "headers": [
                    (b"x-query-id", request_id.encode()),
                    (b"x-query-step", step.encode()),
                    (b"x-query-lookup", str(lookup).lower().encode()),
                ],
            }
        )

    async def run() -> None:
        """保持首节点尚未提交回执，同时启动第二个不同节点。"""
        first_started = asyncio.Event()
        release_first = asyncio.Event()

        async def first() -> str:
            """模拟已发起且尚未收妥的说明请求。"""
            first_started.set()
            await release_first.wait()
            return "first"

        async def second() -> str:
            """不同解释修订独立留存，但写入同一线程。"""
            return "second"

        async def must_not_invoke() -> None:
            """回查只读取旧回执。"""
            pytest.fail("回查不应重新执行节点")

        task_a = asyncio.create_task(journal.journal(request("explanation-1"), {"id": 1}, first))
        await asyncio.wait_for(first_started.wait(), 8)
        task_b = asyncio.create_task(journal.journal(request("explanation-2"), {"id": 2}, second))
        try:
            # 让第二份回执先落盘，再释放先启动的慢节点，避免时序偶发。
            assert await asyncio.wait_for(asyncio.shield(task_b), 8) == "second"
        finally:
            release_first.set()
            assert await asyncio.wait_for(task_a, 12) == "first"
        assert (
            await journal.journal(request("explanation-1", True), {"id": 1}, must_not_invoke)
            == "first"
        )
        assert (
            await journal.journal(request("explanation-2", True), {"id": 2}, must_not_invoke)
            == "second"
        )

    asyncio.run(run())
