"""RAG 业务逻辑编排

编排 retrieve 完整流程：embedding → 检索 → 重排 → 阈值过滤 → 返回。
Milvus 异常时自动切换到降级方案。
"""

import logging

from dataocean.core.config import settings

from .embedder import embed_single
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
    try:
        # 1. 生成问题向量
        question_embedding = await embed_single(request.question)

        # 2. Milvus 检索
        raw_results = await retrieve_from_milvus(question_embedding, request)

        if not raw_results:
            return RetrieveResponse(message="未找到相关数据表，请换个问法")

        # 3. 规则加权重排
        ranked_results = rerank(raw_results, request)

        # 4. 相似度阈值过滤
        threshold = settings.similarity_threshold
        filtered = [r for r in ranked_results if r.score >= threshold]

        if not filtered:
            return RetrieveResponse(message="未找到相关数据表，请换个问法")

        return RetrieveResponse(results=filtered)

    except ValueError as e:
        logger.error("检索参数错误: %s", e)
        return RetrieveResponse(message=str(e))
    except Exception as e:
        logger.error("RAG 检索异常，触发降级: %s", e)
        return fallback_retrieve(request.datasource_id)
