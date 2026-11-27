"""
按固定重排配置对获准候选排序。
"""

import asyncio
import math

import httpx

from dataocean_ai.adapters.chat import ModelConfiguration, ModelFailure
from dataocean_ai.adapters.model_receipts import ModelRecordingError, model_call_hooks


def _order(body: object, size: int, normalized_scores: bool) -> list[int] | None:
    """
    校验所有候选均有一个有限分数。并列按原顺序保留。
    """
    if (
        not isinstance(body, dict)
        or body.get("error") is not None
        or not isinstance(results := body.get("results"), list)
    ):
        return None
    scores: dict[int, int | float] = {}
    for item in results:
        # 提供方索引只能指向原候选，布尔及重复编号均属于异常响应。
        if not isinstance(item, dict):
            return None
        index = item.get("index")
        score = item.get("relevance_score")
        if (
            type(index) is not int
            or not 0 <= index < size
            or index in scores
            or type(score) not in (int, float)
            or isinstance(score, float)
            and not math.isfinite(score)
            or normalized_scores
            and not 0 <= score <= 1
        ):
            return None
        scores[index] = score
    if len(scores) != size:
        # 重排仅更改顺序，缺少结果时由业务层沿用召回顺序。
        return None
    return sorted(scores, key=lambda index: (-scores[index], index))


async def rerank(config: ModelConfiguration, query: str, documents: list[str]) -> list[int]:
    """
    发送一次完整的排序请求，返回相对于原候选的零起始顺序。
    """
    if (
        config.protocol not in ("COHERE_RERANK", "JSON_RERANK")
        or len(documents) < 2
        or not query.strip()
    ):
        # 拒绝用非重排能力或空输入启动外发。
        raise ModelFailure("MODEL_FORMAT")
    cohere = config.protocol == "COHERE_RERANK"
    # Cohere 沿用服务基址规则，通用重排按填写的完整接口发出请求。
    endpoint = config.endpoint.rstrip("/") + "/rerank" if cohere else config.endpoint
    try:
        async with (
            asyncio.timeout(config.timeout),
            httpx.AsyncClient(
                follow_redirects=False,
                trust_env=False,
                timeout=config.timeout,
                event_hooks=model_call_hooks(
                    config, lambda body: _order(body, len(documents), cohere) is not None
                ),
            ) as transport,
        ):
            # 保留全部正式依据，仅请求排名。凭据不随重定向或本机代理转发。
            response = await transport.post(
                endpoint,
                headers={"Authorization": "Bearer " + config.api_key.get_secret_value()},
                json={
                    "model": config.model_name,
                    "query": query,
                    "documents": documents,
                    "top_n": len(documents),
                },
            )
            response.raise_for_status()
            order = _order(response.json(), len(documents), cohere)
            if order is None:
                raise ModelFailure("MODEL_FORMAT")
            return order
    except ModelRecordingError:
        # 真实调用只有在确认记账后才发出。
        raise ModelFailure("MODEL_RECORD_UNAVAILABLE") from None
    except (ValueError, TypeError):
        # 返回稳定诊断，提供方正文不离开适配器。
        raise ModelFailure("MODEL_FORMAT") from None
    except httpx.HTTPStatusError as error:
        # HTTP 故障按现有模型分类记录，调用层不重发。
        codes = {400: "MODEL_FORMAT", 401: "MODEL_AUTH", 403: "MODEL_AUTH", 429: "MODEL_RATE_LIMIT"}
        raise ModelFailure(codes.get(error.response.status_code, "MODEL_RESULT_UNKNOWN")) from None
    except (httpx.RequestError, TimeoutError):
        # 无法核实外部响应时交由请求台账按原顺序恢复。
        raise ModelFailure("MODEL_RESULT_UNKNOWN") from None
