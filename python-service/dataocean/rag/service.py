"""RAG 业务逻辑编排

编排 retrieve 完整流程：embedding → 检索 → 重排 → 阈值过滤 → 返回。
Milvus 异常时自动切换到降级方案。
"""

import logging
from time import perf_counter

from dataocean.core.config import settings
from dataocean.infra.embeddings import embed_single

from .fallback import fallback_retrieve
from .reranker import rerank
from .retriever import retrieve_from_milvus
from .schema import RetrieveRequest, RetrieveResponse

logger = logging.getLogger(__name__)


async def retrieve_schemas(request: RetrieveRequest) -> RetrieveResponse:
    """执行 RAG 语义检索

    流程：
    1. 生成问题向量
    2. Milvus 向量检索（含数据源隔离和准入过滤）
    3. 规则加权重排
    4. 相似度阈值过滤
    5. 返回 Top K 结果

    Milvus 异常时自动降级。
    """
    start = perf_counter()
    try:
        # 1. 生成问题向量
        question_embedding = await embed_single(request.question)

        # 2. Milvus 检索
        raw_results = await retrieve_from_milvus(question_embedding, request)

        if not raw_results:
            return _response(message="未找到相关数据表，请换个问法", start=start)

        # 3. 规则加权重排
        ranked_results = rerank(raw_results, request)

        # 4. 相似度阈值过滤
        threshold = request.min_score if request.min_score is not None else settings.similarity_threshold
        filtered = [r for r in ranked_results if r.score >= threshold]

        if not filtered:
            return _response(message="未找到相关数据表，请换个问法", start=start)

        return _response(results=filtered, total_found=len(ranked_results), start=start)

    except ValueError as e:
        logger.error("检索参数错误: %s", e)
        return _response(message=str(e), start=start)
    except Exception as e:
        logger.error("RAG 检索异常，触发降级: %s", e)
        response = fallback_retrieve(request.datasource_id, request.fallback_chunks)
        response.retrieval_time_ms = _elapsed_ms(start)
        return response


def _response(
    *,
    results: list | None = None,
    total_found: int | None = None,
    message: str = "",
    start: float,
) -> RetrieveResponse:
    result_items = results or []
    return RetrieveResponse(
        results=result_items,
        total_found=total_found if total_found is not None else len(result_items),
        returned=len(result_items),
        message=message,
        retrieval_time_ms=_elapsed_ms(start),
    )


def _elapsed_ms(start: float) -> int:
    return int((perf_counter() - start) * 1000)
