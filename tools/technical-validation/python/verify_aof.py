"""证明 RDB 快照之后的新写入可从 AOF 恢复；仅用于空的本机验证实例。"""

import argparse
import json
import uuid
from pathlib import Path

import redis


def seed(directory):
    state = {
        "run_id": "tech_" + uuid.uuid4().hex[:12],
        "nonce": uuid.uuid4().hex,
        "instances": {},
    }
    for name, port in (("redis", 6379), ("redis-checkpoint", 6380)):
        client = redis.Redis(host="127.0.0.1", port=port, decode_responses=True)
        assert client.dbsize() == 0, "AOF check requires a clean verification instance"
        assert client.config_get("appendonly")["appendonly"] == "yes"
        assert client.config_get("appendfsync")["appendfsync"] == "always"
        # 先固定旧快照，再写三种新数据；强杀恢复不能依赖旧快照中的内容。
        client.save()
        prefix = "do:" + state["run_id"]
        client.set(prefix + ":string", state["nonce"])
        client.json().set(prefix + ":json", "$", {"nonce": state["nonce"]})
        client.xadd(prefix + ":stream", {"nonce": state["nonce"]})
        changes = client.info("persistence")["rdb_changes_since_last_save"]
        assert changes >= 3, "RDB was refreshed after the test writes"
        state["instances"][name] = {
            "port": port,
            "run_id": client.info("server")["run_id"],
            "post_snapshot_changes": changes,
        }
    (directory / "aof-state.json").write_text(json.dumps(state), encoding="utf-8")
    print("Post-snapshot AOF writes prepared on both Redis instances.")


def verify(directory):
    state = json.loads((directory / "aof-state.json").read_text(encoding="utf-8"))
    result = {"status": "passed", "instances": {}}
    prefix = "do:" + state["run_id"]
    for name, before in state["instances"].items():
        client = redis.Redis(
            host="127.0.0.1", port=before["port"], decode_responses=True
        )
        assert client.info("server")["run_id"] != before["run_id"]
        assert client.info("persistence")["aof_enabled"] == 1
        assert client.get(prefix + ":string") == state["nonce"]
        assert client.json().get(prefix + ":json")["nonce"] == state["nonce"]
        assert client.xrange(prefix + ":stream")[0][1]["nonce"] == state["nonce"]
        client.delete(prefix + ":string", prefix + ":json", prefix + ":stream")
        assert client.dbsize() == 0
        result["instances"][name] = {
            "post_snapshot_changes": before["post_snapshot_changes"],
            "string_restored": True,
            "json_restored": True,
            "stream_restored": True,
            "remaining_keys": 0,
        }
    (directory / "aof-post-snapshot-verification.json").write_text(
        json.dumps(result, indent=2), encoding="utf-8"
    )
    print(json.dumps(result))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=("seed", "verify"))
    parser.add_argument("directory", type=Path)
    arguments = parser.parse_args()
    if arguments.phase == "seed":
        seed(arguments.directory)
    else:
        verify(arguments.directory)
