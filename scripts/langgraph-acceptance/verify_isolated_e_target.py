"""Read-only guards and budget ledger for the current E acceptance schema."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from typing import Any

import pymysql


APP_SCHEMA = "dataocean_e_acceptance_20260927"
FIXTURE_SCHEMA = "langgraph_fixture_e_20260927"
FIXTURE_USER = "g0_reader_e27"
BUILD_ID = "1473e4ce-96fe-4483-82ef-2c64664bb0ab"


def _required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise ValueError(f"missing required local configuration: {name}")
    return value


def _connection(*, database: str, username: str, password: str):
    return pymysql.connect(
        host="127.0.0.1",
        port=3306,
        user=username,
        password=password,
        database=database,
        connect_timeout=5,
        read_timeout=10,
        write_timeout=10,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        autocommit=True,
    )


def _normalized_grant(grant: str) -> tuple[str, str]:
    cleaned = re.sub(r"[`'\"]", "", grant.strip().lower())
    match = re.fullmatch(r"grant\s+(.+?)\s+on\s+(.+?)\s+to\s+.+", cleaned)
    if not match:
        raise ValueError("unrecognized fixture reader privilege")
    return re.sub(r"\s+", " ", match.group(1)), re.sub(r"\s+", "", match.group(2))


def _assert_read_only_grants(grants: list[str], schema: str) -> None:
    observed: set[tuple[str, str]] = set()
    allowed = {
        ("usage", "*.*"),
        ("select", f"{schema}.products"),
        ("select", f"{schema}.sales_orders"),
    }
    try:
        observed = {_normalized_grant(grant) for grant in grants}
    except ValueError as exc:
        raise ValueError("fixture database account has unrecognized privileges") from exc
    if observed != allowed:
        raise ValueError("fixture account is not restricted to SELECT on products and sales_orders")


def verify_target() -> dict[str, Any]:
    app_schema = _required("LANGGRAPH_REVIEW_APP_SCHEMA")
    fixture_schema = _required("LANGGRAPH_REVIEW_FIXTURE_SCHEMA")
    fixture_user = _required("LANGGRAPH_REVIEW_FIXTURE_USER")
    if app_schema != APP_SCHEMA or fixture_schema != FIXTURE_SCHEMA or fixture_user != FIXTURE_USER:
        raise ValueError("local acceptance configuration does not match the frozen E target")

    root_user = _required("LANGGRAPH_REVIEW_MYSQL_ROOT_USER")
    root_password = _required("LANGGRAPH_REVIEW_MYSQL_ROOT_PASSWORD")
    fixture_password = _required("LANGGRAPH_REVIEW_FIXTURE_PASSWORD")
    login_user = _required("LANGGRAPH_ACCEPTANCE_READER_USERNAME")
    _required("LANGGRAPH_ACCEPTANCE_READER_PASSWORD")

    try:
        with _connection(database=APP_SCHEMA, username=root_user, password=root_password) as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT DATABASE() AS database_name")
                actual_schema = cursor.fetchone()["database_name"]
                if actual_schema != APP_SCHEMA:
                    raise ValueError("application schema identity check failed")
                cursor.execute(
                    "SELECT version FROM flyway_schema_history "
                    "WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1"
                )
                migration = cursor.fetchone()
                if not migration or str(migration["version"]) != "64":
                    raise ValueError("application schema is not at Flyway V64")
    except Exception as exc:
        raise ValueError("application schema V64 guard failed") from None

    try:
        connection = _connection(database=FIXTURE_SCHEMA, username=fixture_user, password=fixture_password)
    except Exception:
        raise ValueError("fixture reader connection failed") from None
    try:
        with connection.cursor() as cursor:
            try:
                cursor.execute("SELECT DATABASE() AS database_name, CURRENT_USER() AS account_name")
                identity = cursor.fetchone()
            except Exception:
                raise ValueError("fixture account identity query failed") from None
            if identity["database_name"] != FIXTURE_SCHEMA:
                raise ValueError("fixture schema identity check failed")
            if identity["account_name"].split("@", 1)[0] != FIXTURE_USER:
                raise ValueError("fixture read-only account identity check failed")
            try:
                cursor.execute("SHOW GRANTS FOR CURRENT_USER()")
                grants = [next(iter(row.values())) for row in cursor.fetchall()]
                _assert_read_only_grants(grants, FIXTURE_SCHEMA)
            except Exception:
                raise ValueError("fixture account is not restricted to the G0 SELECT grants") from None
    finally:
        connection.close()

    return {
        "passed": True,
        "applicationSchema": APP_SCHEMA,
        "flywayVersion": 64,
        "fixtureSchema": FIXTURE_SCHEMA,
        "fixtureDatabaseUser": FIXTURE_USER,
        "fixturePrivileges": ["SELECT products", "SELECT sales_orders"],
        "loginUsername": login_user,
    }


def read_budget_ledger(task_ids: list[str]) -> dict[str, Any]:
    if not task_ids or any(not task_id for task_id in task_ids):
        raise ValueError("the acceptance run has no complete task ID set")
    schema = _required("LANGGRAPH_REVIEW_APP_SCHEMA")
    root_user = _required("LANGGRAPH_REVIEW_MYSQL_ROOT_USER")
    root_password = _required("LANGGRAPH_REVIEW_MYSQL_ROOT_PASSWORD")
    placeholders = ",".join(["%s"] * len(task_ids))
    with _connection(database=schema, username=root_user, password=root_password) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT task_id, user_id, datasource_id, status, iam_final_protection_status, rag_build_id, "
                "active_metadata_snapshot_id, permission_revision, llm_call_count, "
                "embedding_call_count, estimated_ai_cost_cny, total_time_ms, retry_count "
                f"FROM query_task WHERE task_id IN ({placeholders})",
                task_ids,
            )
            tasks = cursor.fetchall()
            cursor.execute(
                "SELECT task_id, attempt_id, attempt_no, status, sql_hash, safe_sql, "
                "row_count, execution_time_ms, used_tables, used_columns, masked_fields, error_message "
                f"FROM query_attempt WHERE task_id IN ({placeholders}) ORDER BY task_id, attempt_no",
                task_ids,
            )
            attempts = cursor.fetchall()
            cursor.execute(
                "SELECT task_id, call_id, node_name, model_name, status, input_tokens, output_tokens, "
                "reserved_cost_cny, actual_cost_cny, usage_estimated "
                f"FROM query_model_call WHERE task_id IN ({placeholders}) ORDER BY task_id, created_at, id",
                task_ids,
            )
            calls = cursor.fetchall()

    for row in tasks:
        row["estimated_ai_cost_cny"] = str(row["estimated_ai_cost_cny"] or 0)
    for row in calls:
        for key in ("reserved_cost_cny", "actual_cost_cny"):
            if row[key] is not None:
                row[key] = str(row[key])
    found = {row["task_id"] for row in tasks}
    if found != set(task_ids):
        raise ValueError("budget ledger does not contain every API task")
    return {"tasks": tasks, "sqlAttempts": attempts, "modelCalls": calls}


def read_captcha(key: str) -> str:
    if not key:
        raise ValueError("captcha key is missing")
    import redis

    client = redis.Redis(host="127.0.0.1", port=6379, db=0, decode_responses=True, socket_timeout=5)
    value = client.get(f"captcha:{key}")
    if not value:
        raise ValueError("captcha is unavailable in the configured local Redis DB")
    return value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--captcha-key")
    parser.add_argument("--ledger", action="store_true")
    args = parser.parse_args()
    try:
        if args.captcha_key:
            print(read_captcha(args.captcha_key))
            return
        guard = verify_target()
        result: dict[str, Any] = {"targetGuards": guard}
        if args.ledger:
            task_ids = json.loads(_required("LANGGRAPH_ACCEPTANCE_TASK_IDS_JSON"))
            result["budgetLedger"] = read_budget_ledger(task_ids)
        # Keep the helper's machine-readable stdout ASCII-only so PowerShell's
        # native-command code page cannot corrupt non-ASCII SQL aliases.
        print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
    except Exception as exc:
        # Print only our safe stage label; driver exceptions can contain connection details.
        safe_message = str(exc) if isinstance(exc, ValueError) else "database or Redis operation failed"
        print(f"isolated acceptance guard failed: {safe_message}", file=sys.stderr)
        raise SystemExit(2)


if __name__ == "__main__":
    main()
