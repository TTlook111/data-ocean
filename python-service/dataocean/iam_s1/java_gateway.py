"""Authenticated, short-lived Python-to-Java S1 attempt handoff."""

from __future__ import annotations

import logging
from typing import Any

import httpx

from dataocean.core.config import settings

logger = logging.getLogger(__name__)


class JavaAttemptError(RuntimeError):
    def __init__(self, message: str, *, retryable: bool = False):
        super().__init__(message)
        self.retryable = retryable


async def _post(path: str, payload: dict[str, Any]) -> dict[str, Any]:
    headers = {"X-Internal-Token": settings.internal_token}
    try:
        async with httpx.AsyncClient(timeout=8.0, trust_env=False) as client:
            response = await client.post(
                f"{settings.java_gateway_url.rstrip('/')}{path}", json=payload, headers=headers,
            )
        if response.status_code >= 500:
            raise JavaAttemptError("Java 授权服务暂时不可用", retryable=True)
        if response.status_code >= 400:
            try:
                error_body = response.json()
                detail = str(error_body.get("message", ""))[:240] if isinstance(error_body, dict) else ""
            except Exception:
                detail = ""
            logger.warning("Java S1 attempt endpoint rejected status=%d message=%s",
                           response.status_code, detail or "<no message>")
            raise JavaAttemptError("Java 拒绝当前 S1 执行尝试")
        body = response.json()
        if isinstance(body, dict) and isinstance(body.get("data"), dict):
            return body["data"]
        if not isinstance(body, dict):
            raise JavaAttemptError("Java 返回了无效的 S1 执行合同")
        return body
    except JavaAttemptError:
        raise
    except (httpx.TimeoutException, httpx.TransportError) as exc:
        raise JavaAttemptError("Java 授权服务连接失败", retryable=True) from exc
    except Exception as exc:
        raise JavaAttemptError("Java 授权服务响应无效") from exc


async def authorize_attempt(task_id: str, evidence: dict[str, Any]) -> dict[str, Any]:
    return await _post(f"/internal/iam-s1/query/tasks/{task_id}/attempts/authorize", evidence)


async def mark_attempt_executing(task_id: str, attempt_id: str, sql_hash: str) -> dict[str, Any]:
    return await _post(
        f"/internal/iam-s1/query/tasks/{task_id}/attempts/{attempt_id}/executing",
        {"sqlHash": sql_hash},
    )


async def protect_attempt_result(task_id: str, result: dict[str, Any]) -> dict[str, Any]:
    attempt_id = str(result.get("attemptId") or "")
    return await _post(
        f"/internal/iam-s1/query/tasks/{task_id}/attempts/{attempt_id}/protect", result,
    )


async def reserve_model_call(task_id: str, call_id: str, node_name: str,
                             input_tokens: int, output_tokens: int, model_name: str) -> dict[str, Any]:
    return await _post(
        f"/internal/iam-s1/query/tasks/{task_id}/attempts/budget/reserve",
        {"callId": call_id, "nodeName": node_name, "inputTokens": input_tokens,
         "outputTokens": output_tokens, "modelName": model_name, "usageReported": False},
    )


async def settle_model_call(task_id: str, call_id: str, input_tokens: int,
                            output_tokens: int, usage_reported: bool, model_name: str) -> dict[str, Any]:
    return await _post(
        f"/internal/iam-s1/query/tasks/{task_id}/attempts/budget/settle",
        {"callId": call_id, "nodeName": "settle", "inputTokens": input_tokens,
         "outputTokens": output_tokens, "modelName": model_name,
         "usageReported": usage_reported},
    )
