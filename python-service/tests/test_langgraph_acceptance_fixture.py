"""G0 test fixture guardrails; these tests never contact external services."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import pytest


REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURES = REPO_ROOT / "scripts" / "langgraph-acceptance" / "fixtures"
BASELINE = REPO_ROOT / "scripts" / "langgraph-acceptance" / "baseline.py"


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
