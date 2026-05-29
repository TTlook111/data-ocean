"""Prompt version tracking helpers for one Agent run."""

from __future__ import annotations

from typing import Any, Mapping

from .state import AgentState


def record_prompt_version(
    state: AgentState | Mapping[str, Any],
    template_code: str,
    version_no: int | str | None,
) -> AgentState:
    """Return a new state containing the prompt template/version pair once."""
    normalized_version = _normalize_version(version_no)
    entry = {"templateCode": template_code, "versionNo": normalized_version}
    versions = list(state.get("prompt_versions") or [])

    already_recorded = any(
        item.get("templateCode") == template_code
        and _normalize_version(item.get("versionNo")) == normalized_version
        for item in versions
        if isinstance(item, dict)
    )
    if not already_recorded:
        versions.append(entry)

    return {**state, "prompt_versions": versions}


def _normalize_version(version_no: int | str | None) -> int:
    if version_no is None:
        return 0
    try:
        return int(version_no)
    except (TypeError, ValueError):
        return 0
