"""统一 LLM Context Firewall。

The firewall accepts only metadata that can be tied to the current S1
snapshot.  It intentionally returns a smaller context instead of attempting
to repair uncertain or unbound content.
"""

from __future__ import annotations

import re
from typing import Any

from .schema import S1PermissionSnapshot


_SENSITIVE_KEYS = {
    "value", "sample_value", "sample_values", "example", "example_value",
    "parameter_bindings", "execution_bindings", "structured_value_json",
}
_SQL_RE = re.compile(r"\b(select|insert|update|delete|with|from|join|where)\b", re.I)


def resource_index(snapshot: S1PermissionSnapshot) -> dict[str, dict[str, dict[str, Any]]]:
    result: dict[str, dict[str, dict[str, Any]]] = {}
    for resource in snapshot.resources:
        columns: dict[str, dict[str, Any]] = {}
        for column in resource.columns:
            if column.protectionLevel == "HIDDEN":
                continue
            columns[column.name.lower()] = {
                "name": column.name,
                "columnId": column.columnId,
                "usage": list(column.usage),
                "protectionLevel": column.protectionLevel,
                "maskPolicy": column.maskPolicy,
                "dataType": column.dataType,
            }
        result[resource.tableName.lower()] = columns
    return result


def _drop_sensitive_values(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            key: _drop_sensitive_values(item)
            for key, item in value.items()
            if key.lower() not in _SENSITIVE_KEYS
        }
    if isinstance(value, list):
        return [_drop_sensitive_values(item) for item in value]
    return value


def filter_schema(schema: list[dict[str, Any]], snapshot: S1PermissionSnapshot) -> list[dict[str, Any]]:
    """Filter schema linking output to visible S1 fields only."""
    allowed = resource_index(snapshot)
    result: list[dict[str, Any]] = []
    for item in schema:
        table = str(item.get("tableName", item.get("table_name", ""))).lower()
        columns = allowed.get(table)
        if not columns:
            continue
        copied = _drop_sensitive_values(item)
        raw_columns = copied.get("columns", [])
        if isinstance(raw_columns, list):
            copied["columns"] = [
                column for column in raw_columns
                if isinstance(column, dict)
                and str(column.get("name", column.get("columnName", ""))).lower() in columns
            ]
        copied["tableName"] = table
        result.append(copied)
    return result


def filter_chunk(chunk: dict[str, Any], snapshot: S1PermissionSnapshot) -> dict[str, Any] | None:
    """Return a chunk only when its datasource/snapshot and complete source bind."""
    if not isinstance(chunk, dict):
        return None
    datasource = chunk.get("datasourceId", chunk.get("datasource_id"))
    source_snapshot = chunk.get("activeMetadataSnapshotId", chunk.get("snapshotId", chunk.get("snapshot_id")))
    if datasource != snapshot.datasourceId or source_snapshot != snapshot.activeMetadataSnapshotId:
        return None

    tables = chunk.get("tables", chunk.get("tableNames"))
    columns = chunk.get("columns", chunk.get("columnNames"))
    if not isinstance(tables, list) or not tables or not isinstance(columns, list) or not columns:
        return None
    allowed = resource_index(snapshot)
    normalized_tables = {str(table).lower() for table in tables}
    if not normalized_tables.issubset(allowed):
        return None
    for column in columns:
        if not isinstance(column, str) or "." not in column:
            return None
        table, field = column.split(".", 1)
        column_meta = allowed.get(table.lower(), {}).get(field.lower())
        if column_meta is None:
            return None
    return _drop_sensitive_values(chunk)


def filter_chunks(chunks: list[dict[str, Any]], snapshot: S1PermissionSnapshot) -> list[dict[str, Any]]:
    return [filtered for chunk in chunks if (filtered := filter_chunk(chunk, snapshot)) is not None]


def filter_glossary(terms: list[dict[str, Any]], snapshot: S1PermissionSnapshot) -> list[dict[str, Any]]:
    """Keep only terms without unsafe values and bound to visible resources."""
    allowed = resource_index(snapshot)
    result: list[dict[str, Any]] = []
    for term in terms:
        bound_columns = term.get("columns", term.get("linkedColumns"))
        if bound_columns is not None:
            if not isinstance(bound_columns, list) or not bound_columns:
                continue
            if any(
                not isinstance(item, str) or "." not in item
                or item.split(".", 1)[0].lower() not in allowed
                or item.split(".", 1)[1].lower() not in allowed[item.split(".", 1)[0].lower()]
                for item in bound_columns
            ):
                continue
        result.append(_drop_sensitive_values(term))
    return result


def _contains_sensitive_value(value: str, sensitive_values: list[Any]) -> bool:
    return any(raw is not None and str(raw) and str(raw) in value for raw in sensitive_values)


def filter_conversation(
    turns: list[dict[str, Any]],
    snapshot: S1PermissionSnapshot,
    sensitive_values: list[Any] | None = None,
) -> list[dict[str, Any]]:
    """Drop a turn when it contains SQL or an unprovable field/table reference."""
    allowed = resource_index(snapshot)
    result: list[dict[str, Any]] = []
    for turn in turns:
        if not isinstance(turn, dict) or turn.get("role") not in {"user", "assistant"}:
            continue
        content = turn.get("content")
        if not isinstance(content, str) or _SQL_RE.search(content) or _contains_sensitive_value(content, sensitive_values or []):
            continue
        qualified = re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)\b", content)
        if any(table.lower() not in allowed or field.lower() not in allowed[table.lower()] for table, field in qualified):
            continue
        result.append({"role": turn["role"], "content": content})
    return result


def build_model_context(
    snapshot: S1PermissionSnapshot,
    schema: list[dict[str, Any]],
    chunks: list[dict[str, Any]],
    glossary: list[dict[str, Any]],
    few_shot: list[dict[str, Any]],
    history: list[dict[str, Any]],
    summary: dict[str, Any] | None,
    sensitive_values: list[Any] | None = None,
) -> dict[str, Any]:
    """Apply the same firewall to every model input node."""
    return {
        "schema": filter_schema(schema, snapshot),
        "rag": filter_chunks(chunks, snapshot),
        "glossary": filter_glossary(glossary, snapshot),
        "fewShot": [
            item for item in filter_chunks(few_shot, snapshot)
            if "sql" not in item or isinstance(item.get("sql"), str)
        ],
        "conversationHistory": filter_conversation(history, snapshot, sensitive_values),
        "conversationSummary": (
            {}
            if _SQL_RE.search(str(summary or {})) or _contains_sensitive_value(str(summary or {}), sensitive_values or [])
            else _drop_sensitive_values(summary or {})
        ),
    }
