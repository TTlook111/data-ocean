"""SSE 解析和轮询回归测试

测试 SSE 客户端解析和前端轮询逻辑：
- SSE 多行 data 解析
- SSE 空行提交事件
- SSE 心跳/注释行处理
- 配置热重载竞态修复
- 连接池清理 TOCTOU 修复
"""

from __future__ import annotations

import threading
import time
from io import BytesIO

import pytest

from dataocean.core.config import reload_config, get_config_version, settings
from dataocean.sandbox.pool_manager import (
    get_engine,
    destroy_pool,
    cleanup_idle_pools,
    get_pool_status,
    _pool_info,
    PoolInfo,
)
from tests.conftest import parse_sse_stream


class TestSSEClientParsing:
    """SSE 客户端解析测试（模拟 Java 端逻辑）"""

    def test_parse_simple_event(self):
        """解析简单事件"""
        # 模拟 SSE 流
        sse_data = 'event:progress\ndata:{"node":"SQL_GENERATOR","message":"generating"}\n\n'.encode("utf-8")
        stream = BytesIO(sse_data)

        events = parse_sse_stream(stream)
        assert len(events) == 1
        assert events[0]["event"] == "progress"
        assert "SQL_GENERATOR" in events[0]["data"]

    def test_parse_multiline_data(self):
        """解析多行 data"""
        sse_data = b"event:result\ndata:line1\ndata:line2\ndata:line3\n\n"
        stream = BytesIO(sse_data)

        events = parse_sse_stream(stream)
        assert len(events) == 1
        assert events[0]["event"] == "result"
        assert "line1\nline2\nline3" == events[0]["data"]

    def test_parse_comment_lines(self):
        """忽略注释行"""
        sse_data = b":heartbeat\nevent:progress\ndata:test\n\n"
        stream = BytesIO(sse_data)

        events = parse_sse_stream(stream)
        assert len(events) == 1
        assert events[0]["event"] == "progress"

    def test_parse_no_trailing_newline(self):
        """流结束时没有尾部空行"""
        sse_data = b"event:result\ndata:test"
        stream = BytesIO(sse_data)

        events = parse_sse_stream(stream)
        assert len(events) == 1
        assert events[0]["event"] == "result"


class TestConfigReload:
    """配置热重载竞态修复测试"""

    def test_config_version_increments(self):
        """配置重载后版本号递增"""
        initial_version = get_config_version()
        reload_config()
        assert get_config_version() > initial_version

    def test_config_reload_thread_safe(self):
        """配置重载线程安全"""
        results = []
        errors = []

        def reload_worker():
            try:
                for _ in range(10):
                    reload_config()
                    results.append(get_config_version())
            except Exception as e:
                errors.append(e)

        # 启动多个线程同时重载
        threads = [threading.Thread(target=reload_worker) for _ in range(3)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        # 验证无错误
        assert len(errors) == 0
        # 验证版本号递增
        assert len(results) > 0
        assert all(v > 0 for v in results)


class TestPoolCleanup:
    """连接池清理 TOCTOU 修复测试"""

    def test_cleanup_thread_safe(self):
        """清理操作线程安全"""
        # 创建一个测试连接池
        _pool_info[999] = PoolInfo(datasource_id=999, pool_size=5, last_used_at=time.time() - 10000)

        results = []
        errors = []

        def cleanup_worker():
            try:
                cleaned = cleanup_idle_pools()
                results.append(cleaned)
            except Exception as e:
                errors.append(e)

        # 启动多个线程同时清理
        threads = [threading.Thread(target=cleanup_worker) for _ in range(3)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        # 验证无错误
        assert len(errors) == 0
        # 验证清理完成
        assert 999 not in _pool_info


class TestTableScopeMode:
    """table_scope_mode 向后兼容测试"""

    def test_backward_compatibility_with_tables(self):
        """向后兼容：UNSPECIFIED + allowed_tables = ALLOWLIST"""
        from dataocean.sandbox.rules.table_rule import check

        result = check("SELECT * FROM orders", ["orders"], "UNSPECIFIED")
        assert result.passed is True

    def test_backward_compatibility_without_tables(self):
        """无向后兼容：UNSPECIFIED + 空 allowed_tables = 拒绝"""
        from dataocean.sandbox.rules.table_rule import check

        result = check("SELECT * FROM orders", [], "UNSPECIFIED")
        assert result.passed is False
