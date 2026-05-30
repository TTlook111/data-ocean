"""时间预算管理器

为 LangGraph 工作流各节点分配执行时间预算，
总预算 100 秒，各节点按职责分配固定额度。
节点执行前检查剩余预算，不足则提前终止。

属于 infra 中性层：供 agent 工作流使用，不反向依赖任何业务模块。
"""

import logging
import time

logger = logging.getLogger(__name__)


class BudgetExhaustedException(Exception):
    """时间预算耗尽异常"""

    def __init__(self, node_name: str, remaining: float, required: float):
        self.node_name = node_name
        self.remaining = remaining
        self.required = required
        super().__init__(
            f"时间预算不足: 节点 {node_name} 需要 {required:.1f}s，剩余 {remaining:.1f}s"
        )


# 各节点的默认时间预算分配（秒）
NODE_BUDGETS: dict[str, float] = {
    "query_rewriter": 10.0,
    "schema_retriever": 10.0,
    "sql_generator": 40.0,
    "sql_validator": 5.0,
    "sql_executor": 30.0,
    "data_visualizer": 15.0,
}

# 总预算（秒）
TOTAL_BUDGET: float = 100.0


class TimeoutBudget:
    """查询级时间预算管理器

    每次查询创建一个实例，跟踪总耗时和各节点分配。
    """

    def __init__(self, total_budget: float = TOTAL_BUDGET):
        self._total_budget = total_budget
        self._start_time = time.monotonic()

    @property
    def elapsed(self) -> float:
        """已消耗的时间（秒）"""
        return time.monotonic() - self._start_time

    @property
    def remaining(self) -> float:
        """剩余可用时间（秒）"""
        return max(0.0, self._total_budget - self.elapsed)

    def allocate(self, node_name: str) -> float:
        """为指定节点分配时间预算

        返回该节点可用的超时时间（取节点预算和剩余预算的较小值）。
        如果剩余预算不足以启动节点，抛出 BudgetExhaustedException。
        """
        node_budget = NODE_BUDGETS.get(node_name, 10.0)
        remaining = self.remaining

        # 剩余预算不足节点最低需求的 20% 时，拒绝执行
        min_required = node_budget * 0.2
        if remaining < min_required:
            logger.warning(
                "时间预算不足 node=%s remaining=%.1fs required=%.1fs",
                node_name, remaining, min_required,
            )
            raise BudgetExhaustedException(node_name, remaining, min_required)

        # 实际分配：取节点预算和剩余预算的较小值
        allocated = min(node_budget, remaining)
        logger.debug(
            "分配时间预算 node=%s allocated=%.1fs remaining=%.1fs",
            node_name, allocated, remaining,
        )
        return allocated

    def check_remaining(self, node_name: str) -> None:
        """检查是否还有剩余预算可继续执行

        在节点执行前调用，预算耗尽则抛出异常。
        """
        if self.remaining <= 0:
            raise BudgetExhaustedException(node_name, 0, 1.0)
