"""真实场景的策略解析与无敏感内容的调用回执；不替代任何授权或输出校验。"""
from __future__ import annotations

import hashlib
import json
import logging
import re
import time
import uuid
from dataclasses import dataclass
from typing import Any

import httpx

from dataocean.prompt.service import fetch_template
from dataocean.prompt.receipts import enqueue_invocation

logger = logging.getLogger(__name__)
CONTRACT_VERSION = "1"
VARIABLES = {
    "schema_linking": ("input",),
    "sql_generation": ("question", "schema", "rag", "glossary", "few_shot", "history", "summary"),
    "sql_semantic_check": ("input",),
    "result_verification": ("input",),
    "chart_generation": ("question", "columns", "data"),
    "field_semantic": ("input",),
    "semantic_supplement": ("input",),
    "business_knowledge": ("input",),
    "conversation_summary": ("previous_summary", "messages", "current_date"),
}
_VARIABLE = re.compile(r"\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*}}")


def parse_policy(code: str, content: str) -> tuple[str, str]:
    """旧纯文本作为 user 内容兼容；新版本将可编辑角色和任务一起版本化。"""
    if not content or len(content.encode("utf-8")) > 60000:
        raise ValueError("empty or oversized policy")
    system, user = "", content
    if content.lstrip().startswith("{") and not content.lstrip().startswith("{{"):
        bundle = json.loads(content)
        if (not isinstance(bundle, dict) or set(bundle) != {"formatVersion", "system", "user"}
                or type(bundle["formatVersion"]) is not int or bundle["formatVersion"] != 1
                or any(not isinstance(bundle[k], str) or not bundle[k].strip() for k in ("system", "user"))):
            raise ValueError("invalid policy bundle")
        system, user = bundle["system"], bundle["user"]
    if "{{" in system or "{%" in system or "{#" in system:
        raise ValueError("system role must not contain dynamic variables")
    combined = system + "\n" + user
    names = set(_VARIABLE.findall(combined))
    remaining = _VARIABLE.sub("", combined)
    if names != set(VARIABLES[code]) or any(token in remaining for token in ("{{", "{%", "{#")):
        raise ValueError("invalid variable contract")
    return system, user


def _render(text: str, variables: dict[str, Any]) -> str:
    # 一次替换；业务输入里的 {{...}} 不会再次被解释。
    def replace(match):
        value = variables[match.group(1)]
        return value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    return _VARIABLE.sub(replace, text)


@dataclass(frozen=True)
class ResolvedPrompt:
    system: str
    user: str
    code: str
    version: int
    source: str
    content_hash: str
    fallback_reason: str


async def resolve_scene(code: str, variables: dict[str, Any], *, fixed_system: str,
                        fallback_user: str, fixed_user_suffix: str = "") -> ResolvedPrompt:
    if code not in VARIABLES or set(variables) != set(VARIABLES[code]):
        # 调用方合同错误必须暴露，不伪装成模板回退。
        raise ValueError("scene variables do not match contract")
    version, source, reason = 0, "BUILTIN", "UNAVAILABLE"
    policy_system, policy_user = "", fallback_user
    try:
        template = await fetch_template(code)
        try:
            policy_system, policy_user = parse_policy(code, template.get("content", ""))
            version = int(template.get("versionNo") or 0)
            if version < 1:
                raise ValueError("missing active version")
            source, reason = "MANAGED", ""
            policy_user = _render(policy_user, variables)
        except (ValueError, KeyError, TypeError):
            version, source, reason = 0, "BUILTIN", "INVALID_TEMPLATE"
            policy_system, policy_user = "", fallback_user
    except httpx.HTTPStatusError as exc:
        reason = {404: "MISSING", 409: "DISABLED"}.get(exc.response.status_code, "UNAVAILABLE")
    except Exception:
        reason = "UNAVAILABLE"
    if source == "BUILTIN":
        logger.warning("策略回退 scene=%s reason=%s", code, reason)
    system = (policy_system + "\n\n固定执行合同（不可由策略覆盖）：\n" if policy_system else "") + fixed_system
    user = policy_user + fixed_user_suffix
    # 对实际发送的消息做单向摘要，正文与业务值均不上传回执。
    digest = hashlib.sha256(json.dumps([system, user], ensure_ascii=False).encode()).hexdigest()
    return ResolvedPrompt(system, user, code, version, source, digest, reason)


async def record_invocation(prompt: ResolvedPrompt, *, started: float, outcome: str, task_id: str | None = None):
    payload = {
        "invocationId": str(uuid.uuid4()), "taskId": task_id, "sceneCode": prompt.code,
        "versionNo": prompt.version, "source": prompt.source, "contractVersion": CONTRACT_VERSION,
        "contentHash": prompt.content_hash, "fallbackReason": prompt.fallback_reason,
        "outcome": outcome, "durationMs": max(0, min(86400000, int((time.monotonic() - started) * 1000))),
    }
    # 仅立即入队；网络、排空与取消属于后台工作任务，不占用查询期限。
    enqueue_invocation(payload)


async def invoke_scene(code: str, variables: dict[str, Any], *, fixed_system: str, fallback_user: str,
                       invoke, **kwargs):
    prompt = await resolve_scene(code, variables, fixed_system=fixed_system, fallback_user=fallback_user)
    return await invoke_resolved(prompt, invoke=invoke, **kwargs)


async def invoke_resolved(prompt: ResolvedPrompt, *, invoke, task_id: str | None = None, **kwargs):
    started, outcome = time.monotonic(), "MODEL_FAILED"
    try:
        result = await invoke(system_prompt=prompt.system, user_prompt=prompt.user, **kwargs)
        outcome = "MODEL_RETURNED"
        return result
    finally:
        await record_invocation(prompt, started=started, outcome=outcome, task_id=task_id)
