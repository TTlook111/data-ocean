"""数据库连接池管理

按 datasource_id 维护只读连接池，支持密码解密、全局连接数限制和空闲回收。
"""

from __future__ import annotations

import base64
import logging
import threading
import time
from dataclasses import dataclass, field

from .config import sandbox_config

logger = logging.getLogger(__name__)


@dataclass
class PoolInfo:
    """连接池元信息"""

    datasource_id: int
    pool_size: int
    last_used_at: float = field(default_factory=time.time)
    created_at: float = field(default_factory=time.time)


# 连接池注册表（MVP 阶段使用 SQLAlchemy 同步引擎，后续可切换为 AsyncEngine）
_pools: dict[int, object] = {}
_pool_info: dict[int, PoolInfo] = {}
_lock = threading.Lock()


def get_engine(datasource_id: int, connection_config: dict):
    """获取或创建数据源的连接引擎

    Args:
        datasource_id: 数据源 ID
        connection_config: 连接配置 {host, port, database, username, encrypted_password}

    Returns:
        SQLAlchemy Engine 实例

    Raises:
        RuntimeError: 全局连接数超限或创建失败
    """
    from sqlalchemy import create_engine

    with _lock:
        if datasource_id in _pools:
            _pool_info[datasource_id].last_used_at = time.time()
            return _pools[datasource_id]

        # 全局连接数检查
        total_connections = sum(info.pool_size for info in _pool_info.values())
        if total_connections >= sandbox_config.pool_max_global:
            raise RuntimeError("系统繁忙，连接池已满，请稍后重试")

        # 解密密码
        password = _decrypt_password(connection_config.get("encrypted_password", ""))
        host = connection_config.get("host", "localhost")
        port = connection_config.get("port", 3306)
        database = connection_config.get("database", "")
        username = connection_config.get("username", "")

        pool_size = sandbox_config.pool_max_per_source
        url = f"mysql+pymysql://{username}:{password}@{host}:{port}/{database}?charset=utf8mb4"

        engine = create_engine(
            url,
            pool_size=pool_size,
            max_overflow=0,
            pool_timeout=sandbox_config.pool_wait_timeout,
            pool_recycle=3600,
            pool_pre_ping=True,
        )

        _pools[datasource_id] = engine
        _pool_info[datasource_id] = PoolInfo(
            datasource_id=datasource_id,
            pool_size=pool_size,
        )
        logger.info("创建连接池 datasource_id=%d host=%s database=%s pool_size=%d",
                    datasource_id, host, database, pool_size)
        return engine


def destroy_pool(datasource_id: int) -> None:
    """销毁指定数据源的连接池"""
    with _lock:
        engine = _pools.pop(datasource_id, None)
        _pool_info.pop(datasource_id, None)
    if engine is not None:
        try:
            engine.dispose()
        except Exception as e:
            logger.warning("连接池销毁异常 datasource_id=%d error=%s", datasource_id, e)
        logger.info("连接池已销毁 datasource_id=%d", datasource_id)


def cleanup_idle_pools() -> int:
    """清理超过空闲超时的连接池，返回清理数量"""
    now = time.time()
    timeout = sandbox_config.pool_idle_timeout
    expired = [ds_id for ds_id, info in _pool_info.items()
               if now - info.last_used_at > timeout]
    for ds_id in expired:
        destroy_pool(ds_id)
    if expired:
        logger.info("清理空闲连接池 count=%d", len(expired))
    return len(expired)


def get_pool_status() -> list[dict]:
    """获取所有连接池状态"""
    return [
        {
            "datasourceId": info.datasource_id,
            "poolSize": info.pool_size,
            "lastUsedAt": info.last_used_at,
            "createdAt": info.created_at,
        }
        for info in _pool_info.values()
    ]


def _decrypt_password(encrypted: str) -> str:
    """AES-256 解密数据源密码"""
    if not encrypted:
        return ""
    key = sandbox_config.aes_secret_key
    if not key:
        logger.warning("AES 密钥未配置，尝试直接使用密码值")
        return encrypted
    try:
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
        from cryptography.hazmat.primitives import padding as sym_padding

        raw = base64.b64decode(encrypted)
        iv = raw[:16]
        ciphertext = raw[16:]
        key_bytes = key.encode("utf-8")[:32].ljust(32, b"\0")
        cipher = Cipher(algorithms.AES(key_bytes), modes.CBC(iv))
        decryptor = cipher.decryptor()
        padded = decryptor.update(ciphertext) + decryptor.finalize()
        unpadder = sym_padding.PKCS7(128).unpadder()
        plaintext = unpadder.update(padded) + unpadder.finalize()
        return plaintext.decode("utf-8")
    except Exception as e:
        logger.warning("密码解密失败，尝试直接使用原始值 error=%s", e)
        return encrypted
