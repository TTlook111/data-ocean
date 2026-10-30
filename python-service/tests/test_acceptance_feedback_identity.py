"""Acceptance adapter regression: actual collected IDs and distinct actors."""
import importlib.util
from pathlib import Path
import sys
import pytest

HERE = Path(__file__).resolve().parents[2] / "scripts/system-acceptance"
sys.path.insert(0, str(HERE))

def load_module():
    spec = importlib.util.spec_from_file_location("feedback_identity_regression", HERE / "feedback.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

class QueryActor:
    def __init__(self, target_id=9):
        self.target_id = target_id
        self.calls = []
    def call(self, method, path, body=None, expected=200):
        self.calls.append((method, path, body, expected))
        if path.endswith("/feedback-targets"):
            return [{"columnMetaId": self.target_id, "revisionId": 37}]
        if path.endswith("/feedback") and method == "POST":
            return {"id": 73, "rowVersion": 1}
        if path.endswith("/feedback") and method == "GET":
            return {"status": "APPLIED", "followupStatus": "QUEUED"}
        raise AssertionError("Query actor must not perform governance writes")

class AdminActor:
    def __init__(self, output, query):
        self.output, self.query_api, self.cfg = output, query, {}
        self.calls = []
    def call(self, method, path, body=None, expected=200):
        assert path == "/api/admin/governance-feedback/73/review", "Admin must not impersonate task owner"
        self.calls.append((method, path, body, expected))
        return None

class ReadOnlyConnection:
    def __enter__(self): return self
    def __exit__(self, *_): pass
    def cursor(self): return self
    def execute(self, *_): pass
    def fetchone(self): return (1,)

def context():
    return {"datasourceId": 1, "snapshotId": 1, "columns": {"sales_orders.unit_price": {"id": 9}}}

def prepare(module, monkeypatch):
    monkeypatch.setattr(module, "completed_case", lambda *_: {"taskId": "new-office-query", "task": {"question": "synthetic original question"}})
    monkeypatch.setattr(module, "primary_context", lambda *_args, **_kw: context(), raising=False)
    monkeypatch.setattr(module.entry, "connect", lambda *_: ReadOnlyConnection())

def test_feedback_uses_actual_column_and_task_owner_separately_from_reviewer(tmp_path, monkeypatch):
    module = load_module()
    prepare(module, monkeypatch)
    query = QueryActor()
    admin = AdminActor(tmp_path, query)
    result = module.start(admin)
    assert result["columnMetaId"] == 9
    assert result["snapshotId"] == 1
    assert result["datasourceId"] == 1
    assert result["feedbackId"] == 73
    assert [c[2]["columnMetaId"] for c in query.calls if c[0] == "POST"] == [9]
    assert len(admin.calls) == 2 and admin.calls[1][3] == 409

def test_missing_actual_use_target_rejects_before_any_feedback_write(tmp_path, monkeypatch):
    module = load_module()
    prepare(module, monkeypatch)
    query = QueryActor(target_id=1006)
    admin = AdminActor(tmp_path, query)
    with pytest.raises(ValueError, match="actual-use evidence"):
        module.start(admin)
    assert not any(c[0] == "POST" for c in query.calls)
    assert not admin.calls

def test_changed_fixture_snapshot_refuses_feedback_revision_before_write(tmp_path, monkeypatch):
    module = load_module()
    prepare(module, monkeypatch)
    admin = AdminActor(tmp_path, QueryActor())
    monkeypatch.setattr(module, "load", lambda *_: {"datasourceId": 1, "snapshotId": 999, "columnMetaId": 9})
    with pytest.raises(ValueError, match="fixture context changed"):
        module.revise(admin)
    assert not admin.calls and not admin.query_api.calls
