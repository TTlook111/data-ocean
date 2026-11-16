"""RAG 业务逻辑编排

编排 retrieve 完整流程：embedding → 检索 → 重排 → 阈值过滤 → 返回。
Milvus 异常时自动切换到降级方案。
"""

import hashlib
import json
import logging
from time import perf_counter

from dataocean.core.config import get_config_version, get_settings, settings
from dataocean.infra.embeddings import embed_single
from dataocean.infra.memory import _get_redis  # Phase 1 #1: Redis 缓存

from .collection_names import is_build_collection_name
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
    if not request.build_id or not request.collection_name or not request.active_snapshot_id:
        return _response(message="缺少当前生效 RAG build，拒绝读取默认或旧索引", start=start)
    if not is_build_collection_name(request.datasource_id, request.build_id, request.collection_name):
        return _response(message="buildId 与专属 collection 不一致，拒绝检索", start=start)
    if not request.authorized_chunk_ids:
        return _response(message="当前用户在生效 RAG build 中没有可见事实", start=start)
    stage = "embedding"
    try:
        # 缓存只复用相同配置下的问题向量，不缓存查询结果。缓存故障不阻断
        # 主流程，但保留读/写阶段以区分供应商调用变慢与缓存自身故障。
        cache_key = embedding_cache_key(request.question, request.embedding_config)
        question_embedding = None
        try:
            redis = await _get_redis()
            cached = await redis.get(cache_key)
            if cached:
                question_embedding = json.loads(cached)
        except Exception as exc:
            logger.warning("Embedding 缓存读取失败，降级为 API 调用 datasource_id=%d error_type=%s",
                           request.datasource_id, type(exc).__name__)

        if question_embedding is None:
            question_embedding = await embed_single(request.question, request.embedding_config)
            try:
                redis = await _get_redis()
                await redis.setex(cache_key, 3600, json.dumps(question_embedding))
            except Exception as exc:
                logger.warning("Embedding 缓存写入失败，继续检索 datasource_id=%d error_type=%s",
                               request.datasource_id, type(exc).__name__)

        # 2. Milvus 检索
        stage = "vector_search"
        raw_results = await retrieve_from_milvus(question_embedding, request)
        if request.build_id:
            # MySQL supplies the full authorized chunk ID set before ANN TopK.
            # Recheck complete fact dependencies before reranking/thresholds as
            # an independent guard against stale or malformed Milvus metadata.
            stage = "candidate_validation"
            raw_results = _filter_build_candidates(raw_results, request)

        if not raw_results:
            _log_recall_metrics(request, raw_count=0, ranked_count=0, filtered_count=0, top_score=0.0)
            return _response(message="未找到相关数据表，请换个问法", start=start)

        # 3. 规则加权重排
        stage = "rerank"
        ranked_results = rerank(raw_results, request)

        # 4. 相似度阈值过滤（阈值过滤为空时自动降低阈值重试一次）
        stage = "result_selection"
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

        # 生成 SQL 需要一个最小的跨表上下文。中文问题的向量分数经常只有
        # 一个结果越过阈值；只把这个结果交给模型会让它退回通用示例表名，
        # 随后被 SQL-to-Schema 幻觉检测拒绝。保留排序靠前的最多 3 个候选，
        # 仍受 datasource/snapshot/准入过滤约束，并显式标记为低置信度。
        context_degraded = len(filtered) < min(3, len(ranked_results))
        if context_degraded:
            filtered = ranked_results[:min(3, len(ranked_results))]
            logger.info(
                "召回上下文不足，扩展低置信度候选 threshold=%.2f result_count=%d",
                threshold,
                len(filtered),
            )

        # 记录召回质量指标
        _log_recall_metrics(request, raw_count=len(raw_results), ranked_count=len(ranked_results),
                            filtered_count=len(filtered), top_score=filtered[0].score if filtered else 0.0)

        return _response(
            results=filtered,
            total_found=len(ranked_results),
            start=start,
            degraded=context_degraded,
            message="召回包含低置信度上下文，请核对 SQL 与结果" if context_degraded else "",
        )

    except Exception as exc:
        # 供应商/向量库异常原文及堆栈可能携带请求正文或连接信息。
        # 阶段、异常类型和耗时足以定位本次降级，安全兜底资格仍由原逻辑裁决。
        logger.warning(
            "RAG 检索异常，尝试安全降级 datasource_id=%d snapshot_id=%s stage=%s error_type=%s elapsed_ms=%d",
            request.datasource_id, request.active_snapshot_id, stage, type(exc).__name__, _elapsed_ms(start),
        )
        response = fallback_retrieve(
            request.datasource_id,
            request.fallback_chunks,
            active_snapshot_id=request.active_snapshot_id,
            question=request.question,
            limit=request.top_k,
            build_id=request.build_id,
            authorized_resources=request.authorized_resources if request.build_id else None,
        )
        response.retrieval_time_ms = _elapsed_ms(start)
        if not response.results:
            # An outage without usable fallback is not a successful empty search.
            logger.warning("RAG 安全降级无可用事实 datasource_id=%d snapshot_id=%s elapsed_ms=%d",
                           request.datasource_id, request.active_snapshot_id, response.retrieval_time_ms)
            raise
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


def embedding_cache_key(question: str, embedding_config=None) -> str:
    """构建带 Embedding 配置版本的缓存 key。

    同一个问题在切换模型、提供商或向量维度后不能复用旧向量，
    否则可能出现召回偏差甚至 Milvus 维度错误。
    """
    active_settings = get_settings()
    provider = (
        (embedding_config.base_url or active_settings.dashscope_base_url)
        if embedding_config is not None
        else active_settings.embedding_base_url or active_settings.dashscope_base_url
    )
    provider_hash = hashlib.sha256(provider.encode("utf-8")).hexdigest()[:16]
    question_hash = hashlib.sha256(question.encode("utf-8")).hexdigest()
    model = embedding_config.model if embedding_config is not None else active_settings.qwen_embedding_model
    dimension = embedding_config.dimension if embedding_config is not None else active_settings.embedding_dimension
    provider_id = embedding_config.provider_id if embedding_config is not None else "active"
    return (
        f"emb:{provider_hash}:{provider_id}:{model}:{dimension}:"
        f"v{get_config_version()}:{question_hash}"
    )


def _filter_build_candidates(results: list, request: RetrieveRequest) -> list:
    allowed = set(request.authorized_resources)
    allowed_ids=set(request.authorized_chunk_ids)
    filtered = []
    for item in results:
        if getattr(item,"source_id",None) not in allowed_ids:
            continue
        dependencies = set(getattr(item, "resource_dependencies", []) or [])
        if not dependencies or not dependencies.issubset(allowed):
            continue
        if getattr(item, "fact_review_status", "PENDING") != "APPROVED":
            continue
        if getattr(item, "review_status", "") != "APPROVED":
            continue
        if getattr(item, "governance_status", "") not in {"NORMAL", "RECOMMENDED", "SENSITIVE"}:
            continue
        if getattr(item, "snapshot_id", None) != request.active_snapshot_id:
            continue
        if getattr(item, "build_id", None) != request.build_id:
            continue
        filtered.append(item)
    return filtered


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
    只记录来源范围、各阶段数量与最高分；问题文本和知识正文不进入日志。
    """
    logger.info(
        "RAG_RECALL datasource_id=%d snapshot_id=%s raw=%d ranked=%d filtered=%d top_score=%.4f",
        request.datasource_id, request.active_snapshot_id, raw_count, ranked_count, filtered_count, top_score,
    )
