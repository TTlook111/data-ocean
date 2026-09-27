"""G0 test fixture guardrails; these tests never contact external services."""

from __future__ import annotations

import importlib.util
import json
import shutil
import subprocess
from pathlib import Path

import pytest


REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURES = REPO_ROOT / "scripts" / "langgraph-acceptance" / "fixtures"
BASELINE = REPO_ROOT / "scripts" / "langgraph-acceptance" / "baseline.py"
CURRENT_RUNNER = REPO_ROOT / "scripts" / "langgraph-acceptance" / "run_iam_s1_g0_e_isolation.ps1"
ISOLATED_GUARD = REPO_ROOT / "scripts" / "langgraph-acceptance" / "verify_isolated_e_target.py"


def _load_baseline():
    spec = importlib.util.spec_from_file_location("langgraph_g0_baseline", BASELINE)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module


def test_g0_question_set_has_fixed_answerable_and_refusal_cases():
    questions = json.loads((FIXTURES / "g0_questions.json").read_text(encoding="utf-8"))
    assert len(questions) == 8
    assert len({question["id"] for question in questions}) == 8
    assert sum(question["answerable"] for question in questions) == 6
    assert sum(not question["answerable"] for question in questions) == 2
    assert all(question.get("expectedRows") for question in questions if question["answerable"])


def test_g0_grant_excludes_restricted_fixture_tables():
    authorization = json.loads((FIXTURES / "g0_authorization.json").read_text(encoding="utf-8"))
    granted = {grant["table"] for grant in authorization["grants"]}
    assert granted == {"sales_orders", "products"}
    assert set(authorization["deniedTables"]).isdisjoint(granted)


def test_baseline_refuses_non_fixture_database(monkeypatch):
    baseline = _load_baseline()
    monkeypatch.setenv("LANGGRAPH_ACCEPTANCE_MYSQL_HOST", "192.0.2.10")
    monkeypatch.setenv("LANGGRAPH_ACCEPTANCE_MYSQL_PORT", "13316")
    monkeypatch.setenv("LANGGRAPH_ACCEPTANCE_MYSQL_DATABASE", "langgraph_fixture")
    monkeypatch.setenv("LANGGRAPH_ACCEPTANCE_MYSQL_USERNAME", "langgraph_fixture_reader")
    monkeypatch.setenv("LANGGRAPH_ACCEPTANCE_MYSQL_PASSWORD", "fixture-only")
    with pytest.raises(SystemExit, match="Refusing to run"):
        baseline._fixture_connection()


def test_baseline_compares_values_without_requiring_llm_alias_choice():
    baseline = _load_baseline()
    assert baseline._rows_match(
        [{"s1_c1": "180.0", "s1_c2": "Furniture"}],
        [{"revenue": "180.00", "category": "Furniture"}],
    )
    assert not baseline._rows_match(
        [{"s1_c1": "181.0", "s1_c2": "Furniture"}],
        [{"revenue": "180.00", "category": "Furniture"}],
    )


def test_current_e_runner_pins_the_existing_isolated_target_and_frozen_budgets():
    runner = CURRENT_RUNNER.read_text(encoding="utf-8")
    guard = ISOLATED_GUARD.read_text(encoding="utf-8")
    for frozen_value in (
        "dataocean_e_acceptance_20260927",
        "langgraph_fixture_e_20260927",
        "g0_reader_e27",
        "1473e4ce-96fe-4483-82ef-2c64664bb0ab",
        "expectedReaderId = 9002",
        "sqlCalls -gt 3",
        "llmCalls -gt 8",
        "embeddingCalls -gt 2",
        "duration -gt 90000",
        "cost -gt [decimal]0.10",
    ):
        assert frozen_value in runner or frozen_value in guard
    assert "LANGGRAPH_ACCEPTANCE_READER_PASSWORD" in runner
    assert "Evaluate-G0QuestionSecurity" in runner
    assert "$summary.securityViolations -ne 0" in runner
    assert "SHOW GRANTS FOR CURRENT_USER()" in guard
    assert "dataocean_e_acceptance_20260927" in guard


def test_current_e_fixture_account_guard_rejects_any_write_or_wildcard_grant():
    spec = importlib.util.spec_from_file_location("isolated_e_guard", ISOLATED_GUARD)
    guard = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(guard)

    valid = [
        "GRANT USAGE ON *.* TO `g0_reader_e27`@`%`",
        "GRANT SELECT ON `langgraph_fixture_e_20260927`.`products` TO `g0_reader_e27`@`%`",
        "GRANT SELECT ON `langgraph_fixture_e_20260927`.`sales_orders` TO `g0_reader_e27`@`%`",
    ]
    guard._assert_read_only_grants(valid, "langgraph_fixture_e_20260927")
    with pytest.raises(ValueError, match="not restricted"):
        guard._assert_read_only_grants(
            valid + ["GRANT INSERT ON `langgraph_fixture_e_20260927`.`sales_orders` TO `g0_reader_e27`@`%`"],
            "langgraph_fixture_e_20260927",
        )
    with pytest.raises(ValueError, match="not restricted"):
        guard._assert_read_only_grants(
            [
                "GRANT USAGE ON *.* TO `g0_reader_e27`@`%`",
                "GRANT SELECT ON `langgraph_fixture_e_20260927`.* TO `g0_reader_e27`@`%`",
            ],
            "langgraph_fixture_e_20260927",
        )


def test_runner_exits_nonzero_when_only_assistant_history_metadata_leaks(tmp_path):
    powershell = shutil.which("pwsh") or shutil.which("powershell")
    if not powershell:
        pytest.skip("PowerShell is required to execute the G0 runner self-test")
    report_path = tmp_path / "assistant-history-leak.json"
    completed = subprocess.run(
        [
            powershell,
            "-NoProfile",
            "-File",
            str(CURRENT_RUNNER),
            "-SyntheticHistoryLeakTest",
            "-ReportPath",
            str(report_path),
        ],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=20,
        check=False,
    )

    assert completed.returncode == 1
    report = json.loads(report_path.read_text(encoding="utf-8-sig"))
    question = report["questions"][0]
    assert question["protectedResult"]["data"] == []
    assert question["assistantHistory"][0]["content"] == "本轮查询已完成"
    assert question["assistantHistory"][0]["metadata"]["note"] == "hidden phone value"
    assert question["passed"] is False
    assert question["leakedIdentifiers"] == ["phone"]
    assert report["securityViolations"] == 1
    assert report["securityCounterRejected"] is True
    assert any("assistant history" in failure for failure in report["failures"])
