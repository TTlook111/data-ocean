"""Launch one immutable acceptance JAR with the existing private local config."""
import hashlib
import json
import os
import sys
import subprocess
from pathlib import Path

import acceptance as entry


def main():
    runtime=entry.ROOT.parent/"DataOcean-runtime"
    profile=json.loads((runtime/"crash-runtime-profile.json").read_text(encoding="utf-8"))
    jar=Path(profile["jar"]).resolve()
    if not jar.is_relative_to(runtime.resolve()) or hashlib.sha256(jar.read_bytes()).hexdigest()!=profile["jarSha256"]:
        raise ValueError("Acceptance JAR identity changed")
    cfg=entry.private_config(Path(profile["privateEnv"]))
    secondary=runtime/"system-acceptance-secondary.private.json"
    report=entry.preflight(cfg,json.loads(secondary.read_text(encoding="utf-8")) if secondary.exists() else None)
    if not report["passed"]:raise ValueError("Isolated acceptance preflight failed")
    local=entry.ROOT/"backend/DataOcean/src/main/resources/application-local.yml"
    java=Path(profile["javaHome"])/"bin/java.exe"
    if not local.is_file() or not java.is_file():raise ValueError("Verified local Java configuration is missing")
    env=os.environ.copy()
    env.update(SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:3306/"+entry.APP_SCHEMA+"?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
        SPRING_DATASOURCE_USERNAME=cfg["LANGGRAPH_REVIEW_MYSQL_ROOT_USER"],
        SPRING_DATASOURCE_PASSWORD=cfg["LANGGRAPH_REVIEW_MYSQL_ROOT_PASSWORD"],SPRING_FLYWAY_TARGET=str(report["code_migration"]))
    args=[str(java),"-XX:TieredStopAtLevel=1","-Dfile.encoding=UTF-8","-jar",str(jar),"--server.address=127.0.0.1",
          "--spring.config.additional-location="+local.as_uri()]
    print(json.dumps({"event":"immutable-acceptance-java","jarSha256":profile["jarSha256"],"schema":entry.APP_SCHEMA}),flush=True)
    subprocess.run(args,env=env,stdout=sys.stdout,stderr=sys.stderr,check=True)


if __name__=="__main__":main()
