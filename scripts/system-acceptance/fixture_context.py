"""Resolve real collected IDs without relaxing the isolated fixture contract."""
import acceptance as entry

PRIMARY_COLUMNS = {
    "products": {"product_id", "product_name", "category"},
    "sales_orders": {"order_id", "order_date", "region", "product_id", "quantity", "unit_price", "status"},
}


def primary_context(api, published=False):
    return _context(api, entry.FIXTURE_SCHEMA, PRIMARY_COLUMNS, published)


def secondary_context(api):
    return _context(api, entry.SECONDARY_SCHEMA, {"products": PRIMARY_COLUMNS["products"]}, True)


def _context(api, schema, expected, published):
    if (api.cfg.get("LANGGRAPH_REVIEW_APP_SCHEMA") != entry.APP_SCHEMA
            or api.cfg.get("LANGGRAPH_REVIEW_FIXTURE_SCHEMA") != entry.FIXTURE_SCHEMA):
        raise ValueError("Refusing a non-isolated application or fixture database")
    api.verify_target()
    with entry.connect(api.cfg) as db, db.cursor() as cur:
        cur.execute("SET SESSION TRANSACTION READ ONLY")
        cur.execute("SELECT id,host,port,database_name FROM datasource WHERE deleted=0")
        sources = cur.fetchall()
        if any(host not in {"127.0.0.1", "localhost"} or port != 3306
               or name not in {entry.FIXTURE_SCHEMA, entry.SECONDARY_SCHEMA} for _, host, port, name in sources):
            raise ValueError("Datasource inventory includes a non-fixture target")
        matches = [row for row in sources if row[3] == schema]
        if len(matches) != 1:
            raise ValueError("Exactly one declared fixture datasource is required")
        datasource = matches[0][0]
        status = " AND status='PUBLISHED'" if published else ""
        cur.execute("SELECT id,datasource_id,snapshot_version,status FROM metadata_snapshot "
                    "WHERE datasource_id=%s" + status + " ORDER BY snapshot_version DESC,id DESC LIMIT 1", (datasource,))
        snapshot = cur.fetchone()
        if not snapshot:
            raise ValueError("Collect and, when required, publish the fixture snapshot before this flow")
        sid, owner = snapshot[:2]
        if owner != datasource:
            raise ValueError("Snapshot does not belong to the declared fixture datasource")
        cur.execute("SELECT id,table_name FROM db_table_meta WHERE datasource_id=%s AND snapshot_id=%s", (datasource, sid))
        tables = cur.fetchall()
        cur.execute("SELECT id,table_meta_id,table_name,column_name FROM db_column_meta "
                    "WHERE datasource_id=%s AND snapshot_id=%s", (datasource, sid))
        columns = cur.fetchall()
    detail = api.call("GET", f"/api/admin/metadata/snapshots/{sid}")
    actual = detail["snapshot"]
    if (actual.get("id"), actual.get("datasourceId"), actual.get("snapshotVersion"), actual.get("status")) != snapshot:
        raise ValueError("API snapshot does not match the isolated database identity")
    api_tables = detail.get("tables") or []
    api_columns = detail.get("columns") or []
    if (len(api_tables) != len(tables) or {t[1] for t in tables} != set(expected)
            or {(t.get("id"), t.get("tableName")) for t in api_tables} != set(tables)):
        raise ValueError("API tables do not match the declared fixture snapshot")
    if (len(api_columns) != len(columns)
            or {(c.get("id"), c.get("tableMetaId"), c.get("tableName"), c.get("columnName")) for c in api_columns} != set(columns)
            or {(table, name) for _, _, table, name in columns} != {(table, name) for table, names in expected.items() for name in names}
            or any(c.get("datasourceId") != datasource or c.get("snapshotId") != sid for c in api_columns)):
        raise ValueError("API columns do not match the declared fixture snapshot")
    table_ids = dict(tables)
    if any(table_ids.get(table_id) != table for _, table_id, table, _ in columns):
        raise ValueError("Column table ownership does not match the fixture snapshot")
    return {"datasourceId": datasource, "snapshotId": sid,
            "columns": {c["tableName"] + "." + c["columnName"]: c for c in api_columns}}
