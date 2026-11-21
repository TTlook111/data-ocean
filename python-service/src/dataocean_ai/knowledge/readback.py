"""在发布检查和切换后确认实际代次的内容及同版证据。"""

import os

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from dataocean_ai.governance.security import verify_java
from dataocean_ai.knowledge.index import Fragment, index_identity, indexed_rows

router = APIRouter(prefix="/internal/v1/knowledge-index", dependencies=[Depends(verify_java)])


class IndexSnapshot(BaseModel):
    """输入仅来自 Java 当前绑定，身份和内容均须完整一致。"""

    model_config = ConfigDict(hide_input_in_errors=True)

    # 实际组合的稳定身份及已固定的片段。
    candidate: str = Field(pattern="^[0-9]+$")
    revision: str = Field(pattern="^[0-9]+$")
    fingerprint: str = Field(pattern="^[0-9a-f]{64}$")
    embedding: str = Field(pattern="^[0-9]+$")
    collection: str = Field(pattern="^[A-Za-z][A-Za-z0-9_]{1,95}$")
    fragments: list[Fragment] = Field(min_length=1, max_length=12, repr=False)


class Readback(BaseModel):
    """确认回执只提供可复核的身份、指纹和实际片段数。"""

    collection: str
    fingerprint: str
    verified: int


@router.post("/readback", response_model=Readback)
def readback(snapshot: IndexSnapshot) -> Readback:
    """用 Strong 读取现有代次，不生成或修改索引。"""
    os.environ["PYTHON_DOTENV_DISABLED"] = "1"
    from pymilvus import MilvusClient
    from pymilvus.exceptions import MilvusException

    uri = os.getenv("AI_MILVUS_URI")
    if not uri:
        # 缺少实际存储时无法认可切换证据。
        raise HTTPException(status_code=503, detail="INDEX_UNAVAILABLE")
    identity = index_identity(
        snapshot.candidate,
        snapshot.revision,
        snapshot.fingerprint,
        snapshot.embedding,
        snapshot.collection,
    )
    expected = indexed_rows(identity, snapshot.fragments)
    if len(expected) != len(snapshot.fragments):
        # 重复片段编号无法证明完整性。
        raise HTTPException(status_code=409, detail="INDEX_READBACK_MISMATCH")
    client = None
    try:
        client = MilvusClient(uri=uri, token=os.getenv("AI_MILVUS_TOKEN", ""), timeout=3)
        rows = client.query(
            snapshot.collection,
            filter="",
            limit=len(expected) + 1,
            output_fields=list(next(iter(expected.values()))),
            consistency_level="Strong",
            timeout=4,
        )
        if len(rows) != len(expected) or {row["id"]: row for row in rows} != expected:
            # 正文、治理依据或身份有任一不一致，都不返回成功回执。
            raise HTTPException(status_code=409, detail="INDEX_READBACK_MISMATCH")
        return Readback(
            collection=snapshot.collection, fingerprint=snapshot.fingerprint, verified=len(rows)
        )
    except (MilvusException, OSError, TimeoutError):
        # 对外只保留稳定错误，连接和实际内容不外露。
        raise HTTPException(status_code=503, detail="INDEX_UNAVAILABLE") from None
    except (ValueError, KeyError):
        raise HTTPException(status_code=409, detail="INDEX_READBACK_MISMATCH") from None
    finally:
        # 收回内部客户端连接。
        if client is not None:
            client.close()
