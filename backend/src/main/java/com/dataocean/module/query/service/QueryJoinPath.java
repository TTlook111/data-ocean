package com.dataocean.module.query.service;

import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
import com.dataocean.module.governance.service.RelationWindows;
import com.dataocean.module.query.service.QueryCorpus.Basis;
import com.dataocean.module.query.service.QueryProtocol.Condition;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.JoinAssessment;
import com.dataocean.module.query.service.QueryProtocol.Plan;
import com.dataocean.module.query.service.QueryProtocol.RelationEvidence;
import com.dataocean.module.query.service.QueryProtocol.TimeSelection;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;

/**
 * 从当前发布中选择满足业务日期及粒度的关联路径，保留无法选路时的复核依据。
 */
final class QueryJoinPath {

    /**
     * 可按精确数值比较的目录类型，避免把字符串转换和浮点舍入用于唯一性证明。
     */
    private static final Pattern EXACT_JOIN_NUMBER = Pattern.compile(
            "(?i)(tinyint|smallint|mediumint|int|integer|bigint|decimal|numeric)(\\(\\d+(,\\s*\\d+)?\\))?( unsigned)?( zerofill)?");

    /**
     * 关联由同版治理的确切字段对提供。
     *
     * @param left 左端已确认字段
     * @param right 右端已确认字段
     */
    record Pair(Field left, Field right) {}

    /**
     * 关联适用期校验使用的业务日期列和区间。
     *
     * @param field 实际作为日期维度的列
     * @param start 实际查询包含的第一天
     * @param end 实际查询包含的最后一天
     */
    private record RelationDates(Field field, String start, String end) {}

    /**
     * 未核实的适用边界和已确认不适用需要区别处理。
     */
    private enum Applicability {
        APPLICABLE,
        OUTSIDE,
        UNVERIFIABLE
    }

    /**
     * 关联组的全部键都要参与实际 JOIN。
     *
     * @param left 左端逻辑表名
     * @param right 右端逻辑表名
     * @param keys 确认修订中的完整连接键
     * @param fact 关联自身及其确认修订
     */
    record Relation(String left, String right, List<Pair> keys, GovernanceFactVO fact) {

        /**
         * 从关联的一端找到另一张表，保留原始键对方向以供 SQL 使用。
         */
        String other(String table) {
            return left.equals(table) ? right : left;
        }
    }

    /**
     * 按实际筛选确定适用窗口，并从同一条路径证明聚合的行数约束。
     */
    Map<String, Relation> select(Basis basis, Set<String> required, String first, Plan plan, List<Condition> filters) {
        var relationDates = relationDates(basis, first, plan.time(), filters);
        var applicability = new LinkedHashMap<Relation, Applicability>();
        for (var relation : relations(basis)) {
            // 复核收到的适用性来自本次真实判断。
            applicability.put(relation, appliesToDates(relation, relationDates));
        }
        var path = applicableJoinPath(basis, required, first, relationDates, applicability);
        boolean sumsRows = "SUM".equals(plan.operation()) || "SUM_DIFFERENCE".equals(plan.operation());
        if (!path.isEmpty() && sumsRows) {
            // 按实际联接校验度量粒度，筛选或指标依赖引入的表同样可能放大行数。
            try {
                requireRowGrain(basis, plan.fields(), path.values());
            } catch (QueryFailure failure) {
                // 粒度证明未通过也应保留这次尝试使用的准确关系。
                throw QueryFailure.join(joinAssessment(
                        "RELATION_GRAIN_UNVERIFIABLE", required, relationDates, path.values(), applicability));
            }
        }
        return path;
    }

