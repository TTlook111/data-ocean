"""Milvus 降级模块

当 Milvus 向量库不可用时，提供降级方案：
从 skills.md 核心表中提取 DDL 作为上下文。

注意：实际降级逻辑已在 dataocean.rag.service 中实现（异常时自动调用 fallback_retrieve），
本模块提供额外的降级状态查询和手动降级触发能力。
"""

import logging

from dataocean.rag.milvus_client import ping as milvus_ping

logger = logging.getLogger(__name__)


def is_milvus_available() -> bool:
    """检查 Milvus 是否可用

    每次查询时调用，实现自动恢复检测：
    连接成功则自动切回正常 RAG，无需手动干预。
    """
    return milvus_ping()


def get_degradation_notice() -> str:
    """获取降级提示信息"""
    return "知识库暂时不可用，已使用降级方案，召回精度可能降低"
