"""按固定候选构建独立代次，完成 Strong 回读后才交付回执。"""

import asyncio
import json
from typing import TypedDict

import httpx
from langgraph.graph import END, START, StateGraph
from langgraph.runtime import Runtime
from langsmith import tracing_context
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from dataocean_ai.adapters.chat import ModelConfiguration, ModelFailure
from dataocean_ai.adapters.embedding import embed
from dataocean_ai.adapters.milvus import vector_client
from dataocean_ai.governance.security import worker_token

# 单批有界。换批和写索引之前再次从 Java 核查当前任务资格。
BATCH_SIZE = 8


class Evidence(BaseModel):
    """回读治理事实时必需的稳定对象与修订。"""

    # 普通同名列和结构版本不能替换当前事实。
    objectId: str  # noqa: N815
    generation: int
    kind: str
    targetId: str  # noqa: N815
    factRevisionId: str  # noqa: N815
    structureRevisionId: str  # noqa: N815


class Fragment(BaseModel):
    """章节及条件作为一个完整片段进入独立索引。"""

    # 稳定章节顺序和同版证据。
    ordinal: int = Field(ge=0)
    body: str = Field(min_length=1)
    evidence: list[Evidence] = Field(min_length=1)
    digest: str = Field(pattern="^[0-9a-f]{64}$")


class IndexInput(BaseModel):
    """仅从受认证 Java 任务领取的固定执行输入。"""

    # 错误信息隐藏正文、连接值及凭据。
    model_config = ConfigDict(hide_input_in_errors=True)

    # 源候选和确切文档、模型与目标位置。
    candidateId: str = Field(pattern="^[0-9]+$")  # noqa: N815
    documentRevisionId: str = Field(pattern="^[0-9]+$")  # noqa: N815
    fingerprint: str = Field(pattern="^[0-9a-f]{64}$")
    collection: str = Field(pattern="^[A-Za-z][A-Za-z0-9_]{1,95}$")
    question: str = Field(min_length=1)
    fragments: list[Fragment] = Field(min_length=1, max_length=12)
    model: ModelConfiguration = Field(repr=False)
    remainingSeconds: int = Field(ge=1, le=180)  # noqa: N815


class Verified(BaseModel):
    """Strong 回读的片段编号与校验摘要。"""

    # 确切片段身份证明。
    ordinal: int
    digest: str


class IndexReceipt(BaseModel):
    """去除正文与向量，仅返回 Java 可二次核对的构建结果。"""

    # 隔离代次和对应候选的绑定。
    collection: str
    fingerprint: str
    dimension: int
    observed: list[Verified]
    hits: list[int]


class IndexState(TypedDict, total=False):
    """图只保留这一轮的临时向量和可核对回执。"""

    # 模型配置、正文及连接放在 Runtime，不写入图状态或外部追踪。
    vectors: list[list[float]]
    receipt: IndexReceipt


class IndexContext(TypedDict):
    """工作者本次调用依赖，保持固定目的地及授权。"""

    # Java 任务 URL 由部署提供，不能从文档或模型结果决定。
    endpoint: str
    run: int
    source: IndexInput
    client: httpx.AsyncClient


async def authority(client: httpx.AsyncClient, endpoint: str, action: str, run: int):
    """用独立工作者身份取用或复核原构建任务。"""
    try:
        reply = await client.post(
            f"{endpoint}/{action}",
            params={"runVersion": run},
            headers={"Authorization": "Bearer " + worker_token()},
        )
        data = reply.json()
        if data.get("code") == 409403:
            # 已领取的上游调用结果未知，由新代次显式重试。
            raise ModelFailure("BUILD_INTERRUPTED")
        if not reply.is_success or data.get("code") != 200:
            # 旧代次、撤权或来源改变均不能取得当前调用资格。
            raise ModelFailure("INPUT_STALE")
        return data["data"]
    except (httpx.RequestError, ValueError, KeyError, TypeError):
        # 无法核实权威状态时停止后续外发。
        raise ModelFailure("BUILD_INTERRUPTED") from None


async def _vectors(_state: IndexState, runtime: Runtime[IndexContext]) -> IndexState:
    """分批向量化完整章节及本篇代表性问题。"""
    ctx = runtime.context
    job = ctx["source"]
    texts = [fragment.body for fragment in job.fragments] + [job.question]
    vectors: list[list[float]] = []
    for offset in range(0, len(texts), BATCH_SIZE):
        # 同一配置的下一批也必须仍然具备外发资格。
        await authority(ctx["client"], ctx["endpoint"], "recheck", ctx["run"])
        vectors.extend(await embed(job.model, texts[offset : offset + BATCH_SIZE]))
    if any(len(row) != len(vectors[0]) for row in vectors):
        # SDK 各批成功也不能混用不同维数。
        raise ModelFailure("MODEL_FORMAT")
    return {"vectors": vectors}


async def _index(state: IndexState, runtime: Runtime[IndexContext]) -> IndexState:
    """写入隔离 Milvus 代次并读回实际片段与代表性问题命中。"""
    ctx = runtime.context
    await authority(ctx["client"], ctx["endpoint"], "recheck", ctx["run"])
    receipt = await asyncio.to_thread(_persist, ctx["source"], state["vectors"])
    await authority(ctx["client"], ctx["endpoint"], "recheck", ctx["run"])
    return {"receipt": receipt}


