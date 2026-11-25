package com.dataocean.module.query.service;

import com.dataocean.common.Ids;
import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
import com.dataocean.module.query.service.QueryCorpus.Basis;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.Plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 查询许可保存的实际使用引用，仅记录历史编号和用途。
 */
record QueryUsage(
        int schemaVersion,
        List<KnowledgeRef> knowledge,
        List<ObjectRef> objects,
        List<FactRef> facts,
        List<String> contextRequestIds) {

    /** 固定同版知识构建中的确切片段。 */
    record KnowledgeRef(
            String bindingId, String buildId, String documentRevisionId, int fragment, List<String> usages) {}

    /** 固定对象世代、结构和实际涉及的列。 */
    record ObjectRef(String objectId, int generation, String structureRevisionId, List<ColumnRef> columns) {}

    /** 字段可在同一执行承担多种用途。 */
    record ColumnRef(String columnId, String columnRevisionId, List<String> usages) {}

    /** 实际采用的治理修订以原目标编号定位。 */
    record FactRef(String kind, String objectId, String targetId, String factRevisionId) {}

    /** 已经确认的参数出处，在许可时合并进入执行证据。 */
    record ParameterUse(List<String> contextRequestIds, boolean published) {}

    /**
     * 只允许在候选包含原物理对象及实际筛选列时推导历史日期。
     */
    boolean supportsHistoricalPlan(Basis basis, Plan previous) {
        return objects.stream().allMatch(ref -> basis.tables().values().stream()
                        .anyMatch(table -> sameObject(
                                ref, Ids.text(table.entry().id()), table.entry().generation())))
                && previous.filters() != null
                && previous.filters().stream().allMatch(filter -> filter != null && usedFilter(basis, filter.field()))
                && (previous.time() == null || usedFilter(basis, previous.time().field()));
    }

    /**
     * 比较实际参与查询的对象及世代，忽略 SQL 列引用的顺序。
     */
    boolean sameObjects(QueryUsage current) {
        return objects.size() == current.objects.size()
                && current.objects.stream().allMatch(ref -> objects.stream()
                        .anyMatch(prior -> sameObject(prior, ref.objectId(), ref.generation())));
    }

    /**
     * 同时核对候选目录、原物理对象和上轮实际使用的筛选列。
     */
    private boolean usedFilter(Basis basis, Field field) {
        if (field == null || !basis.labels().containsKey(field)) {
            // 日期判断需要候选确认完整的历史筛选字段。
            return false;
        }
        var table = basis.tables().get(field.table());
        var column = table == null ? null : table.entry().named(field.column());
        return column != null
                && objects.stream()
                        .filter(ref -> sameObject(
                                ref, Ids.text(table.entry().id()), table.entry().generation()))
                        .flatMap(ref -> ref.columns().stream())
                        .anyMatch(ref -> Ids.text(column.columnId()).equals(ref.columnId())
                                && Ids.text(column.revisionId()).equals(ref.columnRevisionId())
                                && ref.usages().contains("FILTER"));
    }

    /**
     * 目录对象被替换后，即使名字相同也不能沿用历史口径。
     */
    private boolean sameObject(ObjectRef object, String id, int generation) {
        return id.equals(object.objectId()) && generation == object.generation();
    }

    /** 合并确切片段与沿用条件的出处，保留编译所得的引用。 */
    QueryUsage withBasis(Basis basis, List<Basis> parameters, List<String> context) {
        var primary = knowledge(basis, List.of("QUERY_BASIS"));
        var refs = new LinkedHashSet<KnowledgeRef>();
        for (var parameter : parameters) {
            // 只登记通过确定性参数校验实际采用的片段。
            var secondary = knowledge(parameter, List.of("PARAMETER_BASIS"));
            if (primary.bindingId().equals(secondary.bindingId()) && primary.fragment() == secondary.fragment()) {
                // 同一不可变片段同时承担两个用途。
                primary = knowledge(basis, List.of("QUERY_BASIS", "PARAMETER_BASIS"));
            } else {
                // 与实际编译不同的映射片段单独登记。
                refs.add(secondary);
            }
        }
        var ordered = new ArrayList<KnowledgeRef>();
        ordered.add(primary);
        ordered.addAll(refs);
        return new QueryUsage(
                schemaVersion,
                List.copyOf(ordered),
                objects,
                facts,
                context.stream().distinct().toList());
    }

    /** 发布绑定固定构建与文档修订，回查时沿原编号定位。 */
    private KnowledgeRef knowledge(Basis basis, List<String> usages) {
        var binding = basis.binding();
        return new KnowledgeRef(
                Ids.text(binding.id()),
                binding.buildId(),
                Ids.text(binding.documentRevisionId()),
                basis.ordinal(),
                usages);
    }

    /**
     * 编译期间登记真正进入 SQL 的字段、连接和已选择的治理依赖。
     */
    static final class Capture {

        /** 经过同版校验的目录和治理依据。 */
        private final Basis basis;

        /** 跨结果表达式、谓词和连接按稳定列身份归并。 */
        private final LinkedHashMap<String, LinkedHashMap<Long, ColumnRef>> fields = new LinkedHashMap<>();

        /** 按治理事实身份与确切修订去重。 */
        private final LinkedHashSet<FactRef> facts = new LinkedHashSet<>();

        /** 每个编译候选独立收集，失败草案不会进入许可。 */
        Capture(Basis basis) {
            this.basis = basis;
        }

        /** 登记实际用到的字段和片段内已确认的字段治理。 */
        void field(Field field, String usage) {
            var entry = basis.tables().get(field.table()).entry();
            var column = entry.named(field.column());
            if (column == null) {
                // 实际执行必须能追到确切列修订。
                throw QueryFailure.knowledge();
            }
            var columns = fields.computeIfAbsent(field.table(), ignored -> new LinkedHashMap<>());
            var prior = columns.get(column.columnId());
            var usages = new LinkedHashSet<String>();
            if (prior != null) {
                // 原字段已经登记的其他 SQL 用途也要保留。
                usages.addAll(prior.usages());
            }
            usages.add(usage);
            columns.put(
                    column.columnId(),
                    new ColumnRef(Ids.text(column.columnId()), Ids.text(column.revisionId()), List.copyOf(usages)));
            fact(basis.fact("COLUMN", column.columnId()));
        }

        /** 仅保留编译实际取用的治理修订。 */
        void fact(GovernanceFactVO value) {
            if (value != null) {
                // 使用治理确认时的原始目标及修订编号。
                var target = value.getTarget();
                facts.add(new FactRef(
                        target.getKind(), target.getObjectId(), target.getTargetId(), value.getRevisionId()));
            }
        }

        /** 生成独立于结果保留窗口的不可变引用。 */
        QueryUsage finish(Iterable<String> usedTables) {
            var objects = new ArrayList<ObjectRef>();
            for (String table : usedTables) {
                // 从真实目录取得对象世代和列所在的固定结构。
                var entry = basis.tables().get(table).entry();
                fact(basis.fact("TABLE", entry.id()));
                var columns = fields.get(table);
                objects.add(new ObjectRef(
                        Ids.text(entry.id()),
                        entry.generation(),
                        Ids.text(entry.observation().revisionId()),
                        columns == null ? List.of() : List.copyOf(columns.values())));
            }
            return new QueryUsage(1, List.of(), List.copyOf(objects), List.copyOf(facts), List.of());
        }
    }
}
