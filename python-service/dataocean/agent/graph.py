"""LangGraph Agent 工作流图定义

使用 LangGraph StateGraph 编排 NL2SQL 工作流：
Query_Rewriter → Schema_Retriever → SQL_Generator → SQL_Validator → SQL_Executor → Data_Visualizer

条件路由：
- Validator 拒绝 → 直接返回安全告警
- Executor 失败且 retry < max → 回到 SQL_Generator
- Executor 失败且 retry >= max → 终止
- 任何节点检测到取消或超时 → 终止
"""

from __future__ import annotations

import logging
import time
from typing import Literal

from langgraph.graph import END, StateGraph

from dataocean.core.error_messages import sanitize_error
from dataocean.infra.cancellation import is_cancelled
from .config import agent_config
from .state import AgentState
from . import sse
from dataocean.infra.timeout_budget import TimeoutBudget, BudgetExhaustedException

logger = logging.getLogger(__name__)

# 节点名称常量
NODE_QUERY_REWRITER = "QUERY_REWRITER"
NODE_SCHEMA_RETRIEVER = "SCHEMA_RETRIEVER"
NODE_SCHEMA_LINKER = "SCHEMA_LINKER"
NODE_SQL_GENERATOR = "SQL_GENERATOR"
NODE_SQL_VALIDATOR = "SQL_VALIDATOR"
NODE_SQL_EXECUTOR = "SQL_EXECUTOR"
NODE_DATA_VISUALIZER = "DATA_VISUALIZER"

# 节点中文描述（用于 SSE 进度消息）
NODE_MESSAGES = {
    NODE_QUERY_REWRITER: "正在理解问题",
    NODE_SCHEMA_RETRIEVER: "正在召回相关表",
    NODE_SCHEMA_LINKER: "正在精简 Schema",
    NODE_SQL_GENERATOR: "正在生成 SQL",
    NODE_SQL_VALIDATOR: "正在校验 SQL 安全性",
    NODE_SQL_EXECUTOR: "正在执行查询",
    NODE_DATA_VISUALIZER: "正在生成图表",
}


def _check_cancelled_or_timeout(state: AgentState) -> str | None:
    """检查任务是否已取消或超时，返回错误消息或 None"""
    task_id = state.get("task_id", "")
    if is_cancelled(task_id):
        return "查询已取消"
    # 优先使用 TimeoutBudget 检查剩余预算
    budget: TimeoutBudget | None = state.get("timeout_budget")
    if budget and budget.remaining <= 0:
        return "处理时间超出限制，请简化问题"
    # 兜底：使用 start_time 检查总超时
    start_time = state.get("start_time", 0)
    if start_time and (time.time() - start_time) > agent_config.total_timeout:
        return "处理时间超出限制，请简化问题"
    return None


async def _node_wrapper(state: AgentState, node_name: str, node_fn) -> AgentState:
    """节点执行包装器：推送进度、检查取消/超时/预算、异常处理"""
    task_id = state.get("task_id", "")
    retry_count = state.get("retry_count", 0)

    # 检查取消或超时
    abort_msg = _check_cancelled_or_timeout(state)
    if abort_msg:
        return {**state, "error_message": abort_msg, "current_node": node_name}

    # 检查时间预算是否足够启动此节点，并将分配的秒数传给节点
    budget: TimeoutBudget | None = state.get("timeout_budget")
    allocated_seconds: float = 60  # 默认超时
    if budget:
        try:
            allocated_seconds = budget.allocate(node_name.lower())
        except BudgetExhaustedException:
            error_msg = "处理时间超出限制，请简化问题"
            await sse.emit_progress(task_id, node_name, "failed", error_msg, retry_count)
            return {**state, "error_message": error_msg, "current_node": node_name}

    # 推送节点开始事件
    await sse.emit_progress(task_id, node_name, "started", NODE_MESSAGES.get(node_name, ""), retry_count)

    try:
        state_with_timeout = {**state, "_node_timeout": allocated_seconds}
        result = await node_fn(state_with_timeout)
        # 推送节点完成事件
        msg = result.get("error_message") or ""
        status = "failed" if msg else "completed"
        await sse.emit_progress(task_id, node_name, status, msg or f"{NODE_MESSAGES.get(node_name, '')}完成", retry_count)
        return result
    except BudgetExhaustedException:
        error_msg = "处理时间超出限制，请简化问题"
        await sse.emit_progress(task_id, node_name, "failed", error_msg, retry_count)
        return {**state, "error_message": error_msg, "current_node": node_name}
    except Exception as e:
        logger.error("节点执行异常 node=%s task_id=%s error=%s", node_name, task_id, e, exc_info=True)
        error_msg = sanitize_error(e)
        await sse.emit_progress(task_id, node_name, "failed", error_msg, retry_count)
        return {**state, "error_message": error_msg, "current_node": node_name}


# --- 节点函数（Phase 2 逐步实现，当前为占位） ---

async def query_rewriter_node(state: AgentState) -> AgentState:
    """问题改写节点"""
    from .nodes.query_rewriter import run_query_rewriter
    return await _node_wrapper(state, NODE_QUERY_REWRITER, run_query_rewriter)


async def schema_retriever_node(state: AgentState) -> AgentState:
    """Schema 召回节点"""
    from .nodes.schema_retriever import run_schema_retriever
    return await _node_wrapper(state, NODE_SCHEMA_RETRIEVER, run_schema_retriever)


async def schema_linker_node(state: AgentState) -> AgentState:
    """Schema Linking 节点：过滤无关表/列，精简 SQL 生成输入"""
    from .nodes.schema_linker import run_schema_linker
    return await _node_wrapper(state, NODE_SCHEMA_LINKER, run_schema_linker)


