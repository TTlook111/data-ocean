"""Acceptance actions must use collected identities, never historical fixture IDs."""
import importlib
from pathlib import Path
from copy import deepcopy

import pytest


HERE = Path(__file__).resolve().parents[2] / "scripts/system-acceptance"
NAMES = {
    "products": ("product_id", "product_name", "category"),
    "sales_orders": ("order_id", "order_date", "region", "product_id", "quantity", "unit_price", "status"),
}


class FreshApi:
    def __init__(self, output):
        self.output = output
        self.cfg = {
            "LANGGRAPH_REVIEW_APP_SCHEMA": "dataocean_e_acceptance_20260927",
            "LANGGRAPH_REVIEW_FIXTURE_SCHEMA": "langgraph_fixture_e_20260927",
        }
        self.columns = []
        for table, names in NAMES.items():
            for name in names:
                self.columns.append({"id": 501 + len(self.columns), "tableMetaId": 41 if table == "products" else 42,
                    "datasourceId": 7, "snapshotId": 23, "tableName": table, "columnName": name})
        self.requests = []
        self.source_rows = [(7, "127.0.0.1", 3306, "langgraph_fixture_e_20260927")]
        self.snapshot_row = (23, 7, 4, "PUBLISHED")
        self.table_rows = [(41, "products"), (42, "sales_orders")]
        self.column_rows = [(c["id"], c["tableMetaId"], c["tableName"], c["columnName"]) for c in self.columns]
        self.snapshot_override = {}

    def verify_target(self):
        self.requests.append(("verified",))

    def call(self, method, path, body=None, **_):
        self.requests.append((method, path, body))
        if path == "/api/admin/metadata/snapshots/23":
            return {"snapshot": {"id": 23, "datasourceId": 7, "status": "PUBLISHED", "snapshotVersion": 4, **self.snapshot_override},
                "tables": [{"id": 41, "tableName": "products"}, {"id": 42, "tableName": "sales_orders"}],
                "columns": self.columns}
        if path.startswith("/api/admin/field-semantics/columns/") and "/details?" in path:
            column_id = int(path.split("/columns/", 1)[1].split("/", 1)[0])
            assert column_id in {c["id"] for c in self.columns}, "Historical column ID is not part of collected metadata"
            assert path.endswith("snapshotId=23"), "Historical snapshot ID is not the collected snapshot"
            return {"currentApproved": {"evidence": "synthetic-sales-v1"}, "effectiveStatus": "VALID"}
        if method == "POST" and path == "/api/admin/semantic-checks/snapshots/23":
            return {"status": "COMPLETED"}
        if method == "GET" and path == "/api/iam-s1/roles":
            return []
        if method == "POST" and path == "/api/admin/users":
            return {"id": len(self.requests) + 100}
        if method == "POST" and (path == "/api/iam-s1/roles" or path == "/api/iam-s1/data-grants" or path.endswith("/roles")):
            return len(self.requests) + 200
        if method == "PUT" and path.startswith("/api/iam-s1/user-roles/"):
            return None
        raise AssertionError(f"Unexpected action: {method} {path}")


class FixtureConnection:
    def __init__(self, api):
        self.api = api
        self.rows = []

    def __enter__(self): return self
    def __exit__(self, *_): pass
    def cursor(self): return self

    def execute(self, query, args=None):
        if query == "SET SESSION TRANSACTION READ ONLY":
            self.rows = []
        elif "FROM datasource" in query:
            self.rows = self.api.source_rows
        elif "FROM metadata_snapshot" in query:
            self.rows = [self.api.snapshot_row]
            if "status='PUBLISHED'" in query and self.api.snapshot_row[3] != "PUBLISHED":
                self.rows = []
        elif "FROM db_table_meta" in query:
            self.rows = self.api.table_rows
        elif "FROM db_column_meta" in query:
            self.rows = self.api.column_rows
        else:
            raise AssertionError("Fixture context must only read declared application metadata")

    def fetchone(self): return self.rows[0] if self.rows else None
    def fetchall(self): return self.rows


def context_module(api, monkeypatch):
    monkeypatch.syspath_prepend(str(HERE))
    context = importlib.import_module("fixture_context")
    monkeypatch.setattr(context.entry, "connect", lambda *_: FixtureConnection(api))
    return context


def test_fields_follow_fresh_collected_column_and_snapshot_ids(tmp_path, monkeypatch):
    monkeypatch.syspath_prepend(str(HERE))
    flows = importlib.import_module("flows")
    api = FreshApi(tmp_path)
    context_module(api, monkeypatch)
    outcome = flows.fields(api)
    assert outcome["fields"] == 10
    assert len([r for r in api.requests if len(r) > 1 and "/details?" in r[1]]) == 10