    /**
     * 用适用关系选路，失败时保留同一批已确认键和原始适用依据供独立复核。
     */
    private Map<String, Relation> applicableJoinPath(
            Basis basis,
            Set<String> required,
            String first,
            RelationDates dates,
            Map<Relation, Applicability> applicability) {
        var available = applicability.keySet().stream()
                .filter(relation -> applicability.get(relation) == Applicability.APPLICABLE)
                .toList();
        var possible = applicability.keySet().stream()
                .filter(relation -> applicability.get(relation) != Applicability.OUTSIDE)
                .toList();
        if (possible.size() != available.size() && required.size() > 1) {
            // 未确认的关系只有在可能改变所需路径时才阻止派发。
            var candidates = joinCandidates(basis, required, possible);
            var reachable = new LinkedHashSet<>(Set.of(first));
            reachable.addAll(reachableRelations(adjacentRelations(candidates), Set.of(first), null)
                    .keySet());
            if (reachable.containsAll(required)) {
                var actualPath = confirmedJoinPath(basis, required, first, available);
                var possiblePath = confirmedJoinPath(basis, required, first, possible);
                if (actualPath == null || !actualPath.equals(possiblePath)) {
                    var considered = candidates.stream()
                            .filter(rel -> reachable.contains(rel.left()))
                            .toList();
                    var unresolved = considered.stream()
                            .filter(relation -> applicability.get(relation) == Applicability.UNVERIFIABLE)
                            .toList();
                    // 仅唯一可能路径上的单一关联缺失治理依据时可定位，查询日期不明仍单独处理。
                    String missingRule = unresolved.size() == 1
                                    && possiblePath != null
                                    && possiblePath.containsValue(unresolved.get(0))
                                    && RelationWindows.usable(
                                                    unresolved.get(0).fact().getContent())
                                            == null
                            ? unresolved.get(0).fact().getRevisionId()
                            : null;
                    throw QueryFailure.join(
                            joinAssessment(
                                    "RELATION_APPLICABILITY_UNVERIFIABLE", required, dates, considered, applicability),
                            missingRule);
                }
            }
        }
        try {
            return joinPath(basis, required, first, available);
        } catch (QueryFailure failure) {
            // 未通过日期校验时，忽略日期仅用于重建可审查的候选路径。
            var confirmed = List.copyOf(applicability.keySet());
            var path = confirmedJoinPath(basis, required, first, confirmed);
            if (path != null
                    && path.values().stream()
                            .anyMatch(relation -> applicability.get(relation) == Applicability.OUTSIDE)) {
                throw QueryFailure.join(
                        joinAssessment("RELATION_NOT_APPLICABLE", required, dates, path.values(), applicability));
            }
            // 缺失或歧义时保留与所需表连通的已确认边，让复核可重新核对路径。
            var candidates = joinCandidates(basis, required, confirmed);
            var related = new LinkedHashSet<>(required);
            related.addAll(reachableRelations(adjacentRelations(candidates), required, null)
                    .keySet());
            var considered = candidates.stream()
                    .filter(relation -> related.contains(relation.left()))
                    .toList();
            throw QueryFailure.join(
                    joinAssessment("RELATION_PATH_UNVERIFIABLE", required, dates, considered, applicability));
        }
    }

    /**
     * 只为缺少适用路径时取证，无唯一确认路径则由关联图说明缺口或歧义。
     */
    private Map<String, Relation> confirmedJoinPath(
            Basis basis, Set<String> required, String first, List<Relation> relations) {
        try {
            return joinPath(basis, required, first, relations);
        } catch (QueryFailure noPath) {
            // 仍无法形成唯一确认路径，不能标记为纯适用期冲突。
            return null;
        }
    }

    /**
     * 构造失败候选的关联证据，键、修订和正文均来自当前授权目录。
     */
    private JoinAssessment joinAssessment(
            String reason,
            Set<String> required,
            RelationDates dates,
            Iterable<Relation> relations,
            Map<Relation, Applicability> applicability) {
        var evidence = new ArrayList<RelationEvidence>();
        for (var relation : relations) {
            // 整组复合键一起提供，保留规则与适用条件的原始文本。
            var content = relation.fact().getContent();
            var window = RelationWindows.confirmed(content.path("relationWindow"));
            evidence.add(new RelationEvidence(
                    relation.fact().getRevisionId(),
                    relation.keys().stream()
                            .map(pair -> List.of(pair.left(), pair.right()))
                            .toList(),
                    content.path("rule").asText(""),
                    content.path("usage").asText(""),
                    applicability.get(relation) == Applicability.APPLICABLE,
                    window == null ? null : Objects.toString(window.validFrom(), null),
                    window == null ? null : Objects.toString(window.validThrough(), null)));
        }
        var bounds = dates == null
                ? List.<String>of()
                : Stream.of(dates.start(), dates.end()).distinct().toList();
        return new JoinAssessment(
                reason, List.copyOf(required), dates == null ? null : dates.field(), bounds, evidence);
    }

    /**
     * 沿用所需表的直接关联，只在剩余表无法直接接入时补全中间表。
     */
    private Map<String, Relation> joinPath(Basis basis, Set<String> required, String first, List<Relation> relations) {
        var path = new LinkedHashMap<String, Relation>();
        var remaining = new LinkedHashSet<>(required);
        remaining.remove(first);
        var linked = new LinkedHashSet<>(Set.of(first));
        while (!remaining.isEmpty()) {
            // 优先核对已在指标、投影或筛选中使用的表。
            var ready = relations.stream()
                    .filter(r -> linked.contains(r.left()) && remaining.contains(r.right())
                            || linked.contains(r.right()) && remaining.contains(r.left()))
                    .toList();
            if (ready.isEmpty()) {
                // 仅把可执行的普通表作为中间表，候选视图旁路不参与消歧。
                var candidates = joinCandidates(basis, required, relations);
                path.putAll(bridgePath(candidates, linked, remaining));
                break;
            }
            var relation = ready.get(0);
            var next = linked.contains(relation.left()) ? relation.right() : relation.left();
            if (ready.stream()
                            .filter(r -> r.left().equals(next) || r.right().equals(next))
                            .count()
                    != 1) {
                // 同一张表存在多个直接连接时保留原有的歧义判断。
                throw QueryFailure.knowledge();
            }
            path.put(next, relation);
            remaining.remove(next);
            linked.add(next);
        }
        return path;
    }