async def sql_generator_node(state: AgentState) -> AgentState:
    """SQL 生成节点"""
    from .nodes.sql_generator import run_sql_generator
    result = await _node_wrapper(state, NODE_SQL_GENERATOR, run_sql_generator)
    return result


async def sql_validator_node(state: AgentState) -> AgentState:
    """SQL 校验节点"""
    from .nodes.sql_validator import run_sql_validator
    return await _node_wrapper(state, NODE_SQL_VALIDATOR, run_sql_validator)


async def sql_executor_node(state: AgentState) -> AgentState:
    """SQL 执行节点"""
    from .nodes.sql_executor import run_sql_executor
    return await _node_wrapper(state, NODE_SQL_EXECUTOR, run_sql_executor)


async def data_visualizer_node(state: AgentState) -> AgentState:
    """图表生成节点"""
    from .nodes.data_visualizer import run_data_visualizer
    return await _node_wrapper(state, NODE_DATA_VISUALIZER, run_data_visualizer)


# --- 条件路由函数 ---

def after_rewriter(state: AgentState) -> Literal["schema_retriever", "__end__"]:
    """Rewriter 后路由：有错误则终止，否则继续召回"""
    if state.get("error_message"):
        return END
    return "schema_retriever"


def after_retriever(state: AgentState) -> Literal["schema_linker", "__end__"]:
    """Retriever 后路由：召回为空则终止，否则进入 Schema Linking"""
    if state.get("error_message"):
        return END
    return "schema_linker"


def after_linker(state: AgentState) -> Literal["sql_generator", "__end__"]:
    """Linker 后路由：有错误则终止，否则继续生成"""
    if state.get("error_message"):
        return END
    return "sql_generator"


def after_validator(
    state: AgentState,
) -> Literal["sql_executor", "sql_generator", "__end__"]:
    """Validator 后路由：通过→执行，安全拒绝→终止，普通失败→重试生成

    注：retry_count 由 sql_validator 节点在普通失败时递增
    """
    validation = state.get("validation_result", {})
    if validation.get("valid"):
        return "sql_executor"
    level = validation.get("level", "")
    if level in ("DANGEROUS", "REJECT"):
        return END
    # 普通校验失败（如语法问题），回到生成器重试
    retry_count = state.get("retry_count", 0)
    if retry_count >= agent_config.max_retries:
        return END
    return "sql_generator"


def _classify_execution_error(error_message: str) -> str:
    """分类 SQL 执行错误类型

    Returns:
        "table_not_found" - 表不存在，需要重新检索 schema
        "syntax_error" - 语法错误，需要重新生成 SQL
        "timeout" - 超时，不应重试
        "connection" - 连接问题，不应重试
        "unknown" - 未知错误，重新生成 SQL
    """
    error_lower = error_message.lower()
    if "doesn't exist" in error_lower or "unknown table" in error_lower or "table" in error_lower and "not found" in error_lower:
        return "table_not_found"
    if "syntax error" in error_lower or "sql syntax" in error_lower:
        return "syntax_error"
    if "timeout" in error_lower or "timed out" in error_lower:
        return "timeout"
    if "connection" in error_lower or "refused" in error_lower:
        return "connection"
    return "unknown"


def after_executor(
    state: AgentState,
) -> Literal["data_visualizer", "sql_generator", "schema_retriever", "__end__"]:
    """Executor 后路由：成功→可视化，失败→根据错误类型选择恢复策略

    错误分类路由：
    - 表不存在 → 重新检索 schema（可能 schema 信息不完整）
    - 语法错误 → 重新生成 SQL
    - 超时/连接 → 终止（不应重试）
    - 未知错误 → 重新生成 SQL

    注：retry_count 由 sql_executor 节点在执行失败时递增
    """
    execution = state.get("execution_result", {})
    if execution.get("error"):
        retry_count = state.get("retry_count", 0)
        if retry_count >= agent_config.max_retries:
            return END

        error_type = _classify_execution_error(execution.get("error", ""))
        logger.info("SQL 执行失败，错误类型=%s retry=%d", error_type, retry_count)

        # 超时和连接错误不应重试
        if error_type in ("timeout", "connection"):
            return END

        # 表不存在时重新检索 schema（经过 linker 精简）
        if error_type == "table_not_found":
            return "schema_retriever"

        # 其他错误（语法、未知）重新生成 SQL
        return "sql_generator"
    return "data_visualizer"


# --- 构建图 ---

def build_graph() -> StateGraph:
    """构建 NL2SQL Agent 工作流图"""
    graph = StateGraph(AgentState)

    # 添加节点
    graph.add_node("query_rewriter", query_rewriter_node)
    graph.add_node("schema_retriever", schema_retriever_node)
    graph.add_node("schema_linker", schema_linker_node)
    graph.add_node("sql_generator", sql_generator_node)
    graph.add_node("sql_validator", sql_validator_node)
    graph.add_node("sql_executor", sql_executor_node)
    graph.add_node("data_visualizer", data_visualizer_node)

    # 设置入口
    graph.set_entry_point("query_rewriter")

    # 添加条件边
    graph.add_conditional_edges("query_rewriter", after_rewriter)
    graph.add_conditional_edges("schema_retriever", after_retriever)
    graph.add_conditional_edges("schema_linker", after_linker)
    graph.add_edge("sql_generator", "sql_validator")
    graph.add_conditional_edges("sql_validator", after_validator)
    graph.add_conditional_edges("sql_executor", after_executor)
    graph.add_edge("data_visualizer", END)

    return graph


# 编译图实例（模块级单例）
agent_graph = build_graph().compile()
