"""Atomic local crash-control transport; contains no credentials."""
import json
import os
import time
import uuid


def read_control(path):
    for attempt in range(6):
        try:return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        except (OSError,ValueError):
            if attempt==5:raise
            time.sleep(.01)


def write_control(path,value):
    temporary=path.with_name(path.name+"."+uuid.uuid4().hex+".tmp")
    temporary.write_text(json.dumps(value,indent=2),encoding="utf-8")
    try:
        for attempt in range(6):
            try:os.replace(temporary,path);return
            except PermissionError:
                if attempt==5:raise
                time.sleep(.01)
    finally:
        if temporary.exists():temporary.unlink()
