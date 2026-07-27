"""RAG 业务逻辑编排

编排 retrieve 完整流程：embedding → 检索 → 重排 → 阈值过滤 → 返回。
Milvus 异常时自动切换到降级方案。
"""

import hashlib
import json
import logging
from time import perf_counter

from dataocean.core.config import settings
from dataocean.infra.embeddings import embed_single
from dataocean.infra.memory import _get_redis  # Phase 1 #1: Redis 缓存

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
        # 1. 生成问题向量（Phase 1 #1: Redis 缓存，TTL 1h）
        cache_key = f"emb:{hashlib.md5(request.question.encode()).hexdigest()}"
        question_embedding = None
        try:
            redis = await _get_redis()
            cached = await redis.get(cache_key)
            if cached:
                question_embedding = json.loads(cached)
        except Exception:
            logger.warning("Embedding 缓存读取失败，降级为 API 调用")

        if question_embedding is None:
            question_embedding = await embed_single(request.question)
            try:
                redis = await _get_redis()
                await redis.setex(cache_key, 3600, json.dumps(question_embedding))
            except Exception:
                pass  # 写缓存失败不影响主流程

        # 2. Milvus 检索
        raw_results = await retrieve_from_milvus(question_embedding, request)

        if not raw_results:
            _log_recall_metrics(request, raw_count=0, ranked_count=0, filtered_count=0, top_score=0.0)
            return _response(message="未找到相关数据表，请换个问法", start=start)

        # 3. 规则加权重排
        ranked_results = rerank(raw_results, request)

        # 4. 相似度阈值过滤（阈值过滤为空时自动降低阈值重试一次）
        threshold = request.min_score if request.min_score is not None else settings.similarity_threshold
        filtered = [r for r in ranked_results if r.score >= threshold]

        if not filtered:
            # 降低阈值 0.1 重试，避免因阈值过高过滤掉所有结果
            retry_threshold = max(0.1, threshold - 0.1)
            filtered = [r for r in ranked_results if r.score >= retry_threshold]
            if filtered:
                logger.info("阈值过滤为空，降低阈值重试 threshold=%.2f→%.2f result_count=%d",
                            threshold, retry_threshold, len(filtered))

        if not filtered:
            # 二次重试仍为空时，返回 top-1 结果并标记为低置信度
            # 避免用户看到"未找到"而实际有部分相关结果
            if ranked_results:
                top_result = ranked_results[:1]
                logger.info("阈值过滤为空，返回 top-1 低置信度结果 top_score=%.4f", top_result[0].score)
                _log_recall_metrics(request, raw_count=len(raw_results), ranked_count=len(ranked_results),
                                    filtered_count=1, top_score=top_result[0].score)
                return _response(results=top_result, total_found=len(ranked_results), start=start,
                                 degraded=True, message="召回置信度较低，结果仅供参考")
            _log_recall_metrics(request, raw_count=len(raw_results), ranked_count=len(ranked_results),
                                filtered_count=0, top_score=ranked_results[0].score if ranked_results else 0.0)
            return _response(message="未找到相关数据表，请换个问法", start=start)

        # 记录召回质量指标
        _log_recall_metrics(request, raw_count=len(raw_results), ranked_count=len(ranked_results),
                            filtered_count=len(filtered), top_score=filtered[0].score if filtered else 0.0)

        return _response(results=filtered, total_found=len(ranked_results), start=start)

    except ValueError as e:
        logger.error("检索参数错误: %s", e)
        return _response(message=str(e), start=start)
    except Exception as e:
        logger.error("RAG 检索异常，触发降级: %s", e, exc_info=True)
        response = fallback_retrieve(request.datasource_id, request.fallback_chunks)
        response.retrieval_time_ms = _elapsed_ms(start)
        return response


def _response(
    *,
    results: list | None = None,
    total_found: int | None = None,
    message: str = "",
    start: float,
    degraded: bool = False,
    degrade_reason: str = "",
) -> RetrieveResponse:
    result_items = results or []
    return RetrieveResponse(
        results=result_items,
        total_found=total_found if total_found is not None else len(result_items),
        returned=len(result_items),
        message=message,
        retrieval_time_ms=_elapsed_ms(start),
        degraded=degraded,
        degrade_reason=degrade_reason or message if degraded else "",
    )


def _elapsed_ms(start: float) -> int:
    return int((perf_counter() - start) * 1000)


def _log_recall_metrics(
    request: RetrieveRequest,
    *,
    raw_count: int,
    ranked_count: int,
    filtered_count: int,
    top_score: float,
) -> None:
    """记录 RAG 召回质量指标（结构化日志）

    用于监控 RAG 系统健康度和发现退化。
    指标包括：原始召回数、重排后数、过滤后数、top-1 分数、chunk_type 分布。
    """
    logger.info(
        "RAG_RECALL datasource_id=%d raw=%d ranked=%d filtered=%d top_score=%.4f question=%s",
        request.datasource_id, raw_count, ranked_count, filtered_count, top_score,
        request.question[:50],
    )
