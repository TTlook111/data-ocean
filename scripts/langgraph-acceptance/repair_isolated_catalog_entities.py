#!/usr/bin/env python3
"""Repair missing TABLE/DATASOURCE catalog entities for the frozen G0 snapshot.

The script is a guarded, idempotent post-initialization maintenance step. It
never deletes or rewrites existing catalog entities, and it never modifies the
snapshot, metadata, permissions, knowledge, or business data.

Usage (from the repository root, using the python-service virtualenv):

    python-service/.venv/Scripts/python.exe \
        scripts/langgraph-acceptance/repair_isolated_catalog_entities.py
    python-service/.venv/Scripts/python.exe \
        scripts/langgraph-acceptance/repair_isolated_catalog_entities.py --apply

Without --apply the script performs a read-only validation and prints the plan.
Connection credentials are read from the ignored local file
scripts/langgraph-acceptance/.env.local; passwords are never printed.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import uuid
from pathlib import Path
from typing import Any

import pymysql


TARGET_SCHEMA = "dataocean_e_acceptance_20260927"
TARGET_DATASOURCE_ID = 1
TARGET_SNAPSHOT_ID = 1
EXPECTED_SNAPSHOT_VERSION = 1
EXPECTED_DATASOURCE_DATABASE = "langgraph_fixture_e_20260927"
EXPECTED_TABLE_COLUMN_COUNTS = {"sales_orders": 7, "products": 3}
LOCK_NAME = f"dataocean:catalog-repair:{TARGET_SCHEMA}:{TARGET_DATASOURCE_ID}:{TARGET_SNAPSHOT_ID}"

ENTITY_FIELDS = (
    "id", "entity_type", "entity_uuid", "fqn", "name", "display_name",
    "description", "entity_metadata", "owner_id", "version", "created_at", "updated_at",
)
RELATION_FIELDS = (
    "id", "source_id", "source_type", "target_id", "target_type",
    "relation_type", "relation_metadata", "created_at",
)


class RepairError(RuntimeError):
    pass


def _load_local_env() -> None:
    path = Path(__file__).with_name(".env.local")
    if not path.exists():
        raise RepairError("local acceptance configuration is missing")
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def _required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RepairError(f"missing required local configuration: {name}")
    return value


def _connect(*, autocommit: bool):
    schema = _required("LANGGRAPH_REVIEW_APP_SCHEMA")
    if schema != TARGET_SCHEMA:
        raise RepairError("local app schema is not the frozen isolation target")
    return pymysql.connect(
        host=os.environ.get("LANGGRAPH_REVIEW_MYSQL_HOST", "127.0.0.1"),
        port=int(os.environ.get("LANGGRAPH_REVIEW_MYSQL_PORT", "3306")),
        user=_required("LANGGRAPH_REVIEW_MYSQL_ROOT_USER"),
        password=_required("LANGGRAPH_REVIEW_MYSQL_ROOT_PASSWORD"),
        database=TARGET_SCHEMA,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        connect_timeout=5,
        read_timeout=30,
        write_timeout=30,
        autocommit=autocommit,
    )


def _parse_meta(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if not value:
        return {}
    try:
        parsed = json.loads(value)
    except (TypeError, ValueError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _meta_int(meta: dict[str, Any], *keys: str) -> int | None:
    for key in keys:
        if key in meta and meta[key] is not None:
            try:
                return int(meta[key])
            except (TypeError, ValueError):
                return None
    return None


def _fqn_table(datasource_name: str, database_name: str, table_name: str) -> str:
    return f"{datasource_name.lower()}.{database_name.lower()}.{table_name.lower()}"


def _fqn_column(datasource_name: str, database_name: str, table_name: str, column_name: str) -> str:
    return f"{_fqn_table(datasource_name, database_name, table_name)}.{column_name.lower()}"


def _row_by_fqn(entities: list[dict[str, Any]], fqn: str) -> list[dict[str, Any]]:
    wanted = fqn.casefold()
    return [entity for entity in entities if str(entity["fqn"]).casefold() == wanted]


def _entity_fingerprint(row: dict[str, Any]) -> tuple[Any, ...]:
    return tuple(row.get(field) for field in ENTITY_FIELDS)


def _relation_fingerprint(row: dict[str, Any]) -> tuple[Any, ...]:
    return tuple(row.get(field) for field in RELATION_FIELDS)


def _select_for_update(cursor, sql: str, params: tuple[Any, ...], lock: bool) -> None:
    cursor.execute(sql + (" FOR UPDATE" if lock else ""), params)


def _inspect(cursor, *, lock: bool) -> dict[str, Any]:
    _select_for_update(
        cursor,
        "SELECT id, name, database_name, status FROM datasource WHERE id=%s",
        (TARGET_DATASOURCE_ID,),
        lock,
    )
    datasource = cursor.fetchone()
    if not datasource:
        raise RepairError("target datasource 1 does not exist")
    if datasource["database_name"] != EXPECTED_DATASOURCE_DATABASE:
        raise RepairError("target datasource database does not match the frozen fixture")

    _select_for_update(
        cursor,
        "SELECT id, datasource_id, snapshot_version, status, table_count, column_count, published_at "
        "FROM metadata_snapshot WHERE id=%s",
        (TARGET_SNAPSHOT_ID,),
        lock,
    )
    snapshot = cursor.fetchone()
    if not snapshot:
        raise RepairError("target snapshot 1 does not exist")
    if int(snapshot["datasource_id"]) != TARGET_DATASOURCE_ID:
        raise RepairError("target snapshot 1 does not belong to datasource 1")
    if snapshot["status"] != "PUBLISHED" or snapshot["published_at"] is None:
        raise RepairError("target snapshot 1 is not PUBLISHED")
    if int(snapshot["snapshot_version"]) != EXPECTED_SNAPSHOT_VERSION:
        raise RepairError("target snapshot 1 version does not match the frozen fixture")
    if int(snapshot["table_count"]) != len(EXPECTED_TABLE_COLUMN_COUNTS):
        raise RepairError("snapshot table_count does not match the frozen fixture")
    if int(snapshot["column_count"]) != sum(EXPECTED_TABLE_COLUMN_COUNTS.values()):
        raise RepairError("snapshot column_count does not match the frozen fixture")

    _select_for_update(
        cursor,
        "SELECT id FROM metadata_snapshot WHERE datasource_id=%s AND status='PUBLISHED'",
        (TARGET_DATASOURCE_ID,),
        lock,
    )
    published = cursor.fetchall()
    if len(published) != 1 or int(published[0]["id"]) != TARGET_SNAPSHOT_ID:
        raise RepairError("published snapshot range has changed for datasource 1")

    _select_for_update(
        cursor,
        "SELECT id, snapshot_id, datasource_id, table_name, table_comment, table_type, governance_status "
        "FROM db_table_meta WHERE snapshot_id=%s AND datasource_id=%s ORDER BY id",
        (TARGET_SNAPSHOT_ID, TARGET_DATASOURCE_ID),
        lock,
    )
    table_metas = cursor.fetchall()
    names = {str(row["table_name"]) for row in table_metas}
    if len(table_metas) != len(EXPECTED_TABLE_COLUMN_COUNTS) or names != set(EXPECTED_TABLE_COLUMN_COUNTS):
        raise RepairError("published table structure does not match the frozen fixture")
    table_by_id = {int(row["id"]): row for row in table_metas}

    _select_for_update(
        cursor,
        "SELECT id, snapshot_id, table_meta_id, datasource_id, table_name, column_name, column_comment, "
        "data_type, governance_status FROM db_column_meta "
        "WHERE snapshot_id=%s AND datasource_id=%s ORDER BY id",
        (TARGET_SNAPSHOT_ID, TARGET_DATASOURCE_ID),
        lock,
    )
    column_metas = cursor.fetchall()
    if len(column_metas) != sum(EXPECTED_TABLE_COLUMN_COUNTS.values()):
        raise RepairError("published column structure does not match the frozen fixture")
    counts: dict[str, int] = {}
    for column in column_metas:
        table = table_by_id.get(int(column["table_meta_id"]))
        if table is None or table["table_name"] != column["table_name"]:
            raise RepairError("column metadata ownership conflict in the published snapshot")
        counts[str(column["table_name"])] = counts.get(str(column["table_name"]), 0) + 1
    if counts != EXPECTED_TABLE_COLUMN_COUNTS:
        raise RepairError("published column counts do not match the frozen fixture")

    datasource_name = str(datasource["name"])
    database_name = str(datasource["database_name"])
    datasource_fqn = datasource_name.lower()
    prefix = datasource_fqn + "."

    _select_for_update(
        cursor,
        "SELECT id, entity_type, entity_uuid, fqn, name, display_name, description, entity_metadata, "
        "owner_id, version, created_at, updated_at FROM metadata_entity "
        "WHERE fqn=%s OR fqn LIKE %s OR JSON_EXTRACT(entity_metadata, '$.datasource_id')=%s",
        (datasource_fqn, prefix + "%", TARGET_DATASOURCE_ID),
        lock,
    )
    entities = cursor.fetchall()

    datasource_entities = [
        entity for entity in entities
        if entity["entity_type"] == "DATASOURCE"
    ]
    if len(datasource_entities) > 1:
        raise RepairError("multiple DATASOURCE entities exist for the target")
    datasource_entity = datasource_entities[0] if datasource_entities else None
    if datasource_entity is not None:
        if str(datasource_entity["fqn"]).casefold() != datasource_fqn.casefold():
            raise RepairError("existing DATASOURCE entity has a conflicting FQN")
        if _meta_int(_parse_meta(datasource_entity["entity_metadata"]), "datasource_id", "datasourceId") != TARGET_DATASOURCE_ID:
            raise RepairError("existing DATASOURCE entity has conflicting ownership")

    table_by_fqn: dict[str, dict[str, Any]] = {}
    for table_name in EXPECTED_TABLE_COLUMN_COUNTS:
        fqn = _fqn_table(datasource_name, database_name, table_name)
        candidates = _row_by_fqn(entities, fqn)
        if len(candidates) > 1:
            raise RepairError(f"multiple entities share the table FQN for {table_name}")
        if candidates:
            candidate = candidates[0]
            if candidate["entity_type"] != "TABLE" or candidate["name"] != table_name:
                raise RepairError(f"existing entity for {table_name} is not the expected TABLE")
            meta = _parse_meta(candidate["entity_metadata"])
            if _meta_int(meta, "datasource_id", "datasourceId") != TARGET_DATASOURCE_ID:
                raise RepairError(f"existing TABLE {table_name} has conflicting datasource ownership")
            if _meta_int(meta, "snapshot_id", "snapshotId") != TARGET_SNAPSHOT_ID:
                raise RepairError(f"existing TABLE {table_name} has conflicting snapshot ownership")
            table_by_fqn[table_name] = candidate

    expected_table_fqns = {
        _fqn_table(datasource_name, database_name, table_name).casefold()
        for table_name in EXPECTED_TABLE_COLUMN_COUNTS
    }
    for entity in entities:
        if entity["entity_type"] == "TABLE" and str(entity["fqn"]).casefold() not in expected_table_fqns:
            raise RepairError("an unexpected TABLE entity already exists for the target")

    column_entities = [entity for entity in entities if entity["entity_type"] == "COLUMN"]
    if len(column_entities) != len(column_metas):
        raise RepairError("existing COLUMN entity count does not match the published snapshot")
    column_by_meta_id: dict[int, dict[str, Any]] = {}
    used_column_ids: set[int] = set()
    for column in column_metas:
        fqn = _fqn_column(
            datasource_name,
            database_name,
            str(column["table_name"]),
            str(column["column_name"]),
        )
        candidates = _row_by_fqn(entities, fqn)
        if len(candidates) != 1:
            raise RepairError(f"expected exactly one COLUMN entity for {fqn}")
        entity = candidates[0]
        if entity["entity_type"] != "COLUMN" or entity["name"] != column["column_name"]:
            raise RepairError(f"existing COLUMN entity for {fqn} has conflicting identity")
        meta = _parse_meta(entity["entity_metadata"])
        if _meta_int(meta, "datasource_id", "datasourceId") != TARGET_DATASOURCE_ID:
            raise RepairError(f"existing COLUMN entity for {fqn} has conflicting datasource ownership")
        if _meta_int(meta, "snapshot_id", "snapshotId") != TARGET_SNAPSHOT_ID:
            raise RepairError(f"existing COLUMN entity for {fqn} has conflicting snapshot ownership")
        if _meta_int(meta, "column_meta_id", "columnMetaId") != int(column["id"]):
            raise RepairError(f"existing COLUMN entity for {fqn} has conflicting column metadata id")
        if "tableName" in meta and str(meta["tableName"]) != str(column["table_name"]):
            raise RepairError(f"existing COLUMN entity for {fqn} has conflicting table name")
        if "tableMetaId" in meta and _meta_int(meta, "tableMetaId") != int(column["table_meta_id"]):
            raise RepairError(f"existing COLUMN entity for {fqn} has conflicting table metadata id")
        entity_id = int(entity["id"])
        if entity_id in used_column_ids:
            raise RepairError("multiple published columns resolve to the same COLUMN entity")
        used_column_ids.add(entity_id)
        column_by_meta_id[int(column["id"])] = entity

    if used_column_ids != {int(entity["id"]) for entity in column_entities}:
        raise RepairError("an unexpected COLUMN entity exists for the target")

    _select_for_update(
        cursor,
        "SELECT id, source_id, source_type, target_id, target_type, relation_type, relation_metadata, created_at "
        "FROM metadata_relationship ORDER BY id",
        (),
        lock,
    )
    relationships = cursor.fetchall()

    # Resolve the existing datasource entity id for relationship planning.
    datasource_entity_id = int(datasource_entity["id"]) if datasource_entity else None
    planned_tables = [name for name in EXPECTED_TABLE_COLUMN_COUNTS if name not in table_by_fqn]
    planned_relations: list[dict[str, Any]] = []

    def relation_exists(source_id: int, source_type: str, target_id: int, target_type: str, relation_type: str) -> bool:
        matches = [
            relation for relation in relationships
            if int(relation["source_id"]) == source_id
            and int(relation["target_id"]) == target_id
            and relation["relation_type"] == relation_type
        ]
        if len(matches) > 1:
            raise RepairError("duplicate relationship rows violate the unique contract")
        if not matches:
            return False
        relation = matches[0]
        if relation["source_type"] != source_type or relation["target_type"] != target_type:
            raise RepairError("existing relationship has conflicting entity types")
        return True

    for table_name in EXPECTED_TABLE_COLUMN_COUNTS:
        table_entity = table_by_fqn.get(table_name)
        contains_exists = False
        if datasource_entity_id is not None and table_entity is not None:
            contains_exists = relation_exists(
                datasource_entity_id, "DATASOURCE", int(table_entity["id"]), "TABLE", "CONTAINS",
            )
        if not contains_exists:
            planned_relations.append({
                "source": datasource_fqn,
                "sourceType": "DATASOURCE",
                "target": table_name,
                "targetType": "TABLE",
                "relationType": "CONTAINS",
            })

        for column in column_metas:
            if str(column["table_name"]) != table_name:
                continue
            has_part_exists = False
            if table_entity is not None:
                column_entity = column_by_meta_id[int(column["id"])]
                has_part_exists = relation_exists(
                    int(table_entity["id"]), "TABLE", int(column_entity["id"]), "COLUMN", "HAS_PART",
                )
            if not has_part_exists:
                planned_relations.append({
                    "source": table_name,
                    "sourceType": "TABLE",
                    "target": str(column["column_name"]),
                    "targetType": "COLUMN",
                    "relationType": "HAS_PART",
                })

    return {
        "datasource": datasource,
        "snapshot": snapshot,
        "datasourceName": datasource_name,
        "databaseName": database_name,
        "datasourceFqn": datasource_fqn,
        "tableMetas": table_metas,
        "columnMetas": column_metas,
        "entities": entities,
        "datasourceEntity": datasource_entity,
        "tableByFqn": table_by_fqn,
        "columnByMetaId": column_by_meta_id,
        "relationships": relationships,
        "plannedTables": planned_tables,
        "plannedRelations": planned_relations,
        "preservedColumnIds": sorted(used_column_ids),
    }


def _apply(cursor, context: dict[str, Any]) -> dict[str, Any]:
    added_entities: list[dict[str, Any]] = []
    added_relations: list[dict[str, Any]] = []

    datasource_entity = context["datasourceEntity"]
    if datasource_entity is None:
        metadata = json.dumps({
            "datasource_id": TARGET_DATASOURCE_ID,
            "database_name": context["databaseName"],
        }, ensure_ascii=False, separators=(",", ":"))
        cursor.execute(
            "INSERT INTO metadata_entity "
            "(entity_type, entity_uuid, fqn, name, display_name, description, entity_metadata, version) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, 1)",
            (
                "DATASOURCE",
                str(uuid.uuid4()),
                context["datasourceFqn"],
                context["datasourceName"],
                context["datasourceName"],
                None,
                metadata,
            ),
        )
        datasource_entity_id = int(cursor.lastrowid)
        added_entities.append({
            "id": datasource_entity_id,
            "entityType": "DATASOURCE",
            "fqn": context["datasourceFqn"],
        })
    else:
        datasource_entity_id = int(datasource_entity["id"])

    table_metas_by_name = {str(row["table_name"]): row for row in context["tableMetas"]}
    table_entities = dict(context["tableByFqn"])
    for table_name in context["plannedTables"]:
        table = table_metas_by_name[table_name]
        fqn = _fqn_table(context["datasourceName"], context["databaseName"], table_name)
        metadata = json.dumps({
            "datasource_id": TARGET_DATASOURCE_ID,
            "snapshot_id": TARGET_SNAPSHOT_ID,
            "table_type": table["table_type"],
            "governance_status": table["governance_status"],
        }, ensure_ascii=False, separators=(",", ":"))
        cursor.execute(
            "INSERT INTO metadata_entity "
            "(entity_type, entity_uuid, fqn, name, display_name, description, entity_metadata, version) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, 1)",
            ("TABLE", str(uuid.uuid4()), fqn, table_name, table["table_comment"], table["table_comment"], metadata),
        )
        table_entity_id = int(cursor.lastrowid)
        added_entities.append({"id": table_entity_id, "entityType": "TABLE", "fqn": fqn})
        table_entities[table_name] = {
            "id": table_entity_id,
            "entity_type": "TABLE",
            "fqn": fqn,
            "name": table_name,
        }

    for planned in context["plannedRelations"]:
        if planned["sourceType"] == "DATASOURCE":
            source_id = datasource_entity_id
            target_id = int(table_entities[planned["target"]]["id"])
        else:
            source_id = int(table_entities[planned["source"]]["id"])
            target_meta = next(
                column for column in context["columnMetas"]
                if str(column["column_name"]) == planned["target"]
                and str(column["table_name"]) == planned["source"]
            )
            target_id = int(context["columnByMetaId"][int(target_meta["id"])]["id"])
        cursor.execute(
            "INSERT INTO metadata_relationship "
            "(source_id, source_type, target_id, target_type, relation_type, relation_metadata) "
            "VALUES (%s, %s, %s, %s, %s, NULL)",
            (
                source_id,
                planned["sourceType"],
                target_id,
                planned["targetType"],
                planned["relationType"],
            ),
        )
        added_relations.append({
            "id": int(cursor.lastrowid),
            "sourceId": source_id,
            "sourceType": planned["sourceType"],
            "targetId": target_id,
            "targetType": planned["targetType"],
            "relationType": planned["relationType"],
        })

    return {"addedEntities": added_entities, "addedRelations": added_relations}


def _verify_after(cursor, before: dict[str, Any], added: dict[str, Any]) -> dict[str, Any]:
    _select_for_update(
        cursor,
        "SELECT id, entity_type, entity_uuid, fqn, name, display_name, description, entity_metadata, "
        "owner_id, version, created_at, updated_at FROM metadata_entity "
        "WHERE fqn=%s OR fqn LIKE %s OR JSON_EXTRACT(entity_metadata, '$.datasource_id')=%s",
        (before["datasourceFqn"], before["datasourceFqn"] + ".%", TARGET_DATASOURCE_ID),
        True,
    )
    entities = cursor.fetchall()
    by_fqn: dict[str, list[dict[str, Any]]] = {}
    for entity in entities:
        by_fqn.setdefault(str(entity["fqn"]).casefold(), []).append(entity)

    expected_table_fqns = {
        _fqn_table(before["datasourceName"], before["databaseName"], name).casefold()
        for name in EXPECTED_TABLE_COLUMN_COUNTS
    }
    table_entities = [entity for entity in entities if entity["entity_type"] == "TABLE"]
    column_entities = [entity for entity in entities if entity["entity_type"] == "COLUMN"]
    datasource_entities = [entity for entity in entities if entity["entity_type"] == "DATASOURCE"]
    if len(table_entities) != len(EXPECTED_TABLE_COLUMN_COUNTS):
        raise RepairError("post-repair TABLE entity count is not two")
    if {str(entity["fqn"]).casefold() for entity in table_entities} != expected_table_fqns:
        raise RepairError("post-repair TABLE FQNs do not match the published snapshot")
    if len(column_entities) != sum(EXPECTED_TABLE_COLUMN_COUNTS.values()):
        raise RepairError("post-repair COLUMN entity count changed")
    if len(datasource_entities) != 1:
        raise RepairError("post-repair DATASOURCE entity count is not one")
    for fqn, rows in by_fqn.items():
        dataset = [entity for entity in rows if entity["entity_type"] in {"DATASOURCE", "TABLE", "COLUMN"}]
        if len(dataset) > 1:
            raise RepairError(f"multiple catalog entities share the FQN {fqn}")

    preserved_ids = before["preservedColumnIds"]
    placeholders = ",".join(["%s"] * len(preserved_ids))
    cursor.execute(
        f"SELECT {', '.join(ENTITY_FIELDS)} FROM metadata_entity WHERE id IN ({placeholders}) ORDER BY id",
        tuple(preserved_ids),
    )
    preserved_after = {int(row["id"]): row for row in cursor.fetchall()}
    for row in before["entities"]:
        entity_id = int(row["id"])
        if entity_id not in preserved_ids:
            continue
        if preserved_after.get(entity_id) is None:
            raise RepairError("an existing COLUMN entity was removed")
        if _entity_fingerprint(preserved_after[entity_id]) != _entity_fingerprint(row):
            raise RepairError("an existing COLUMN entity was modified")

    cursor.execute(
        f"SELECT {', '.join(RELATION_FIELDS)} FROM metadata_relationship ORDER BY id"
    )
    relations_after = {int(row["id"]): row for row in cursor.fetchall()}
    for row in before["relationships"]:
        relation_id = int(row["id"])
        if relation_id not in relations_after:
            raise RepairError("an existing relationship was removed")
        if _relation_fingerprint(relations_after[relation_id]) != _relation_fingerprint(row):
            raise RepairError("an existing relationship was modified")

    datasource_id = int(datasource_entities[0]["id"])
    table_by_name = {str(entity["name"]): entity for entity in table_entities}
    for table_name in EXPECTED_TABLE_COLUMN_COUNTS:
        relation = [
            row for row in relations_after.values()
            if int(row["source_id"]) == datasource_id
            and row["relation_type"] == "CONTAINS"
            and int(row["target_id"]) == int(table_by_name[table_name]["id"])
        ]
        if len(relation) != 1 or relation[0]["source_type"] != "DATASOURCE" or relation[0]["target_type"] != "TABLE":
            raise RepairError(f"missing CONTAINS relationship for {table_name}")
        for column in before["columnMetas"]:
            if str(column["table_name"]) != table_name:
                continue
            target = before["columnByMetaId"][int(column["id"])]
            has_part = [
                row for row in relations_after.values()
                if int(row["source_id"]) == int(table_by_name[table_name]["id"])
                and row["relation_type"] == "HAS_PART"
                and int(row["target_id"]) == int(target["id"])
            ]
            if len(has_part) != 1 or has_part[0]["source_type"] != "TABLE" or has_part[0]["target_type"] != "COLUMN":
                raise RepairError(f"missing HAS_PART relationship for {column['column_name']}")

    return {
        "tableEntityCount": len(table_entities),
        "columnEntityCount": len(column_entities),
        "datasourceEntityCount": len(datasource_entities),
        "relationshipCount": len(relations_after),
    }


def _preview_result(before: dict[str, Any], added: dict[str, Any] | None = None) -> dict[str, Any]:
    return {
        "target": {
            "schema": TARGET_SCHEMA,
            "datasourceId": TARGET_DATASOURCE_ID,
            "snapshotId": TARGET_SNAPSHOT_ID,
        },
        "snapshot": {
            "version": int(before["snapshot"]["snapshot_version"]),
            "tableCount": int(before["snapshot"]["table_count"]),
            "columnCount": int(before["snapshot"]["column_count"]),
        },
        "existing": {
            "datasourceEntities": 0 if before["datasourceEntity"] is None else 1,
            "tableEntities": len(before["tableByFqn"]),
            "columnEntities": len(before["preservedColumnIds"]),
            "preservedColumnEntityIds": before["preservedColumnIds"],
            "existingRelationshipCount": len(before["relationships"]),
        },
        "planned": {
            "datasourceEntities": 0 if before["datasourceEntity"] is not None else 1,
            "tableEntities": len(before["plannedTables"]),
            "relations": [
                {"relationType": item["relationType"], "source": item["source"], "target": item["target"]}
                for item in before["plannedRelations"]
            ],
        },
        "added": added,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="write the missing catalog entities")
    args = parser.parse_args()
    _load_local_env()

    connection = None
    lock_acquired = False
    try:
        if not args.apply:
            connection = _connect(autocommit=True)
            with connection.cursor() as cursor:
                context = _inspect(cursor, lock=False)
                print(json.dumps(_preview_result(context), ensure_ascii=True, separators=(",", ":")))
            return

        connection = _connect(autocommit=False)
        with connection.cursor() as cursor:
            cursor.execute("SELECT GET_LOCK(%s, 10) AS acquired", (LOCK_NAME,))
            if cursor.fetchone()["acquired"] != 1:
                raise RepairError("could not acquire the catalog repair lock")
            lock_acquired = True
            connection.commit()
            connection.begin()
            try:
                context = _inspect(cursor, lock=True)
                added = _apply(cursor, context)
                verified = _verify_after(cursor, context, added)
                connection.commit()
            except Exception:
                connection.rollback()
                raise
        result = _preview_result(context, added)
        result["mode"] = "apply"
        result["added"] = added
        result["verified"] = verified
        print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
    except RepairError as exc:
        print(f"catalog repair stopped: {exc}", file=sys.stderr)
        raise SystemExit(2)
    except Exception as exc:
        print(
            f"catalog repair stopped: database operation failed ({type(exc).__name__}); transaction rolled back",
            file=sys.stderr,
        )
        raise SystemExit(3)
    finally:
        if connection is not None:
            try:
                if lock_acquired:
                    with connection.cursor() as cursor:
                        cursor.execute("SELECT RELEASE_LOCK(%s) AS released", (LOCK_NAME,))
                    connection.commit()
            except Exception:
                pass
            connection.close()


if __name__ == "__main__":
    main()
