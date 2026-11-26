package com.dataocean.module.query.service;

import com.dataocean.module.query.service.QueryCorpus.Basis;
import com.dataocean.module.query.service.QueryProtocol.Condition;
import com.dataocean.module.query.service.QueryProtocol.Plan;
import com.dataocean.module.query.service.QueryProtocol.SelectionReview;
import com.dataocean.module.query.service.QueryProtocol.TimeSelection;
import com.dataocean.module.query.service.QuerySql.BoundFilter;
import com.dataocean.module.query.service.QuerySql.Compiled;
import com.dataocean.module.query.service.QuerySql.ValueComparison;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/**
 * 已编译的发布候选及其口径等价性。
 *
 * @param plan 实际采用的逻辑计划
 * @param basis 候选所用的已确认事实片段
 * @param compiled 编译后的 SQL 及其投影和血缘
 * @param usesHistoricalScope 是否固定上一轮的业务口径
 * @param parameterUse 实际参数引用的已确认取值证据
 * @param selection 提供给独立模型复核的候选选择依据
 */
record QueryCandidate(
        Plan plan,
        Basis basis,
        Compiled compiled,
        boolean usesHistoricalScope,
        QueryUsage.ParameterUse parameterUse,
        SelectionReview selection) {

    /**
     * 比较实际提问使用的列身份，而非 JOIN 后所有物理表的集合。
     */
    static boolean sameInputs(Basis first, Basis second, Plan plan) {
        if (plan.filters() == null
                || plan.filters().stream().anyMatch(filter -> filter == null || filter.field() == null)
                || plan.time() != null && plan.time().field() == null) {
            // 不完整的模型提议无法作为跨发布的物理输入依据。
            return false;
        }
        var fields = new ArrayList<>(plan.fields());
        plan.filters().stream().map(Condition::field).forEach(fields::add);
        if (plan.time() != null) {
            // 日期列也属于当前业务范围，不能从另一物理列读取相同日期值来替代。
            fields.add(plan.time().field());
        }
        for (var field : fields) {
            // 只承认证据已确认且来自相同对象世代的确切目录列。
            if (!first.labels().containsKey(field) || !second.labels().containsKey(field)) {
                // 两份依据都必须确认过该列。
                return false;
            }
            var left = first.tables().get(field.table());
            var right = second.tables().get(field.table());
            if (left == null
                    || right == null
                    || left.entry().id() != right.entry().id()
                    || left.entry().generation() != right.entry().generation()) {
                // 同名的新对象世代不能替代原列依赖。
                return false;
            }
            var leftColumn = left.entry().named(field.column());
            var rightColumn = right.entry().named(field.column());
            if (leftColumn == null
                    || rightColumn == null
                    || leftColumn.columnId() != rightColumn.columnId()
                    || leftColumn.revisionId() != rightColumn.revisionId()) {
                // 只认可已固定的实际字段及其修订。
                return false;
            }
        }
        return true;
    }

    /**
     * 比较实际筛选、日期、连接和结果口径，排序不能掩盖物理执行差异。
     */
    boolean sameOutcome(QueryCandidate second, ValueComparison comparison) {
        var left = compiled;
        var right = second.compiled();
        if (!left.columns().equals(right.columns())
                || !left.projection().equals(right.projection())
                || !Objects.equals(left.unit(), right.unit())
                || left.verifiesUnit() != right.verifiesUnit()) {
            // 投影或计量口径不同，立即保留差异。
            return false;
        }
        return sameFilters(second, comparison)
                && left.usage().sameObjects(right.usage())
                && left.joinKeys().equals(right.joinKeys());
    }

    /**
     * 使用实际编译值比较双方筛选和查询日期，兼容旧回执的等值规则。
     */
    private boolean sameFilters(QueryCandidate second, ValueComparison comparison) {
        var leftFilters = QuerySql.boundFilters(basis, plan);
        var rightFilters = QuerySql.boundFilters(second.basis(), second.plan());
        if (comparison == null) {
            // 旧回执沿用既有等价性规则。
            return compiled.parameters().equals(second.compiled().parameters()) && leftFilters.equals(rightFilters);
        }
        return Objects.equals(
                        comparisonTime(plan.time()),
                        comparisonTime(second.plan().time()))
                && containsFilters(basis, leftFilters, rightFilters, comparison)
                && containsFilters(second.basis(), rightFilters, leftFilters, comparison);
    }

    /**
     * 比较日期实际范围，展示用默认标记不改变筛选含义。
     */
    private TimeSelection comparisonTime(TimeSelection time) {
        return time == null ? null : new TimeSelection(time.field(), time.mode(), time.start(), time.end(), null);
    }

    /**
     * 同列同方向筛选按真实源列规则比较类型化值。
     */
    private boolean containsFilters(
            Basis basis, Set<BoundFilter> required, Set<BoundFilter> available, ValueComparison comparison) {
        return required.stream().allMatch(first -> available.stream()
                .anyMatch(second -> QuerySql.sameBoundFilter(basis, first, second, comparison)));
    }
}
