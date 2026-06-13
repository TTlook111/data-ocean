"""SSE 解析和轮询回归测试

覆盖 F0 和 P11 修复的 SSE/轮询场景：
- SSE 多行 data 解析
- SSE 空行提交事件
- SSE 心跳/注释行处理
- SSE 流结束时提交最后一个事件
- 配置热重载线程安全
- 连接池清理线程安全
"""

from __future__ import annotations

import threading
import time
from io import BytesIO

import pytest

from dataocean.core.config import reload_config, get_config_version
from dataocean.sandbox.pool_manager import cleanup_idle_pools, _pool_info, PoolInfo
from tests.conftest import parse_sse_stream


# ============================================================
# SSE 客户端解析回归测试
# ============================================================

class TestSSEClientParsing:
    """SSE 客户端解析（模拟 Java 端逻辑）"""

    def test_parse_simple_event(self):
        """解析简单事件"""
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
        assert events[0]["data"] == "line1\nline2\nline3"

    def test_parse_comment_lines_ignored(self):
        """忽略注释行（心跳）"""
        sse_data = b":heartbeat\nevent:progress\ndata:test\n\n"
        stream = BytesIO(sse_data)

        events = parse_sse_stream(stream)
        assert len(events) == 1
        assert events[0]["event"] == "progress"

    def test_parse_no_trailing_newline(self):
        """流结束时没有尾部空行也能提交事件"""
        sse_data = b"event:result\ndata:test"
        stream = BytesIO(sse_data)

        events = parse_sse_stream(stream)
        assert len(events) == 1
        assert events[0]["event"] == "result"
        assert events[0]["data"] == "test"

    def test_parse_multiple_events(self):
        """解析多个事件"""
        sse_data = b"event:progress\ndata:step1\n\nevent:progress\ndata:step2\n\nevent:result\ndata:final\n\n"
        stream = BytesIO(sse_data)

        events = parse_sse_stream(stream)
        assert len(events) == 3
        assert events[0]["event"] == "progress"
        assert events[1]["event"] == "progress"
        assert events[2]["event"] == "result"

    def test_parse_empty_data_line(self):
        """解析空 data 行"""
        sse_data = b"event:result\ndata:\ndata:test\n\n"
        stream = BytesIO(sse_data)

        events = parse_sse_stream(stream)
        assert len(events) == 1
        assert events[0]["data"] == "\ntest"

    def test_parse_data_with_leading_space(self):
        """解析 data 前有空格的情况"""
        sse_data = b"event:result\ndata: test\n\n"
        stream = BytesIO(sse_data)

        events = parse_sse_stream(stream)
        assert len(events) == 1
        assert events[0]["data"] == "test"


# ============================================================
# 配置热重载线程安全回归测试
# ============================================================

class TestConfigReloadThreadSafety:
    """配置热重载线程安全"""

    def test_config_version_monotonically_increases(self):
        """配置版本号单调递增"""
        initial = get_config_version()
        reload_config()
        after_first = get_config_version()
        reload_config()
        after_second = get_config_version()

        assert after_first > initial
        assert after_second > after_first

    def test_concurrent_reload_no_crash(self):
        """并发重载不会崩溃"""
        errors = []

        def reload_worker():
            try:
                for _ in range(10):
                    reload_config()
            except Exception as e:
                errors.append(e)

        threads = [threading.Thread(target=reload_worker) for _ in range(5)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(errors) == 0


# ============================================================
# 连接池清理线程安全回归测试
# ============================================================

class TestPoolCleanupThreadSafety:
    """连接池清理线程安全"""

    def test_concurrent_cleanup_no_crash(self):
        """并发清理不会崩溃"""
        # 创建测试连接池
        _pool_info[8888] = PoolInfo(
            datasource_id=8888,
            pool_size=5,
            last_used_at=time.time() - 10000
        )

        errors = []

        def cleanup_worker():
            try:
                cleanup_idle_pools()
            except Exception as e:
                errors.append(e)

        threads = [threading.Thread(target=cleanup_worker) for _ in range(5)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(errors) == 0
        assert 8888 not in _pool_info

    def test_cleanup_removes_only_expired(self):
        """清理只移除过期的连接池"""
        # 创建过期连接池
        _pool_info[7777] = PoolInfo(
            datasource_id=7777,
            pool_size=5,
            last_used_at=time.time() - 10000
        )
        # 创建活跃连接池
        _pool_info[7778] = PoolInfo(
            datasource_id=7778,
            pool_size=5,
            last_used_at=time.time()
        )

        cleanup_idle_pools()

        assert 7777 not in _pool_info  # 过期的被清理
        assert 7778 in _pool_info  # 活跃的保留

        # 清理测试数据
        _pool_info.pop(7778, None)


# ============================================================
# SSE 事件类型回归测试
# ============================================================

class TestSSEEventTypes:
    """SSE 事件类型"""

    def test_progress_event(self):
        """解析 progress 事件"""
        sse_data = 'event:progress\ndata:{"node":"SQL_GENERATOR","message":"generating","retry":0}\n\n'.encode("utf-8")
        stream = BytesIO(sse_data)

        events = parse_sse_stream(stream)
        assert len(events) == 1
        assert events[0]["event"] == "progress"

    def test_result_event(self):
        """解析 result 事件"""
        sse_data = 'event:result\ndata:{"status":"COMPLETED","sql":"SELECT 1"}\n\n'.encode("utf-8")
        stream = BytesIO(sse_data)

        events = parse_sse_stream(stream)
        assert len(events) == 1
        assert events[0]["event"] == "result"

    def test_error_event(self):
        """解析 error 事件"""
        sse_data = 'event:error\ndata:{"status":"FAILED","error":"timeout"}\n\n'.encode("utf-8")
        stream = BytesIO(sse_data)

        events = parse_sse_stream(stream)
        assert len(events) == 1
        assert events[0]["event"] == "error"