@pytest.mark.parametrize("tamper", ["schema", "source", "duplicateSource", "snapshotOwner", "apiSnapshot", "columnId", "columnOwner", "columnSnapshot", "missingColumn", "duplicateColumn", "tableOwner"])
def test_fixture_context_rejects_foreign_or_incomplete_identities_before_mutation(tmp_path, monkeypatch, tamper):
    api = FreshApi(tmp_path)
    context = context_module(api, monkeypatch)
    if tamper == "schema": api.cfg["LANGGRAPH_REVIEW_APP_SCHEMA"] = "dataocean"
    if tamper == "source": api.source_rows.append((8, "127.0.0.1", 3306, "scanlink"))
    if tamper == "duplicateSource": api.source_rows.append((8, "localhost", 3306, api.source_rows[0][3]))
    if tamper == "snapshotOwner": api.snapshot_row = (23, 999, 4, "PUBLISHED")
    if tamper == "apiSnapshot": api.snapshot_override["id"] = 24
    if tamper == "columnId": api.columns[0]["id"] = 1008
    if tamper == "columnOwner": api.columns[0]["datasourceId"] = 8
    if tamper == "columnSnapshot": api.columns[0]["snapshotId"] = 24
    if tamper == "missingColumn": api.columns.pop()
    if tamper == "duplicateColumn": api.columns.append(deepcopy(api.columns[0]))
    if tamper == "tableOwner":
        api.columns[0]["tableMetaId"] = 42
        api.column_rows[0] = (501, 42, "products", "product_id")
    with pytest.raises(ValueError):
        context.primary_context(api)
    assert all(r[0] in ("GET", "verified") for r in api.requests)


def test_published_actions_do_not_silently_use_a_draft_snapshot(tmp_path, monkeypatch):
    api = FreshApi(tmp_path)
    api.snapshot_row = (23, 7, 4, "DRAFT")
    context = context_module(api, monkeypatch)
    with pytest.raises(ValueError, match="publish"):
        context.primary_context(api, published=True)


def test_role_grants_use_actual_source_snapshot_and_column_ids(tmp_path, monkeypatch):
    output = tmp_path / "run"
    output.mkdir()
    api = FreshApi(output)
    context = context_module(api, monkeypatch)
    first = context.primary_context(api, published=True)
    actors = importlib.import_module("actors")
    actors.setup(api)
    grant = next(r[2] for r in api.requests if len(r) == 3 and r[0] == "POST" and r[1] == "/api/iam-s1/data-grants")
    assert (grant["datasourceId"], grant["metadataSnapshotId"]) == (7, 23)
    assert {c["columnMetaId"] for c in grant["columns"]} == {501, 502, 503}
    secondary = {"datasourceId": 9, "snapshotId": 28,
        "columns": {"products." + name: {"id": 901 + index, "columnName": name}
                    for index, name in enumerate(NAMES["products"])}}
    matrix = importlib.import_module("role_matrix")
    monkeypatch.setattr(matrix, "contexts", lambda _: (first, secondary))
    before = len(api.requests)
    matrix.setup(api)
    new_grants = [r[2] for r in api.requests[before:] if len(r) == 3 and r[0] == "POST" and r[1] == "/api/iam-s1/data-grants"]
    assert {(g["datasourceId"], g["metadataSnapshotId"]) for g in new_grants} == {(7, 23), (9, 28)}
    limited = next(g for g in new_grants if g["rowConditions"])
    assert limited["rowConditions"][0]["columnMetaId"] == 501
    assert {c["columnMetaId"] for c in limited["columns"]} == {501, 503}
    for grant in new_grants:
        assert {c["columnMetaId"] for c in grant["columns"]} <= ({501, 502, 503} if grant["datasourceId"] == 7 else {901, 902, 903})


@pytest.mark.parametrize("foreign", ["datasource", "snapshot"])
def test_build_rejects_generated_documents_from_a_different_target_before_writes(tmp_path, monkeypatch, foreign):
    monkeypatch.syspath_prepend(str(HERE))
    flows = importlib.import_module("flows")
    api = FreshApi(tmp_path)
    context_module(api, monkeypatch)
    (tmp_path / "generated-documents.json").write_text('[{"id":17}]', encoding="utf-8")
    original_call = api.call

    def call(method, path, body=None, **kwargs):
        if path == "/api/admin/knowledge-docs/17":
            return {"id": 17, "datasourceId": 99 if foreign == "datasource" else 7, "currentVersion": 3}
        if path == "/api/admin/knowledge-docs/17/versions/3":
            return {"metadataSnapshotId": 99 if foreign == "snapshot" else 23}
        if path == "/api/admin/knowledge-docs?datasourceId=7&pageSize=100":
            return {"records": []}
        return original_call(method, path, body, **kwargs)

    api.call = call
    with pytest.raises(ValueError, match="Generated document"):
        flows.build(api)
    assert all(r[0] in ("GET", "verified") for r in api.requests)
