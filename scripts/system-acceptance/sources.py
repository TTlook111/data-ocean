"""Explicit second synthetic schema inside the verified, already-running MySQL.

No container, volume, production schema, existing account or existing data is
created/replaced/reset here. Repetition verifies the exact fixture instead.
"""
import json
from pathlib import Path
import secrets
import time
import pymysql
import acceptance as entry

def run(api):
    assert api.cfg["LANGGRAPH_REVIEW_APP_SCHEMA"]==entry.APP_SCHEMA
    path=api.output.parent/"system-acceptance-secondary.private.json"
    if path.exists():
        profile=json.loads(path.read_text(encoding="utf-8"))
    else:
        profile={"schema":entry.SECONDARY_SCHEMA,"username":"acc_switch_reader_20261028",
                 "password":"Cc3"+secrets.token_urlsafe(15)}
        with entry.connect(api.cfg) as db,db.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s",(entry.SECONDARY_SCHEMA,))
            assert cur.fetchone()[0]==0,"Named secondary schema exists without its local test provenance; leave it untouched"
            cur.execute("SELECT COUNT(*) FROM mysql.user WHERE User=%s",(profile["username"],))
            assert cur.fetchone()[0]==0,"Named account already exists; do not replace its credentials"
            path.write_text(json.dumps(profile,indent=2),encoding="utf-8")
            cur.execute("CREATE DATABASE `langgraph_fixture_switch_20261028` CHARACTER SET utf8mb4")
            cur.execute("CREATE TABLE `langgraph_fixture_switch_20261028`.`products` (product_id BIGINT PRIMARY KEY,product_name VARCHAR(100),category VARCHAR(100))")
            cur.execute("INSERT INTO `langgraph_fixture_switch_20261028`.`products` VALUES (1,'SecondaryOnlyA','SecondSource'),(2,'SecondaryOnlyB','SecondSource')")
            cur.execute("CREATE USER %s@'%%' IDENTIFIED BY %s",(profile["username"],profile["password"]))
            cur.execute("GRANT SELECT ON `langgraph_fixture_switch_20261028`.`products` TO %s@'%%'",(profile["username"],))
            db.commit()
    with entry.secondary_connection(profile) as db,db.cursor() as cur:
        cur.execute("SET SESSION TRANSACTION READ ONLY")
        cur.execute("SHOW GRANTS FOR CURRENT_USER()")
        entry.assert_read_only_grants([r[0] for r in cur.fetchall()],entry.SECONDARY_SCHEMA,("products",))
        cur.execute("SELECT product_id,product_name,category FROM products ORDER BY product_id")
        assert cur.fetchall()==((1,'SecondaryOnlyA','SecondSource'),(2,'SecondaryOnlyB','SecondSource'))
    sources=api.call("GET","/api/admin/datasources?pageNo=1&pageSize=100")["records"]
    secondary=next((s for s in sources if s["databaseName"]==entry.SECONDARY_SCHEMA),None)
    if secondary is None:
        secondary=api.call("POST","/api/admin/datasources",{"name":"System Acceptance Secondary 20261028",
            "description":"Synthetic second database; no RAG, no business grants; source-switch isolation test",
            "host":"127.0.0.1","port":3306,"databaseName":entry.SECONDARY_SCHEMA,
            "username":profile["username"],"password":profile["password"]})
    source_id=secondary["id"]
    snapshots=api.call("GET",f"/api/admin/metadata/snapshots?datasourceId={source_id}&pageNo=1&pageSize=100")["records"]
    task=None
    if not snapshots:
        task=api.call("POST","/api/admin/metadata/sync",{"datasourceId":source_id,"includeStatistics":True})
        deadline=time.monotonic()+60
        while time.monotonic()<deadline:
            snapshots=api.call("GET",f"/api/admin/metadata/snapshots?datasourceId={source_id}&pageNo=1&pageSize=100")["records"]
            if snapshots:break
            time.sleep(1)
    assert snapshots and all(s["datasourceId"]==source_id for s in snapshots)
    knowledge=api.call("GET",f"/api/admin/knowledge-docs?datasourceId={source_id}&pageSize=100")
    assert knowledge["total"]==0 and not knowledge["records"],"Knowledge list leaked the primary datasource"
    assert not api.call("GET",f"/api/admin/knowledge-docs/rag-builds?datasourceId={source_id}")
    lineage=api.call("GET",f"/api/lineage/table/products?datasourceId={source_id}")
    assert not lineage,"Same-name table lineage leaked from the primary datasource"
    response=api.client.post("/api/iam-s1/query/ask",json={"protocolVersion":"IAM-SIMPLE-1","datasourceId":source_id,"question":"产品有多少条？"})
    assert response.status_code in {400,403,409} and response.json().get("data") is None,"Unpublished/ungranted secondary source became queryable"
    primary=api.call("GET","/api/admin/datasources/1/readiness")
    result={"passed":True,"primaryDatasource":1,"secondaryDatasource":source_id,"schema":entry.SECONDARY_SCHEMA,
        "secondaryRows":2,"syncTask":task,"snapshots":[{"id":s["id"],"status":s["status"]} for s in snapshots],
        "secondaryKnowledgeCount":0,"secondaryLineageCount":0,"secondaryQueryRefusedHttp":response.status_code,
        "primaryReadiness":primary,"limit":"Two real synthetic databases and metadata/list/readiness switching; second source intentionally has no published knowledge or query grant"}
    (api.output/"multi-source.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
    return result
