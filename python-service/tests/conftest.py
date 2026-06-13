"""测试配置和共享辅助函数

提供测试中常用的辅助函数和 fixtures。
"""

from __future__ import annotations

from io import BytesIO


def parse_sse_stream(stream: BytesIO) -> list[dict]:
    """模拟 Java 端的 SSE 解析逻辑

    实现标准 SSE 客户端解析：
    - 支持多行 data（SSE 规范允许多行 data，以换行分隔）
    - 按空行提交事件（空行表示事件结束）
    - 忽略注释行（以 : 开头）
    - 处理心跳/注释行
    - 流结束时提交最后一个事件

    Args:
        stream: 包含 SSE 数据的字节流

    Returns:
        解析后的事件列表，每个事件包含 event 和 data 字段
    """
    events = []
    current_event = None
    current_data = []

    for line in stream.read().decode("utf-8").split("\n"):
        # 空行表示事件结束，提交当前事件
        if line == "":
            if current_data and current_event:
                events.append({
                    "event": current_event,
                    "data": "\n".join(current_data),
                })
            current_event = None
            current_data = []
            continue

        # 忽略注释行（以 : 开头，SSE 心跳机制）
        if line.startswith(":"):
            continue

        # 解析 event 行
        if line.startswith("event:"):
            current_event = line[6:].strip()
            continue

        # 解析 data 行（支持多行 data）
        if line.startswith("data:"):
            data_content = line[5:]
            # SSE 规范：data 前的空格是可选的
            if data_content.startswith(" "):
                data_content = data_content[1:]
            current_data.append(data_content)
            continue

        # 其他行（如 id:、retry: 等）暂时忽略

    # 流结束时，提交最后一个事件（如果没有以空行结尾）
    if current_data and current_event:
        events.append({
            "event": current_event,
            "data": "\n".join(current_data),
        })

    return events
