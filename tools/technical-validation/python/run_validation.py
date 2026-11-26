"""真实本机技术链路验证，运行日志和秘密保留在仓库外。"""

import argparse
import asyncio
import contextlib
import importlib.metadata
import json
import os
import re
import secrets
import socket
import subprocess
import sys
import time
import traceback
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx
import jwt
import pymysql
import redis
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from langgraph.checkpoint.redis.aio import AsyncRedisSaver
from pymilvus import MilvusClient
from pymilvus.exceptions import MilvusException


def mysql(sql):
    # 验证端只使用 schema 级临时账号，避免依赖 Python 子进程访问 Docker 管道。
    with (
        pymysql.connect(
            host="127.0.0.1",
            port=3306,
            user=FIXTURE["run_id"],
            password=FIXTURE["database_password"],
            autocommit=True,
            client_flag=pymysql.constants.CLIENT.MULTI_STATEMENTS,
        ) as connection,
        connection.cursor() as cursor,
    ):
        cursor.execute(sql)
        rows = []
        while True:
            if cursor.description:
                rows.extend(
                    "\t".join(str(value) for value in row) for row in cursor.fetchall()
                )
            if not cursor.nextset():
                break
        return "\n".join(rows)


class Validation:
    def __init__(self, runtime, report, java, jar, storage_faults=False):
        self.runtime = runtime.resolve()
        self.report = report.resolve()
        self.java = java
        self.jar = jar.resolve()
        self.storage_faults = storage_faults
        self.run_id = FIXTURE["run_id"]
        self.directory = self.runtime / self.run_id
        self.directory.mkdir(parents=True, exist_ok=True)
        self.report.mkdir(parents=True, exist_ok=True)
        self.secret_directory = self.directory / "secrets"
        self.secret_directory.mkdir(exist_ok=True)
        self.temporary_directory = self.directory / "tmp"
        self.temporary_directory.mkdir(exist_ok=True)
        self.schema = "dataocean_" + self.run_id
        self.prefix = "do:" + self.run_id
        self.client = httpx.Client(timeout=15, trust_env=False)
        self.redis = redis.Redis.from_url(
            "redis://127.0.0.1:6379/0", decode_responses=True
        )
        self.checkpoint_redis = redis.Redis.from_url(
            "redis://127.0.0.1:6380/0", decode_responses=True
        )
        self.results = []
        self.processes = {}
        self.logs = contextlib.ExitStack()
        self.collections = []
        self.task_ids = []
        self.config = {
            "prefix": self.prefix,
            "redis_url": "redis://127.0.0.1:6379/0",
            "checkpoint_redis_url": "redis://127.0.0.1:6380/0",
            "java_url": "http://127.0.0.1:18081",
            "python_port": 18082,
            "secret_directory": str(self.secret_directory),
        }
        self.config_path = self.directory / "probe.json"
        self.config_path.write_text(json.dumps(self.config), encoding="utf-8")

    def record(self, name, function):
        started = time.monotonic()
        try:
            detail = function()
            result = {"name": name, "status": "passed", "detail": detail}
        except Exception as error:  # noqa: BLE001 -- 单项失败须保留证据并继续独立检查。
            # 报告只记错误类型；详细服务日志留在受控运行目录。
            result = {
                "name": name,
                "status": "failed",
                "error_type": type(error).__name__,
                "reason": str(error)
                if isinstance(error, AssertionError)
                else "see runtime logs",
            }
            with (self.directory / "diagnostic.log").open(
                "a", encoding="utf-8"
            ) as diagnostic:
                diagnostic.write(name + "\n" + traceback.format_exc() + "\n")
        result["seconds"] = round(time.monotonic() - started, 2)
        self.results.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
        self.save()
        return result["status"] == "passed"

    def save(self):
        data = {
            "run_id": self.run_id,
            "schema": self.schema,
            "results": self.results,
            "runtime_directory": str(self.directory),
            "versions": self.versions(),
        }
        (self.report / "verification.json").write_text(
            json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
        )

    def versions(self):
        return {
            name: importlib.metadata.version(name)
            for name in (
                "fastapi",
                "langgraph",
                "langgraph-checkpoint",
                "langgraph-checkpoint-redis",
                "pymilvus",
                "uvicorn",
                "redis",
                "pydantic",
                "httpx",
                "PyJWT",
                "cryptography",
            )
        }

    def prepare(self):
        for port in (18081, 18082):
            with socket.socket() as check:
                assert check.connect_ex(("127.0.0.1", port)) != 0, (
                    f"probe port {port} already occupied"
                )
        info = FIXTURE["mysql"]
        assert info["State"]["Running"]
        assert info["Config"]["Image"] == "mysql:8.0", "unexpected MySQL container"
        assert any(m["Name"] == "dataocean-shared-mysql-data" for m in info["Mounts"])
        database_version = mysql("SELECT VERSION()")
        assert (
            mysql(
                f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{self.schema}'"
            )
            == "0"
        )
        assert re.fullmatch(r"dataocean_tech_[0-9a-f]{12}", self.schema)
        mysql(f"""
            CREATE DATABASE `{self.schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
            USE `{self.schema}`;
            CREATE TABLE probe_marker (run_id VARCHAR(64) PRIMARY KEY);
            INSERT INTO probe_marker VALUES ('{self.run_id}');
            CREATE TABLE probe_items(id BIGINT PRIMARY KEY, label VARCHAR(64));
            INSERT INTO probe_items VALUES (1,' one '),(2,' two '),(3,' three '),(4,' four '),(5,' five ');
            CREATE TABLE probe_tasks(
                request_id VARCHAR(100) PRIMARY KEY,command_id VARCHAR(100) UNIQUE,kind VARCHAR(30),
                status VARCHAR(30),run_version INT,prepare_count INT DEFAULT 0,complete_count INT DEFAULT 0);
            CREATE TABLE probe_manifest(id INT PRIMARY KEY,version INT,generation VARCHAR(64),collection_name VARCHAR(100));
            INSERT INTO probe_manifest VALUES(1,0,'none','none');
        """)
        for service in ("java", "python"):
            key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
            (self.secret_directory / f"{service}-private.pem").write_bytes(
                key.private_bytes(
                    serialization.Encoding.PEM,
                    serialization.PrivateFormat.PKCS8,
                    serialization.NoEncryption(),
                )
            )
            (self.secret_directory / f"{service}-public.pem").write_bytes(
                key.public_key().public_bytes(
                    serialization.Encoding.PEM,
                    serialization.PublicFormat.SubjectPublicKeyInfo,
                )
            )
        # JDBC 只使用本轮生成、仅授权本轮 schema 的临时账号，不导出既有 root 密码。
        self.database_password = FIXTURE["database_password"]
        (self.secret_directory / "spring.datasource.password").write_text(
            self.database_password, encoding="utf-8"
        )
        (self.secret_directory / "probe.password").write_text(
            secrets.token_urlsafe(32), encoding="utf-8"
        )
        properties = {
            "server.address": "127.0.0.1",
            "server.port": "18081",
            "spring.datasource.url": f"jdbc:mysql://127.0.0.1:3306/{self.schema}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
            "spring.datasource.username": self.run_id,
            "spring.data.redis.host": "127.0.0.1",
            "spring.data.redis.port": "6379",
            "spring.data.redis.timeout": "2s",
            "spring.data.redis.connect-timeout": "1s",
            "spring.session.data.redis.namespace": self.prefix + ":sessions",
            "spring.session.timeout": "30m",
            "server.servlet.session.cookie.name": "PROBESESSION",
            "server.servlet.session.cookie.http-only": "true",
            "server.servlet.session.cookie.same-site": "lax",
            "probe.secret-directory": self.secret_directory.as_posix(),
            "probe.python-url": "http://127.0.0.1:18082",
            "spring.main.banner-mode": "off",
            "logging.level.root": "WARN",
        }
        self.properties_path = self.directory / "application.properties"
        self.properties_path.write_text(
            "\n".join(f"{k}={v}" for k, v in properties.items()), encoding="utf-8"
        )
        self.indexes_before = set(self.checkpoint_redis.execute_command("FT._LIST"))
        for client in (self.redis, self.checkpoint_redis):
            assert client.info("server")["redis_version"] == "8.10.3"
            assert client.config_get("appendonly")["appendonly"] == "yes"
            assert client.config_get("appendfsync")["appendfsync"] == "always"
            assert (
                client.config_get("maxmemory-policy")["maxmemory-policy"]
                == "noeviction"
            )
        return {
            "mysql": database_version,
            "redis": self.redis.info("server")["redis_version"],
            "redis_appendonly": self.redis.config_get("appendonly")["appendonly"],
            "redis_appendfsync": self.redis.config_get("appendfsync")["appendfsync"],
            "checkpoint_redis": self.checkpoint_redis.info("server")["redis_version"],
            "schema": self.schema,
            "isolated_prefix": self.prefix,
        }

    def spawn(self, name, arguments):
        log = self.logs.enter_context(
            (self.directory / (name + ".log")).open("ab", buffering=0)
        )
        environment = os.environ.copy()
        environment["PROBE_CONFIG"] = str(self.config_path)
        environment["TMP"] = str(self.temporary_directory)
        environment["TEMP"] = str(self.temporary_directory)
        self.processes[name] = subprocess.Popen(
            arguments,
            env=environment,
            stdout=log,
            stderr=log,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
        return self.processes[name]

    def stop(self, name):
        process = self.processes.get(name)
        if process and process.poll() is None:
            # Windows venv 的 python.exe 可能是启动器，必须终止本轮进程树。
            subprocess.run(
                ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                capture_output=True,
                check=False,
            )
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def wait(self, function, seconds=40):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                value = function()
                if value:
                    return value
            except httpx.HTTPError, ConnectionError:
                pass
            time.sleep(0.15)
        raise AssertionError("condition not reached within verification deadline")

    def start_java(self):
        self.spawn(
            "java",
            [
                self.java,
                "-Djava.io.tmpdir=" + str(self.temporary_directory),
                "-jar",
                str(self.jar),
                "--spring.config.location=" + self.properties_path.as_uri(),
                "--spring.config.import=configtree:"
                + self.secret_directory.as_posix()
                + "/",
            ],
        )
        self.wait(
            lambda: (
                self.client.get(self.config["java_url"] + "/probe/health").status_code
                == 200
            )
        )

    def start_python(self):
        self.spawn("api", [sys.executable, str(Path(__file__).with_name("chain.py"))])
        self.wait(
            lambda: self.client.get("http://127.0.0.1:18082/health").status_code == 200
        )
        self.spawn(
            "worker",
            [sys.executable, str(Path(__file__).with_name("chain.py")), "worker"],
        )
        self.wait(lambda: self.redis.get(self.prefix + ":worker-ready"), seconds=45)
        api_pid = self.client.get("http://127.0.0.1:18082/health").json()["pid"]
        worker_pid = int(self.redis.get(self.prefix + ":worker-ready"))
        assert api_pid != worker_pid
        return {
            "java": self.processes["java"].pid,
            "api": api_pid,
            "worker": worker_pid,
        }

    def get(self, path):
        response = self.client.get(self.config["java_url"] + path)
        response.raise_for_status()
        return response.json()

    def post(self, path, data=None):
        csrf = self.get("/probe/csrf")
        return self.client.post(
            self.config["java_url"] + path,
            json=data,
            headers={csrf["header"]: csrf["token"]},
        )

    def login(self):
        assert self.client.get(self.config["java_url"] + "/probe/me").status_code == 401
        csrf = self.get("/probe/csrf")
        old_cookie = self.client.cookies.get("PROBESESSION")
        response = self.client.post(
            self.config["java_url"] + "/login",
            data={
                "username": "probe",
                "password": (self.secret_directory / "probe.password").read_text(),
                csrf["parameter"]: csrf["token"],
            },
        )
        assert response.status_code == 204, "login was rejected"
        assert self.client.cookies.get("PROBESESSION") != old_cookie, (
            "session ID was not rotated"
        )
        assert self.get("/probe/me")["username"] == "probe"
        assert list(self.redis.scan_iter(self.prefix + ":sessions:*")), (
            "session namespace was not applied"
        )
        page = self.get("/probe/items")
        assert page == {
            "total": 5,
            "records": [{"id": "3", "label": "three"}, {"id": "4", "label": "four"}],
        }, "MyBatis pagination mismatch"
        assert (
            self.client.post(
                self.config["java_url"] + "/probe/manifest", json={}
            ).status_code
            == 403
        )
        return {
            "anonymous": 401,
            "login": 204,
            "session_rotated": True,
            "csrf_rejected": 403,
            "pagination_total": page["total"],
            "page_ids": [r["id"] for r in page["records"]],
        }

    def token(self, service, **overrides):
        now = int(time.time())
        claims = {
            "iss": "probe-" + service,
            "aud": "probe-python" if service == "java" else "probe-java",
            "sub": service + "-service",
            "iat": now,
            "exp": now + 300,
        }
        claims.update(overrides)
        return jwt.encode(
            claims,
            (self.secret_directory / (service + "-private.pem")).read_bytes(),
            algorithm="RS256",
        )

    def service_auth(self):
        cases = [
            ("missing", ""),
            ("wrong_issuer", self.token("java", iss="other")),
            ("wrong_audience", self.token("java", aud="other")),
            ("expired", self.token("java", exp=int(time.time()) - 120)),
            (
                "wrong_signer",
                self.token("python", iss="probe-java", aud="probe-python"),
            ),
        ]
        for name, token in cases:
            response = httpx.get(
                "http://127.0.0.1:18082/internal/tasks/invalid/events",
                headers={"Authorization": "Bearer " + token} if token else {},
                trust_env=False,
            )
            assert response.status_code == 401, name + " accepted by Python"
        for name, token in [
            ("missing", ""),
            ("expired", self.token("python", exp=int(time.time()) - 120)),
            ("wrong_audience", self.token("python", aud="other")),
            ("wrong_signer", self.token("java", iss="probe-python", aud="probe-java")),
        ]:
            response = httpx.get(
                self.config["java_url"] + "/internal/tasks/invalid",
                headers={"Authorization": "Bearer " + token} if token else {},
                trust_env=False,
            )
            assert response.status_code == 401, name + " accepted by Java"
        return {
            "python_rejections": len(cases),
            "java_rejections": 4,
            "algorithm": "RS256",
        }

    def new_task(self, kind):
        task_id = self.run_id + "_" + kind
        self.task_ids.append(task_id)
        command = {
            "requestId": task_id,
            "commandId": task_id + "_start",
            "kind": kind,
            "runVersion": 1,
        }
        response = self.post("/probe/tasks", command)
        response.raise_for_status()
        return task_id, command

    def state(self, task_id):
        return self.get("/probe/tasks/" + task_id)

    def wait_status(self, task_id, status):
        return self.wait(
            lambda: (
                self.state(task_id) if self.state(task_id)["status"] == status else None
            )
        )

    def read_events(self, task_id, cursor="0-0", limit=None):
        events = []
        current_id = None
        with self.client.stream(
            "GET",
            self.config["java_url"] + "/probe/tasks/" + task_id + "/events",
            headers={"Last-Event-ID": cursor},
            timeout=20,
        ) as response:
            response.raise_for_status()
            for line in response.iter_lines():
                if line.startswith("id:"):
                    current_id = line[3:].strip()
                if line.startswith("data:"):
                    events.append(
                        {"id": current_id, "data": json.loads(line[5:].strip())}
                    )
                    if limit and len(events) >= limit:
                        break
        return events

    def normal_and_sse(self):
        task_id, command = self.new_task("normal")
        first = self.read_events(task_id, limit=1)
        assert first, "SSE returned no event"
        final = self.wait_status(task_id, "COMPLETED")
        replay = self.read_events(task_id, cursor=first[-1]["id"])
        assert replay and all(e["id"] != first[-1]["id"] for e in replay), (
            "SSE cursor replay duplicated an event"
        )
        assert replay[-1]["data"]["stage"] == "COMPLETED"
        self.post("/probe/tasks", command).raise_for_status()
        time.sleep(0.5)
        assert self.state(task_id)["complete_count"] == 1
        conflict = command | {"kind": "checkpoint"}
        assert self.post("/probe/tasks", conflict).status_code == 409
        assert final["prepare_count"] == 1 and final["complete_count"] == 1
        return {
            "first_events": len(first),
            "replayed_events": len(replay),
            "prepare_count": 1,
            "complete_count": 1,
            "command_conflict": 409,
        }

    def cancellation(self):
        task_id, _ = self.new_task("cancel")
        self.wait_status(task_id, "RUNNING")
        self.post("/probe/tasks/" + task_id + "/cancel").raise_for_status()
        final = self.wait_status(task_id, "CANCELLED")
        assert final["complete_count"] == 0
        late = httpx.post(
            self.config["java_url"] + "/internal/stage",
            headers={"Authorization": "Bearer " + self.token("python")},
            json={"requestId": task_id, "runVersion": 1, "stage": "COMPLETED"},
            trust_env=False,
        )
        assert late.status_code == 409, "late completion accepted after cancellation"
        return {"status": final["status"], "complete_count": 0, "late_completion": 409}

    def storage_fault(self, action):
        # Docker 操作由外层 PowerShell 执行；子进程只提出本轮固定故障步骤。
        operation_id = uuid.uuid4().hex
        request = self.report / "storage-fault.request.json"
        response = self.report / "storage-fault.response.json"
        temporary = self.report / "storage-fault.request.tmp"
        temporary.write_text(
            json.dumps({"id": operation_id, "action": action, "run_id": self.run_id}),
            encoding="utf-8",
        )
        temporary.replace(request)

        def received():
            if not response.exists():
                return None
            result = json.loads(response.read_text(encoding="utf-8"))
            return result if result.get("id") == operation_id else None

        result = self.wait(received, seconds=150)
        assert result["ok"], "storage fault step failed: " + action
        return result

    def checkpoint_snapshot(self, task_id, port=6380):
        async def read():
            async with AsyncRedisSaver.from_conn_string(
                f"redis://127.0.0.1:{port}/0",
                checkpoint_prefix=self.prefix + ":checkpoint",
                checkpoint_write_prefix=self.prefix + ":checkpoint_write",
            ) as saver:
                saved = await saver.aget_tuple({"configurable": {"thread_id": task_id}})
                assert saved is not None
                return {
                    "checkpoint_id": saved.checkpoint["id"],
                    "prepared": saved.checkpoint["channel_values"]["prepared"],
                    "completed": saved.checkpoint["channel_values"]["completed"],
                }

        return asyncio.run(read())

    def redis_fault_prepare(self, task_id):
        snapshot = self.checkpoint_snapshot(task_id)
        self.backup_checkpoint = (task_id, snapshot)
        assert snapshot["prepared"] and not snapshot["completed"]
        self.storage_fault("backup-redis")
        before = [
            client.info("server")["run_id"]
            for client in (self.redis, self.checkpoint_redis)
        ]
        stopped = self.storage_fault("redis-stop")
        assert stopped["exit_codes"] == [137, 137]
        for client in (self.redis, self.checkpoint_redis):
            try:
                client.ping()
            except redis.ConnectionError:
                continue
            raise AssertionError("Redis still reachable after SIGKILL")
        response = self.client.get(self.config["java_url"] + "/probe/me")
        assert response.status_code in (401, 500, 503), (
            "Redis outage allowed authenticated content"
        )
        durable = httpx.get(
            self.config["java_url"] + "/internal/tasks/" + task_id,
            headers={"Authorization": "Bearer " + self.token("python")},
            trust_env=False,
        )
        durable.raise_for_status()
        assert durable.json()["status"] == "WAITING"
        self.storage_fault("redis-start")
        after = [
            client.info("server")["run_id"]
            for client in (self.redis, self.checkpoint_redis)
        ]
        assert all(old != new for old, new in zip(before, after, strict=True))
        assert self.checkpoint_snapshot(task_id) == snapshot
        self.wait(lambda: self.get("/probe/me")["username"] == "probe")
        self.redis_fault_detail = {
            "exit_codes": stopped["exit_codes"],
            "outage_http_status": response.status_code,
            "checkpoint_id_preserved": True,
            "mysql_waiting_preserved": True,
            "session_survived": True,
            "both_redis_processes_changed": True,
        }

    def redis_backup_restore(self):
        task_id, snapshot = self.backup_checkpoint
        self.storage_fault("restore-checkpoint")
        assert self.checkpoint_snapshot(task_id, port=16380) == snapshot
        # 备份仍是 WAITING，权威 MySQL 已完成，旧检查点不能重新执行任务。
        assert self.state(task_id)["status"] == "COMPLETED"
        assert self.post("/probe/tasks/" + task_id + "/resume").status_code == 409
        self.storage_fault("remove-restore")
        return {
            "backup_checkpoint_id_preserved": True,
            "restored_prepared": True,
            "restored_completed": False,
            "completed_task_resume_rejected": 409,
        }

    def checkpoint_restart(self):
        task_id, _ = self.new_task("checkpoint")
        self.wait_status(task_id, "WAITING")
        old_pid = int(self.redis.get(self.prefix + ":worker-ready"))
        self.stop("worker")
        old_process = subprocess.run(
            ["tasklist", "/FI", f"PID eq {old_pid}", "/FO", "CSV", "/NH"],
            capture_output=True,
            text=True,
            check=True,
        )
        assert f'"{old_pid}"' not in old_process.stdout, "old worker is still running"
        if self.storage_faults:
            self.redis_fault_prepare(task_id)
        self.stop("java")
        self.start_java()
        assert self.get("/probe/me")["username"] == "probe", (
            "Redis session lost after Java restart"
        )
        assert self.state(task_id)["status"] == "WAITING", "MySQL task state lost"
        self.redis.delete(self.prefix + ":worker-ready")
        self.spawn(
            "worker",
            [sys.executable, str(Path(__file__).with_name("chain.py")), "worker"],
        )
        self.wait(lambda: self.redis.get(self.prefix + ":worker-ready"))
        new_pid = int(self.redis.get(self.prefix + ":worker-ready"))
        assert new_pid != old_pid
        self.post("/probe/tasks/" + task_id + "/resume").raise_for_status()
        final = self.wait_status(task_id, "COMPLETED")
        assert (
            final["run_version"] == 2
            and final["prepare_count"] == 1
            and final["complete_count"] == 1
        )
        stale = httpx.post(
            self.config["java_url"] + "/internal/stage",
            headers={"Authorization": "Bearer " + self.token("python")},
            json={"requestId": task_id, "runVersion": 1, "stage": "WAITING"},
            trust_env=False,
        )
        assert stale.status_code == 409
        result = {
            "old_worker_pid": old_pid,
            "new_worker_pid": new_pid,
            "run_version": 2,
            "prepare_count": 1,
            "complete_count": 1,
            "old_version_rejected": 409,
            "java_session_survived": True,
            "mysql_state_survived": True,
        }
        if self.storage_faults:
            result["storage_crash"] = self.redis_fault_detail
        return result

    def index_switch(self):
        client = MilvusClient(uri="http://127.0.0.1:19530")
        first, second = self.run_id + "_a", self.run_id + "_b"
        for name, doc_id in ((first, 101), (second, 202)):
            assert not client.has_collection(name)
            client.create_collection(
                name, dimension=4, metric_type="IP", consistency_level="Strong"
            )
            self.collections.append(name)
            client.insert(
                name,
                [{"id": doc_id, "vector": [1.0, 0.0, 0.0, 0.0], "generation": name}],
            )
            result = client.search(
                name, [[1.0, 0.0, 0.0, 0.0]], limit=1, consistency_level="Strong"
            )
            assert result[0][0]["id"] == doc_id, (
                "Strong read did not see prepared generation"
            )
        self.post(
            "/probe/manifest",
            {"expectedVersion": 0, "generation": "a", "collectionName": first},
        ).raise_for_status()
        pinned = self.get("/probe/manifest")
        assert pinned["collection_name"] == first
        # 使用真实非法向量触发构建失败，确认已生效清单保持原值。
        try:
            client.insert(second, [{"id": 303, "vector": [1.0, 0.0]}])
        except MilvusException as error:
            dimension_error_type = type(error).__name__
        else:
            raise AssertionError("Milvus accepted wrong vector dimensions")
        assert self.get("/probe/manifest") == pinned
        csrf = self.get("/probe/csrf")

        def switch(_):
            return self.client.post(
                self.config["java_url"] + "/probe/manifest",
                json={
                    "expectedVersion": 1,
                    "generation": "b",
                    "collectionName": second,
                },
                headers={csrf["header"]: csrf["token"]},
            ).status_code

        with ThreadPoolExecutor(max_workers=2) as executor:
            codes = sorted(executor.map(switch, range(2)))
        assert codes == [200, 409], "concurrent manifest compare-and-set failed"
        current = self.get("/probe/manifest")
        assert current["version"] == 2 and current["collection_name"] == second
        milvus_fault = None
        if self.storage_faults:
            milvus_fault = self.storage_fault("milvus-restart")
            assert milvus_fault["exit_codes"] == [137]
            assert self.get("/probe/manifest") == current
            client.close()
            client = MilvusClient(uri="http://127.0.0.1:19530")
            for name in (first, second):
                client.load_collection(name)
        assert (
            client.search(
                pinned["collection_name"],
                [[1.0, 0.0, 0.0, 0.0]],
                limit=1,
                consistency_level="Strong",
            )[0][0]["id"]
            == 101
        )
        assert (
            client.search(
                current["collection_name"],
                [[1.0, 0.0, 0.0, 0.0]],
                limit=1,
                consistency_level="Strong",
            )[0][0]["id"]
            == 202
        )
        self.post(
            "/probe/manifest",
            {"expectedVersion": 2, "generation": "a", "collectionName": first},
        ).raise_for_status()
        assert self.get("/probe/manifest")["collection_name"] == first
        client.close()
        return {
            "strong_search": True,
            "failed_build_preserved_manifest": True,
            "dimension_error_type": dimension_error_type,
            "concurrent_switch_codes": codes,
            "pinned_old_id": 101,
            "new_id": 202,
            "rollback": True,
            "milvus_sigkill": bool(milvus_fault),
        }

    def logout(self):
        response = self.post("/logout")
        assert response.status_code == 204
        assert self.client.get(self.config["java_url"] + "/probe/me").status_code == 401
        return {"logout": 204, "subsequent_request": 401}

    def cleanup(self):
        for name in ("worker", "api", "java"):
            self.stop(name)
        self.logs.close()
        # 只清理本轮已登记的独立 Collection；旧 Collection 从不使用通配删除。
        client = MilvusClient(uri="http://127.0.0.1:19530")
        for collection in self.collections:
            assert collection.startswith(self.run_id + "_")
            client.drop_collection(collection)
        client.close()
        keys = list(self.redis.scan_iter(self.prefix + ":*"))
        if keys:
            self.redis.delete(*keys)
        # 补充清理适配器管理的注册 key；只匹配本轮唯一标识。
        owned = list(self.checkpoint_redis.scan_iter("*" + self.run_id + "*"))
        if owned:
            self.checkpoint_redis.delete(*owned)
        for index in (
            set(self.checkpoint_redis.execute_command("FT._LIST")) - self.indexes_before
        ):
            if index.startswith(self.prefix + ":"):
                self.checkpoint_redis.execute_command("FT.DROPINDEX", index)
        assert mysql(f"SELECT run_id FROM `{self.schema}`.probe_marker") == self.run_id
        assert re.fullmatch(r"dataocean_tech_[0-9a-f]{12}", self.schema)
        mysql(f"DROP DATABASE `{self.schema}`")
        # 本轮一次性测试秘密完成后逐个删除，不保留在报告目录。
        for name in (
            "java-private.pem",
            "java-public.pem",
            "python-private.pem",
            "python-public.pem",
            "spring.datasource.password",
            "probe.password",
        ):
            (self.secret_directory / name).unlink(missing_ok=True)
        self.secret_directory.rmdir()
        return {
            "probe_processes_stopped": True,
            "probe_schema_removed": True,
            "probe_collections_removed": len(self.collections),
            "probe_redis_keys_removed": len(keys) + len(owned),
        }

    def run(self):
        prepared = self.record("environment_and_isolated_fixtures", self.prepare)
        try:
            if (
                prepared
                and self.record("java_real_startup", self.start_java)
                and self.record("session_csrf_and_mysql_pagination", self.login)
            ):
                if self.record("python_api_worker_real_startup", self.start_python):
                    self.record(
                        "bidirectional_service_jwt_rejections", self.service_auth
                    )
                    self.record(
                        "task_idempotency_and_sse_reconnect", self.normal_and_sse
                    )
                    self.record(
                        "cancellation_and_late_completion_rejection",
                        self.cancellation,
                    )
                    resumed = self.record(
                        "java_worker_restart_and_redis_checkpoint_resume",
                        self.checkpoint_restart,
                    )
                    if self.storage_faults and resumed:
                        self.record(
                            "redis_backup_restore_and_authoritative_resume_gate",
                            self.redis_backup_restore,
                        )
                self.record(
                    "milvus_strong_search_mysql_switch_and_rollback",
                    self.index_switch,
                )
                self.record("logout_invalidates_session", self.logout)
        finally:
            if prepared:
                self.record("isolated_fixture_cleanup", self.cleanup)
            else:
                for name in list(self.processes):
                    self.stop(name)
        self.save()
        return all(item["status"] == "passed" for item in self.results)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--java", required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--storage-faults", action="store_true")
    arguments = parser.parse_args()
    FIXTURE = json.loads(arguments.fixture.read_text(encoding="utf-8"))
    validation = Validation(
        arguments.runtime,
        arguments.report,
        arguments.java,
        arguments.jar,
        arguments.storage_faults,
    )
    sys.exit(0 if validation.run() else 1)
