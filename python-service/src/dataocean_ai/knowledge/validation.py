"""按当前候选证据逐题召回，并返回确切代次的可核查命中。"""

import asyncio
import os
from typing import TypedDict

import httpx
from langgraph.graph import END, START, StateGraph
from langgraph.runtime import Runtime
from langsmith import tracing_context
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from dataocean_ai.adapters.chat import ModelConfiguration, ModelFailure
from dataocean_ai.adapters.embedding import embed
from dataocean_ai.knowledge.index import BATCH_SIZE, Verified, authority


class Question(BaseModel):
    """已获准发送的冻结题干。"""

    # 结果以稳定题标识关联 Java 的审核版本。
    id: str = Field(pattern="^[0-9]+$")
    text: str = Field(min_length=1, max_length=300, repr=False)


class ValidationInput(BaseModel):
    """只从受认证 Java 服务获取验证的固定索引和调用许可。"""

    model_config = ConfigDict(hide_input_in_errors=True)

    # 数据源的访问控制及授权不在 Python 复制。
    candidateId: str = Field(pattern="^[0-9]+$")  # noqa: N815
    documentRevisionId: str = Field(pattern="^[0-9]+$")  # noqa: N815
    fingerprint: str = Field(pattern="^[0-9a-f]{64}$")
    collection: str = Field(pattern="^[A-Za-z][A-Za-z0-9_]{1,95}$")
    fragments: list[Verified] = Field(min_length=1, max_length=12)
    questions: list[Question] = Field(min_length=1, repr=False)
    model: ModelConfiguration = Field(repr=False)
    remainingSeconds: int = Field(ge=1, le=180)  # noqa: N815


class Hit(BaseModel):
    """一题的实际命中，空命中由 Java 记录为异常。"""

    # 不返回向量、正文、期待答案或连接信息。
    id: str
    fragments: list[Verified]


class ValidationReceipt(BaseModel):
    """对照 Java 固定代次使用的逐题召回证明。"""

    # 保留源索引权限边界和同一候选指纹。
    collection: str
    fingerprint: str
    matches: list[Hit]


class ValidationState(TypedDict, total=False):
    """节点只交换临时向量和确切回执。"""

    # 原题和安全配置只在 Runtime 内。
    vectors: list[list[float]]
    result: ValidationReceipt


class ValidationContext(TypedDict):
    """本次运行的有界服务依赖。"""

    # Java 路径由部署和权威任务决定。
    source: ValidationInput
    endpoint: str
    run: int
    client: httpx.AsyncClient


async def _vectors(_state: ValidationState, runtime: Runtime[ValidationContext]):
    """使用候选的固定向量配置对每道题运行真实向量化。"""
    ctx = runtime.context
    job = ctx["source"]
    vectors: list[list[float]] = []
    for offset in range(0, len(job.questions), BATCH_SIZE):
        # 下一批仍须核对部署获准范围和本题执行资格。
        await authority(ctx["client"], ctx["endpoint"], "recheck", ctx["run"])
        batch = job.questions[offset : offset + BATCH_SIZE]
        vectors.extend(await embed(job.model, [question.text for question in batch]))
    return {"vectors": vectors}


def _search(job: ValidationInput, vectors: list[list[float]]) -> ValidationReceipt:
    """使用 Strong 实际查询既有索引并逐条确认归属。"""
    os.environ["PYTHON_DOTENV_DISABLED"] = "1"
    from pymilvus import MilvusClient
    from pymilvus.exceptions import MilvusException

    uri = os.getenv("AI_MILVUS_URI")
    if not uri:
        # 未配置服务时保留可诊断的技术失败。
        raise ModelFailure("INDEX_UNAVAILABLE")
    identity = {
        "candidate": job.candidateId,
        "revision": job.documentRevisionId,
        "fingerprint": job.fingerprint,
        "embedding": job.model.revision_id,
        "generation": job.collection,
    }
    expected = {fragment.ordinal: fragment.digest for fragment in job.fragments}
    matches: list[Hit] = []
    connection = None
    try:
        connection = MilvusClient(uri=uri, token=os.getenv("AI_MILVUS_TOKEN", ""), timeout=8)
        for offset in range(0, len(vectors), BATCH_SIZE):
            # 相似度结果必须绑定该题向量，保留搜索返回顺序。
            results = connection.search(
                job.collection,
                data=vectors[offset : offset + BATCH_SIZE],
                limit=min(3, len(expected)),
                output_fields=[*identity, "digest"],
                consistency_level="Strong",
                timeout=10,
            )
            for group in results:
                fragments = []
                for hit in group:
                    found = hit["entity"]
                    ordinal = hit["id"]
                    if (
                        ordinal not in expected
                        or found.get("digest") != expected[ordinal]
                        or any(found.get(key) != value for key, value in identity.items())
                    ):
                        # 不允许其他代次或不同治理片段参与验证。
                        raise ModelFailure("INDEX_READBACK_MISMATCH")
                    fragments.append(Verified(ordinal=ordinal, digest=found["digest"]))
                matches.append(Hit(id=job.questions[len(matches)].id, fragments=fragments))
        if len(matches) != len(job.questions):
            # 不完整的批次不能伪造每题结果。
            raise ModelFailure("INDEX_READBACK_MISMATCH")
        return ValidationReceipt(
            collection=job.collection, fingerprint=job.fingerprint, matches=matches
        )
    except (MilvusException, OSError, TimeoutError):
        raise ModelFailure("INDEX_UNAVAILABLE") from None
    except (KeyError, ValueError, IndexError):
        raise ModelFailure("INDEX_READBACK_MISMATCH") from None
    finally:
        # 关闭索引客户端，验证始终只读。
        if connection is not None:
            connection.close()


async def _recall(state: ValidationState, runtime: Runtime[ValidationContext]):
    """只在当前获准代次查询并在交付前复核。"""
    ctx = runtime.context
    await authority(ctx["client"], ctx["endpoint"], "recheck", ctx["run"])
    result = await asyncio.to_thread(_search, ctx["source"], state["vectors"])
    await authority(ctx["client"], ctx["endpoint"], "recheck", ctx["run"])
    return {"result": result}


def _graph():
    """逐题召回按向量化和独立读取执行。"""
    graph = StateGraph(ValidationState, context_schema=ValidationContext)
    graph.add_node("embed", _vectors)
    graph.add_node("recall", _recall)
    graph.add_edge(START, "embed")
    graph.add_edge("embed", "recall")
    graph.add_edge("recall", END)
    return graph.compile()


async def execute_validation(task: str, run: int, base: str) -> ValidationReceipt:
    """一次领取确切索引和获准题单，预算覆盖整段召回。"""
    endpoint = f"{base}/internal/v1/knowledge/validations/{task}"
    async with httpx.AsyncClient(timeout=8, trust_env=False) as client:
        try:
            job = ValidationInput.model_validate(await authority(client, endpoint, "input", run))
        except ValidationError:
            # 返回可披露状态，不记录无效输入正文。
            raise ModelFailure("INPUT_STALE") from None
        try:
            with tracing_context(enabled=False):
                async with asyncio.timeout(job.remainingSeconds):
                    state = await _graph().ainvoke(
                        {},
                        context={"source": job, "endpoint": endpoint, "run": run, "client": client},
                    )
            return state["result"]
        except TimeoutError:
            # 同一未知代次不会自动重新调用模型。
            raise ModelFailure("VALIDATION_TIMEOUT") from None