    /**
     * 只有所需表和可查询的普通中间表进入实际选路，也用于失败取证。
     */
    private List<Relation> joinCandidates(Basis basis, Set<String> required, List<Relation> relations) {
        return relations.stream()
                .filter(relation -> (required.contains(relation.left())
                                || QueryCorpus.ordinaryTable(basis, relation.left()))
                        && (required.contains(relation.right()) || QueryCorpus.ordinaryTable(basis, relation.right())))
                .toList();
    }

    /**
     * 通过人工确认的业务日期窗口判断适用性。历史正文只用于标记待核实。
     */
    private Applicability appliesToDates(Relation relation, RelationDates dates) {
        var content = relation.fact().getContent();
        var window = RelationWindows.usable(content);
        if (window == null) {
            // 结构化日期与正文的其他条件共同决定关联能否使用。
            return Applicability.UNVERIFIABLE;
        }
        if (window.unrestricted()) {
            // 已核实无日期限制时无需查询日期。
            return Applicability.APPLICABLE;
        }
        if (dates == null) {
            // 没有唯一可验证日期时保留候选歧义。
            return Applicability.UNVERIFIABLE;
        }
        try {
            var start = LocalDate.parse(dates.start());
            var end = LocalDate.parse(dates.end());
            if (end.isBefore(start)) {
                return Applicability.UNVERIFIABLE;
            }
            return (window.validFrom() == null || !start.isBefore(window.validFrom()))
                            && (window.validThrough() == null || !end.isAfter(window.validThrough()))
                    ? Applicability.APPLICABLE
                    : Applicability.OUTSIDE;
        } catch (DateTimeParseException invalid) {
            // 错误的查询日期不能证明候选不适用。
            return Applicability.UNVERIFIABLE;
        }
    }

    /**
     * 以编译实际生效的唯一业务日期或趋势区间核对关联适用窗口。
     */
    private RelationDates relationDates(Basis basis, String primary, TimeSelection time, List<Condition> filters) {
        var dates = filters.stream()
                .filter(filter -> QueryCorpus.isDateField(basis, filter.field()))
                .distinct()
                .toList();
        if (time == null ? dates.size() != 1 : !dates.isEmpty()) {
            // 省略日期或多个日期列时，不能自行选择哪个日期定义关联有效性。
            return null;
        }
        if (time == null && dates.get(0).excluded()) {
            // 排除一个日期不能划定查询的实际起止时间。
            return null;
        }
        var field = time == null ? dates.get(0).field() : time.field();
        var businessDates = basis.labels().keySet().stream()
                .filter(item -> item.table().equals(primary) && QueryCorpus.isDateField(basis, item))
                .toList();
        if (businessDates.size() != 1 || !businessDates.contains(field)) {
            // 仅以主查询对象的唯一可信日期列证明约束，其他表的日期不能代替该业务日期。
            return null;
        }
        String first = time == null ? dates.get(0).value() : time.start();
        String last = time != null && "TREND".equals(time.mode()) ? time.end() : first;
        return first == null || last == null ? null : new RelationDates(field, first, last);
    }

    /**
     * 找齐连到剩余所需表的边，逐条确认移除后无法绕行，最后丢弃无关旁支。
     */
    private Map<String, Relation> bridgePath(List<Relation> relations, Set<String> linked, Set<String> remaining) {
        var adjacent = adjacentRelations(relations);
        var parents = reachableRelations(adjacent, linked, null);
        if (!parents.keySet().containsAll(remaining)) {
            // 每张所需表都必须能使用当前完整的已确认关联接入。
            throw QueryFailure.knowledge();
        }
        var needed = new LinkedHashSet<Relation>();
        for (var table : remaining) {
            // 从每张所需表回溯到已接入的表，共用边只核验一次。
            var next = table;
            var relation = parents.get(next);
            while (relation != null && needed.add(relation)) {
                // 移除本边后仍可到达下游，说明另有可用路径，即使更长也不能猜选。
                if (reachableRelations(adjacent, linked, relation).containsKey(next)) {
                    throw QueryFailure.knowledge();
                }
                next = relation.other(next);
                relation = parents.get(next);
            }
        }
        // 广度遍历已保证父边先于子边，过滤后仍能依次生成 JOIN。
        parents.values().retainAll(needed);
        return parents;
    }

