"""Agent 短期记忆 Redis 缓存

基于后续开发文档 P5 设计，实现四类记忆：
1. 对话历史缓存（List）— 读穿透缓存，Java 负责持久化
2. 会话级上下文摘要（Hash）— 最近表、意图、SQL 等
3. 用户级偏好记忆（Hash）— 偏好数据源、图表类型等
4. 用户最近查询摘要（List）— 用于 Few-shot 检索

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


async def _get_redis():
    """获取 Redis 客户端（延迟初始化，asyncio.Lock 防并发）"""
    global _redis
    if _redis is None:
        async with _redis_lock:
            if _redis is None:
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
                    )
                    # 测试连接
                    await _redis.ping()
                    logger.info("Redis 连接成功 host=%s port=%d", settings.redis_host, settings.redis_port)
                except Exception as e:
                    logger.warning("Redis 连接失败，降级为无缓存模式: %s", e)
                    _redis = None
    return _redis


async def _safe_execute(coro, default=None):
    """安全执行 Redis 操作，失败时静默降级"""
    try:
        return await coro
    except Exception as e:
        logger.debug("Redis 操作失败，降级: %s", e)
        return default


# ===== 1. 对话历史缓存 =====

async def get_conversation_history(conversation_id: str) -> list[dict] | None:
    """获取对话历史缓存（读穿透）"""
    redis = await _get_redis()
    if redis is None:
        return None
    key = f"agent:conv:{conversation_id}:history"
    data = await _safe_execute(redis.lrange(key, 0, -1))
    if not data:
        return None
    return [json.loads(item) for item in data]


async def set_conversation_history(conversation_id: str, messages: list[dict], ttl: int = 7200) -> None:
    """设置对话历史缓存（原子 LPUSH + LTRIM + EXPIRE）"""
    redis = await _get_redis()
    if redis is None:
        return
    key = f"agent:conv:{conversation_id}:history"
    try:
        async with redis.pipeline(transaction=True) as pipe:
            pipe.delete(key)
            for msg in messages[-20:]:  # 最多保留 20 条（10 轮）
                pipe.lpush(key, json.dumps(msg, ensure_ascii=False))
            pipe.ltrim(key, 0, 19)
            pipe.expire(key, ttl)
            await pipe.execute()
    except Exception as e:
        logger.debug("对话历史缓存写入失败: %s", e)


# ===== 2. 会话级上下文摘要 =====

async def get_session_context(conversation_id: str) -> dict | None:
    """获取会话上下文摘要"""
    redis = await _get_redis()
    if redis is None:
        return None
    key = f"agent:conv:{conversation_id}:context"
    data = await _safe_execute(redis.hgetall(key))
    if not data:
        return None
    # 解析 JSON 字段
    for json_field in ("last_tables", "last_intent"):
        if json_field in data:
            try:
                data[json_field] = json.loads(data[json_field])
            except (json.JSONDecodeError, TypeError):
                pass
    return data


async def update_session_context(conversation_id: str, **fields) -> None:
    """更新会话上下文摘要（HSET 单字段更新）"""
    redis = await _get_redis()
    if redis is None:
        return
    key = f"agent:conv:{conversation_id}:context"
    try:
        mapping = {}
        for k, v in fields.items():
            if isinstance(v, (dict, list)):
                mapping[k] = json.dumps(v, ensure_ascii=False)
            else:
                mapping[k] = str(v)
        mapping["updated_at"] = str(int(time.time()))
        await _safe_execute(redis.hset(key, mapping=mapping))
        await _safe_execute(redis.expire(key, 7200))
    except Exception as e:
        logger.debug("会话上下文更新失败: %s", e)


# ===== 3. 用户级偏好记忆 =====

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


# ===== 4. 用户最近查询摘要 =====

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
