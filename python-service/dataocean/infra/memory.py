"""Agent 用户级 Redis 记忆缓存

Python 不保存 conversationId 对应的会话历史或会话摘要，
会话消息和长期上下文由 Java 持久化后按请求传入。
本模块只保留与具体会话无关的用户偏好和最近查询摘要能力。

所有操作 try-catch 静默降级，Redis 故障不阻断查询。
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import Any

from dataocean.core.config import settings

logger = logging.getLogger(__name__)

# Redis 客户端单例（延迟初始化）
_redis = None
_redis_lock = asyncio.Lock()
_last_connect_failure = 0.0
_COOLDOWN_SECONDS = 30.0


async def _get_redis():
    """获取 Redis 客户端（延迟初始化，asyncio.Lock 防并发，失败后冷却 30 秒）

    连接失败后不会每次请求都重试，而是等待冷却期过后再尝试，
    避免 Redis 持续不可用时产生大量无效连接尝试。
    """
    global _redis, _last_connect_failure
    if _redis is not None:
        return _redis
    # 冷却期内直接返回 None，不尝试重连
    if time.monotonic() - _last_connect_failure < _COOLDOWN_SECONDS:
        return None
    async with _redis_lock:
        if _redis is not None:
            return _redis
        # 双重检查冷却期
        if time.monotonic() - _last_connect_failure < _COOLDOWN_SECONDS:
            return None
        try:
            import redis.asyncio as aioredis
            _redis = aioredis.Redis(
                host=settings.redis_host,
                port=settings.redis_port,
                password=settings.redis_password or None,
                db=settings.redis_db,
                decode_responses=True,
                socket_connect_timeout=5,
                socket_timeout=5,
                retry_on_timeout=True,
            )
            await _redis.ping()
            _last_connect_failure = 0.0
            logger.info("Redis 连接成功 host=%s port=%d", settings.redis_host, settings.redis_port)
        except Exception as e:
            logger.warning("Redis 连接失败，降级为无缓存模式: %s", e)
            _last_connect_failure = time.monotonic()
            _redis = None
    return _redis


async def _safe_execute(coro, default=None):
    """安全执行 Redis 操作，失败时静默降级"""
    try:
        return await coro
    except (ConnectionError, TimeoutError) as e:
        # 连接/超时错误记录 warning 级别，便于运维发现
        logger.warning("Redis 连接/超时异常: %s", e)
        return default
    except Exception as e:
        logger.debug("Redis 操作失败，降级: %s", e)
        return default


# ===== 1. 用户级偏好记忆 =====

async def get_user_prefs(user_id: str) -> dict | None:
    """获取用户偏好记忆"""
    redis = await _get_redis()
    if redis is None:
        return None
    key = f"agent:user:{user_id}:prefs"
    data = await _safe_execute(redis.hgetall(key))
    if not data:
        return None
    for json_field in ("frequent_tables", "frequent_metrics"):
        if json_field in data:
            try:
                data[json_field] = json.loads(data[json_field])
            except (json.JSONDecodeError, TypeError):
                pass
    return data


async def update_user_prefs(user_id: str, **fields) -> None:
    """更新用户偏好（HSET 单字段更新）"""
    redis = await _get_redis()
    if redis is None:
        return
    key = f"agent:user:{user_id}:prefs"
    try:
        mapping = {}
        for k, v in fields.items():
            if isinstance(v, (dict, list)):
                mapping[k] = json.dumps(v, ensure_ascii=False)
            else:
                mapping[k] = str(v)
        mapping["last_active"] = str(int(time.time()))
        await _safe_execute(redis.hset(key, mapping=mapping))
        await _safe_execute(redis.expire(key, 604800))  # 7 天
    except Exception as e:
        logger.debug("用户偏好更新失败: %s", e)


# ===== 2. 用户最近查询摘要 =====

async def get_recent_queries(user_id: str, limit: int = 10) -> list[dict]:
    """获取用户最近查询摘要"""
    redis = await _get_redis()
    if redis is None:
        return []
    key = f"agent:user:{user_id}:recent_queries"
    data = await _safe_execute(redis.lrange(key, 0, limit - 1))
    if not data:
        return []
    return [json.loads(item) for item in data]


async def add_recent_query(user_id: str, query_info: dict) -> None:
    """添加用户最近查询摘要（原子 LPUSH + LTRIM）"""
    redis = await _get_redis()
    if redis is None:
        return
    key = f"agent:user:{user_id}:recent_queries"
    try:
        query_info["ts"] = int(time.time())
        async with redis.pipeline(transaction=True) as pipe:
            pipe.lpush(key, json.dumps(query_info, ensure_ascii=False))
            pipe.ltrim(key, 0, 19)
            pipe.expire(key, 604800)  # 7 天
            await pipe.execute()
    except Exception as e:
        logger.debug("最近查询写入失败: %s", e)


async def close() -> None:
    """关闭 Redis 连接"""
    global _redis
    if _redis is not None:
        try:
            await _redis.close()
        except Exception:
            pass
        _redis = None