    /**
     * 从每张度量表出发核对所有连接方向。只有对端键覆盖完整主键，才能保证一行至多匹配一次。
     */
    private void requireRowGrain(Basis basis, List<Field> measures, Iterable<Relation> path) {
        var adjacent = adjacentRelations(path);
        var measureTables = measures.stream().map(Field::table).collect(Collectors.toSet());
        for (var table : measureTables) {
            // 同一个路径对不同度量的方向可能相反，差额指标必须分别核对。
            var parents = reachableRelations(adjacent, Set.of(table), null);
            for (var step : parents.entrySet()) {
                // 只使用精确数值等式覆盖的主键成员，避免隐式转换使两个键值比较相等。
                var target = step.getKey();
                var relation = step.getValue();
                var keys = relation.keys().stream()
                        .filter(pair -> EXACT_JOIN_NUMBER
                                        .matcher(QueryCorpus.confirmedColumn(basis, pair.left())
                                                .nativeType())
                                        .matches()
                                && EXACT_JOIN_NUMBER
                                        .matcher(QueryCorpus.confirmedColumn(basis, pair.right())
                                                .nativeType())
                                        .matches())
                        .map(pair -> pair.left().table().equals(target)
                                ? pair.left().column()
                                : pair.right().column())
                        .collect(Collectors.toSet());
                var primary = basis.tables()
                        .get(target)
                        .entry()
                        .observation()
                        .structure()
                        .primaryKeys();
                if (primary.isEmpty() || primary.stream().anyMatch(key -> !keys.contains(key.field()))) {
                    // 目录无法证明匹配唯一时保留知识不足，防止重复汇总。
                    throw QueryFailure.knowledge();
                }
            }
        }
    }

    /**
     * 等值关联可从任意一端遍历，复合键组保留为同一条边。
     */
    private Map<String, List<Relation>> adjacentRelations(Iterable<Relation> relations) {
        var adjacent = new LinkedHashMap<String, List<Relation>>();
        for (var relation : relations) {
            // 注册两端以支持路径查找和从度量表验证粒度。
            adjacent.computeIfAbsent(relation.left(), table -> new ArrayList<>())
                    .add(relation);
            adjacent.computeIfAbsent(relation.right(), table -> new ArrayList<>())
                    .add(relation);
        }
        return adjacent;
    }

    /**
     * 从已接入表向外遍历，按发现顺序登记其余表首次接入的关联。
     */
    private Map<String, Relation> reachableRelations(
            Map<String, List<Relation>> adjacent, Set<String> linked, Relation excluded) {
        var parents = new LinkedHashMap<String, Relation>();
        var pending = new ArrayDeque<>(linked);
        while (!pending.isEmpty()) {
            // 队列先处理已接入表，再处理新发现的表。
            var table = pending.remove();
            for (var relation : adjacent.getOrDefault(table, List.of())) {
                // 验证绕行时只排除确切关系，另一份同端关系仍可能构成歧义。
                if (relation == excluded) {
                    // 唯一性核对暂时移除此边，其余边仍可遍历。
                    continue;
                }
                var next = relation.other(table);
                if (!linked.contains(next) && !parents.containsKey(next)) {
                    // 同一张表只进入队列一次，循环关联不会反复遍历。
                    parents.put(next, relation);
                    pending.add(next);
                }
            }
        }
        return parents;
    }

    /**
     * 从同版确认读取复合键配对，以字段稳定身份定位两端。
     */
    private List<Relation> relations(Basis basis) {
        var relations = new ArrayList<Relation>();
        for (var fact : basis.facts().values()) {
            if (!"RELATION".equals(fact.getTarget().getKind())) {
                // 表、列及指标描述不能提供连接边。
                continue;
            }
            var pairs = new ArrayList<Pair>();
            for (JsonNode pair : fact.getContent().path("pairs")) {
                var left = basis.fields().get(pair.path("left").asText());
                var right = basis.fields().get(pair.path("right").asText());
                if (QueryCorpus.confirmedColumn(basis, left) == null
                        || QueryCorpus.confirmedColumn(basis, right) == null
                        || left.table().equals(right.table())) {
                    // 键缺少本片段的表或字段依据时，丢弃整组关联。
                    pairs.clear();
                    break;
                }
                pairs.add(new Pair(left, right));
            }
            if (!pairs.isEmpty()) {
                var key = pairs.get(0);
                if (pairs.stream()
                        .allMatch(p -> p.left().table().equals(key.left().table())
                                && p.right().table().equals(key.right().table()))) {
                    relations.add(new Relation(key.left().table(), key.right().table(), pairs, fact));
                }
            }
        }
        return relations;
    }
}