def index_identity(
    candidate: str, revision: str, fingerprint: str, embedding: str, generation: str
) -> dict[str, str]:
    """构建与发布核验沿用相同的代次归属字段。"""
    return {
        "candidate": candidate,
        "revision": revision,
        "fingerprint": fingerprint,
        "embedding": embedding,
        "generation": generation,
    }


def indexed_rows(identity: dict[str, str], fragments: list[Fragment]) -> dict[int, dict]:
    """形成同版正文及治理引用的确切存储快照。"""
    return {
        fragment.ordinal: {
            **identity,
            "id": fragment.ordinal,
            "body": fragment.body,
            "evidence": json.dumps(
                [ref.model_dump() for ref in fragment.evidence], ensure_ascii=False
            ),
            "digest": fragment.digest,
        }
        for fragment in fragments
    }


def _persist(job: IndexInput, vectors: list[list[float]]) -> IndexReceipt:
    """在不可变候选代次内回读每个片段和实际向量搜索结果。"""
    from pymilvus.exceptions import MilvusException

    name = job.collection
    # 每条记录都包含独立归属，不依赖会变化的 Milvus 别名。
    identity = index_identity(
        job.candidateId, job.documentRevisionId, job.fingerprint, job.model.revision_id, name
    )
    payload = indexed_rows(identity, job.fragments)
    if len(payload) != len(job.fragments):
        # 重复编号是输入错误，不能覆盖其他必要章节。
        raise ModelFailure("INPUT_STALE")
    try:
        # 连接参数统一由适配器管理，超时仍由业务场景决定。
        with vector_client(8) as client:
            if client.has_collection(name, timeout=8):
                raise ModelFailure("INDEX_READBACK_MISMATCH")
            client.create_collection(
                name,
                dimension=len(vectors[0]),
                metric_type="COSINE",
                consistency_level="Strong",
                enable_dynamic_field=True,
                timeout=12,
            )
            client.insert(
                name,
                [
                    {**payload[fragment.ordinal], "vector": vector}
                    for fragment, vector in zip(job.fragments, vectors[:-1], strict=True)
                ],
                timeout=12,
            )
            stored = client.query(
                name,
                filter="",
                limit=len(payload) + 1,
                output_fields=list(next(iter(payload.values()))),
                consistency_level="Strong",
                timeout=10,
            )
            readback = {row["id"]: row for row in stored}
            if len(stored) != len(payload) or readback != payload:
                # 写入回执不能代替 Strong 读取到的实际完整正文和依据。
                raise ModelFailure("INDEX_READBACK_MISMATCH")
            matches = client.search(
                name,
                data=[vectors[-1]],
                limit=min(3, len(payload)),
                output_fields=[*identity, "digest"],
                consistency_level="Strong",
                timeout=10,
            )[0]
            if not matches or any(
                hit["id"] not in readback
                or any(hit["entity"].get(key) != value for key, value in identity.items())
                or hit["entity"].get("digest") != readback[hit["id"]]["digest"]
                for hit in matches
            ):
                # 代表性查询必须实际命中同代次、同正文和同治理依据。
                raise ModelFailure("INDEX_READBACK_MISMATCH")
            return IndexReceipt(
                collection=name,
                fingerprint=job.fingerprint,
                dimension=len(vectors[0]),
                observed=[
                    Verified(ordinal=i, digest=readback[i]["digest"]) for i in sorted(readback)
                ],
                hits=[hit["id"] for hit in matches],
            )
    except (MilvusException, OSError, TimeoutError):
        # 依赖故障保留稳定编码，不把连接及原始数据打进日志。
        raise ModelFailure("INDEX_UNAVAILABLE") from None
    except (KeyError, ValueError, IndexError):
        # 不完整或不兼容的回读不形成成功证据。
        raise ModelFailure("INDEX_READBACK_MISMATCH") from None


def _graph():
    """构建按确定的向量化、写入和真实读取顺序执行。"""
    flow = StateGraph(IndexState, context_schema=IndexContext)
    flow.add_node("embed", _vectors)
    flow.add_node("index", _index)
    flow.add_edge(START, "embed")
    flow.add_edge("embed", "index")
    flow.add_edge("index", END)
    return flow.compile()


async def execute_index(task: str, run: int, base: str) -> IndexReceipt:
    """按持久队列任务领取一次授权并运行固定构建。"""
    endpoint = f"{base}/internal/v1/knowledge/builds/{task}"
    async with httpx.AsyncClient(timeout=8, trust_env=False) as client:
        try:
            # 模型、修订及片段全部来自同一权威领取结果。
            source = IndexInput.model_validate(await authority(client, endpoint, "input", run))
        except ValidationError:
            raise ModelFailure("INPUT_STALE") from None
        try:
            # 剩余预算包含所有批次和存储，且不发送正文至外部追踪服务。
            with tracing_context(enabled=False):
                async with asyncio.timeout(source.remainingSeconds):
                    state = await _graph().ainvoke(
                        {},
                        context={
                            "endpoint": endpoint,
                            "run": run,
                            "source": source,
                            "client": client,
                        },
                    )
            return state["receipt"]
        except TimeoutError:
            raise ModelFailure("BUILD_TIMEOUT") from None
