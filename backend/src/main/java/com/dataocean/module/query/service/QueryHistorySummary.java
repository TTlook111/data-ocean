package com.dataocean.module.query.service;

import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.HistorySummary;
import com.dataocean.module.query.service.QueryProtocol.Memory;
import com.dataocean.module.query.service.QueryProtocol.MemoryTurn;
import com.dataocean.module.query.service.QueryProtocol.Plan;
import com.dataocean.module.query.service.QueryProtocol.SummaryDecision;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将历史压缩为可追溯的原计划，模型只能选择来源编号。
 */
final class QueryHistorySummary {

    /**
     * 摘要回包最多保留五条经过核验的历史口径。
     */
    private static final int MAX_SELECTION = 5;

    /**
     * 规则只依赖本轮冻结的合规历史，无依赖或内部状态。
     */
    private QueryHistorySummary() {}

    /**
     * 同绑定、指标与字段的查询口径，便于确定其最近一次已验证条件。
     */
    private record Scope(String binding, String operation, String metric, List<Field> fields) {}

    /**
     * 可提供给摘要模型的证据只保留各口径最近有效旧轮次，近期只传前后比较所需的计划。
     */
    static Memory input(Memory frozen) {
        Map<Scope, MemoryTurn> older = latest(frozen.earlier());
        Map<Scope, MemoryTurn> recent = latest(frozen.recent());
        var earlier = frozen.earlier().stream()
                .filter(turn -> Objects.equals(older.get(scope(turn.plan())), turn))
                .filter(turn -> !replacedBy(turn, recent.get(scope(turn.plan()))))
                .map(QueryHistorySummary::planOnly)
                .toList();
        var plans = frozen.recent().stream()
                .filter(turn -> turn.plan() != null)
                .map(QueryHistorySummary::planOnly)
                .toList();
        return new Memory(frozen.timeAnchor(), plans, earlier);
    }

    /** 摘要节点不读取历史原话、补充或已返回的业务数值。 */
    private static MemoryTurn planOnly(MemoryTurn turn) {
        return new MemoryTurn(turn.requestId(), null, null, turn.plan());
    }

    /**
     * 按实际时间顺序选择原计划。同口径的旧商品或其他筛选不能压过更晚的确认。
     */
    private static Map<Scope, MemoryTurn> latest(List<MemoryTurn> turns) {
        var plans = new HashMap<Scope, MemoryTurn>();
        for (var turn : turns) {
            // 无可复用结构的回答不参与历史摘要。
            if (turn.plan() != null) {
                plans.put(scope(turn.plan()), turn);
            }
        }
        return plans;
    }

    /**
     * 仅同一业务口径比较筛选；不同指标的条件不会替换原口径。
     */
    private static Scope scope(Plan plan) {
        return plan == null ? null : new Scope(plan.binding(), plan.operation(), plan.metric(), plan.fields());
    }

    /**
     * 同列已有更新的确认值时，不再把包含旧值的执行口径交给模型。
     */
    private static boolean replacedBy(MemoryTurn older, MemoryTurn newer) {
        if (newer == null || older.plan().filters() == null || newer.plan().filters() == null) {
            // 尚无较新条件可以确认替换。
            return false;
        }
        if (older.plan().equals(newer.plan())) {
            // 近期已经含有同一确切口径，无需再次摘要。
            return true;
        }
        return older.plan().filters().stream().anyMatch(old -> newer.plan().filters().stream()
                .anyMatch(now -> Objects.equals(now.field(), old.field()) && !Objects.equals(now.value(), old.value())));
    }

    /**
     * 只有提供的旧轮次可以被引用；格式失败使用确定性的旧计划摘要。
     */
    static SummaryDecision decide(Memory input, HistorySummary result) {
        var fallback = input.earlier().stream().map(MemoryTurn::requestId).toList();
        var ids = result == null ? null : result.requestIds();
        if (ids == null || ids.isEmpty() || ids.size() > MAX_SELECTION
                || new HashSet<>(ids).size() != ids.size() || !fallback.containsAll(ids)) {
            // 缺回执、越界来源或全无选择都回到已核验的历史条件。
            return new SummaryDecision(fallback, false);
        }
        return new SummaryDecision(fallback.stream().filter(ids::contains).toList(), true);
    }

    /**
     * 近期原话仍供理解使用，早期摘要只回填原有业务计划。
     */
    static Memory apply(Memory frozen, SummaryDecision decision) {
        return new Memory(frozen.timeAnchor(), frozen.recent(), frozen.earlier().stream()
                .filter(turn -> decision.requestIds().contains(turn.requestId()))
                .map(QueryHistorySummary::planOnly).toList());
    }
}
