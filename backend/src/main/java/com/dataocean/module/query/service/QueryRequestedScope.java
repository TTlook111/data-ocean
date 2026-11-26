package com.dataocean.module.query.service;

import com.dataocean.common.Ids;
import com.dataocean.module.query.service.QueryCorpus.Basis;
import com.dataocean.module.query.service.QueryProtocol.CandidateEvidence;
import com.dataocean.module.query.service.QueryProtocol.CandidateExclusion;
import com.dataocean.module.query.service.QueryProtocol.ColumnIdentity;
import com.dataocean.module.query.service.QueryProtocol.Condition;
import com.dataocean.module.query.service.QueryProtocol.ConditionComparison;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.JoinAssessment;
import com.dataocean.module.query.service.QueryProtocol.Plan;
import com.dataocean.module.query.service.QueryProtocol.RequestedTerm;
import com.dataocean.module.query.service.QuerySql.BoundFilter;
import com.dataocean.module.query.service.QuerySql.Compiled;
import com.dataocean.module.query.service.QuerySql.ValueComparison;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一轮查询共用的用户条件及其物理字段依据，发布各自应用固定条件。
 */
record QueryRequestedScope(
        List<RequestedTerm> terms, List<VerifiedCondition> verified, Set<Field> governed, Set<Field> edited) {

    /**
     * 真实列世代和修订须一致，即使另一候选有相同逻辑表列名。
     */
    private record PhysicalField(Field field, long object, long generation, long column, long revision) {}

    /**
     * 原话经授权资料绑定后的参数与物理列。
     */
    private record VerifiedCondition(PhysicalField field, Condition condition) {}

    /**
     * 同一物理字段匹配到的编码，以其获准目录核对实际等值关系。
     */
    private record MatchedField(Basis basis, LinkedHashMap<BoundFilter, Condition> values) {}

    /**
     * 先确定编辑的字段和候选值，整列操作可覆盖尚未确认唯一值的旧编辑。
     */
    private record LocatedEdit(RequestedTerm term, PhysicalField field, MatchedField match) {}

    /**
     * 保留原句和条件值的绝对位置，旧回执也使用实际出现次数定位。
     */
    private record PositionedTerm(RequestedTerm term, boolean supplement, int end) {}

    /**
     * 核对原话片段并对各可信候选统一解析，同一用户条件只接受唯一的字段和实际值。
     */
    static QueryRequestedScope resolve(
            List<Basis> bases,
            Plan plan,
            List<RequestedTerm> terms,
            String question,
            String supplement,
            ValueComparison comparison) {
        if (terms.size() > 16 || terms.stream().anyMatch(term -> !grounded(term, question, supplement))) {
            // 没有明确原话的模型草案不能成为用户要求。
            return null;
        }
        var compiler = new QuerySql();
        var governed = new LinkedHashSet<Field>();
        var edited = new LinkedHashSet<Field>();
        var ordered = inSourceOrder(terms, question, supplement);
        if (ordered == null) {
            // 原文中的出现位置无法核对，不能猜操作顺序。
            return null;
        }
        for (var basis : bases) {
            // 所选及其他召回发布中的固定条件都可能被模型复述。
            compiler.provenFixedFilters(basis, plan).forEach(condition -> governed.add(condition.field()));
        }
        var active = new ArrayList<LocatedEdit>();
        for (var term : ordered) {
            // 优先采用原话和已确认字段，源列等值匹配先于模型的字段建议。
            var matches = new LinkedHashMap<PhysicalField, MatchedField>();
            for (var basis : bases) {
                locate(compiler, basis, plan, term, matches, null, false);
            }
            if (matches.isEmpty()) {
                // 立足相同的 JDBC 类型和源库规则识别已发布编码的字面别名。
                for (var basis : bases) {
                    locate(compiler, basis, plan, term, matches, comparison, false);
                }
            }
            if (matches.isEmpty()) {
                // 无治理值对应时，仍允许规划为原话字面值建议已获准的列。
                for (var basis : bases) {
                    locate(compiler, basis, plan, term, matches, comparison, true);
                }
            }
            if (matches.size() != 1) {
                // 只能修改已绑定到唯一本轮物理字段的范围。
                return null;
            }
            var match = matches.entrySet().iterator().next();
            var field = match.getKey();
            if ("REPLACE".equals(term.action()) || clearsField(term)) {
                // 整列编辑在确认最终取值前消除作废的条件和撤销操作。
                active.removeIf(previous -> previous.field().equals(field));
            }
            if (!clearsField(term)) {
                // 定向撤销只对当前仍有可能生效的值比较。
                active.add(new LocatedEdit(term, field, match.getValue()));
            }
            edited.add(field.field());
            governed.add(field.field());
        }
        var verified = effectiveConditions(active, comparison);
        if (verified == null || verified.size() > 8) {
            // 仅确认最终生效条件的歧义和八项限制。
            return null;
        }
        return new QueryRequestedScope(ordered, List.copyOf(verified), Set.copyOf(governed), Set.copyOf(edited));
    }

    /**
     * 原问先于补充，补充内部按原话位置回放，包括同句或重复句里的动作。
     */
    private static List<RequestedTerm> inSourceOrder(List<RequestedTerm> terms, String question, String supplement) {
        var positions = new ArrayList<PositionedTerm>();
        var used = new LinkedHashMap<RequestedTerm, Integer>();
        for (var term : terms) {
            // 完整原句仍用于证明否定上下文，前缀只用于定位该值的这一次出现。
            boolean amendment = "SUPPLEMENT".equals(term.source());
            var source = amendment ? supplement : question;
            var focus = clearsField(term) ? term.field() : term.value();
            var starts = new ArrayList<Integer>();
            int cursor = 0;
            for (var clause : clauses(source)) {
                // 从上一个句尾向后查找可区分字面相同的句子。
                var start = source.indexOf(clause, cursor);
                if (clause.equals(term.phrase())) {
                    starts.add(start);
                }
                cursor = start + clause.length();
            }
            int end;
            if (term.through() != null) {
                // 有定位回执时，由原始文本而不是模型的列表顺序决定操作位置。
                end = term.through().length();
                if (!amendment
                        || !source.startsWith(term.through())
                        || !term.through().endsWith(focus)
                        || starts.stream()
                                .noneMatch(start -> end - focus.length() >= start
                                        && end <= start + term.phrase().length())
                        || positions.stream()
                                .anyMatch(position -> position.supplement()
                                        && position.end() == end
                                        && position.term().through() != null)) {
                    // 两项不能共用同一个定位，否则无法证明实际执行次序。
                    return null;
                }
            } else {
                // 旧回执仅在重复编辑与原句出现次数一一对应时继续处理。
                if (starts.size() > 1 && Collections.frequency(terms, term) != starts.size()) {
                    return null;
                }
                var next = starts.size() == 1 ? 0 : used.merge(term, 1, Integer::sum) - 1;
                end = starts.get(next) + term.phrase().indexOf(focus) + focus.length();
            }
            positions.add(new PositionedTerm(term, amendment, end));
        }
        return positions.stream()
                .sorted(Comparator.comparing(PositionedTerm::supplement).thenComparingInt(PositionedTerm::end))
                .map(PositionedTerm::term)
                .toList();
    }

    /**
     * 使用与候选比较相同的等值测试归并同一物理列的已确认别名。
     */
    private static Condition equivalentValue(MatchedField match, ValueComparison comparison) {
        var representative = match.values().entrySet().stream()
                .min(Comparator.comparing(entry -> entry.getValue().value()))
                .orElse(null);
        if (representative == null
                || match.values().keySet().stream()
                        .anyMatch(candidate -> !QuerySql.sameBoundFilter(
                                match.basis(), representative.getKey(), candidate, comparison))) {
            // 源库实际区分的候选值仍需要澄清。
            return null;
        }
        return representative.getValue();
    }

    /**
     * 定向撤销只影响前面同列同方向的值，最终剩余的值才需要唯一绑定。
     */
    private static List<VerifiedCondition> effectiveConditions(List<LocatedEdit> active, ValueComparison comparison) {
        var verified = new ArrayList<VerifiedCondition>();
        for (int index = 0; index < active.size(); index++) {
            // 撤销本身不会生成 SQL 条件。
            var edit = active.get(index);
            if ("REMOVE".equals(edit.term().action())) {
                continue;
            }
            boolean removed = false;
            boolean uncertain = false;
            for (var undo : active.subList(index + 1, active.size())) {
                // 只比较可能取消当前条件的后续撤销。
                if (!"REMOVE".equals(undo.term().action())
                        || !edit.field().equals(undo.field())
                        || !edit.term().mode().equals(undo.term().mode())) {
                    continue;
                }
                if (edit.term().value().equals(undo.term().value())) {
                    // 用户原样撤销旧范围时无需解决该旧值的多个编码。
                    removed = true;
                    break;
                }
                var previous = edit.match().values().keySet();
                var next = undo.match().values().keySet();
                var pairs = previous.stream()
                        .flatMap(left -> next.stream()
                                .map(right ->
                                        QuerySql.sameBoundFilter(edit.match().basis(), left, right, comparison)))
                        .toList();
                if (!pairs.isEmpty() && pairs.stream().allMatch(Boolean.TRUE::equals)) {
                    // 不同字面但在源库上明确等价的定向撤销同样生效。
                    removed = true;
                    break;
                }
                uncertain |= pairs.contains(true);
            }
            if (removed) {
                continue;
            }
            var condition = equivalentValue(edit.match(), comparison);
            if (uncertain || condition == null) {
                // 部分重叠或多个实际值仍影响查询结果，需要用户确认。
                return null;
            }
            var basis = edit.match().basis();
            var bound = QuerySql.bindFilter(basis, condition);
            if (verified.stream()
                    .noneMatch(previous -> previous.field().equals(edit.field())
                            && QuerySql.sameBoundFilter(
                                    basis, QuerySql.bindFilter(basis, previous.condition()), bound, comparison))) {
                // 追加只补充仍生效的条件，源列等价的重复值保留一份。
                verified.add(new VerifiedCondition(edit.field(), condition));
            }
        }
        return verified;
    }

    /**
     * 撤销整列范围只需辨明字段，无须提供不存在的新值。
     */
    private static boolean clearsField(RequestedTerm term) {
        return "REMOVE".equals(term.action()) && term.value() == null;
    }

    /**
     * 过滤掉模型在受治理范围上提出的条件，候选之间只比较真实的物理输入。
     */
    Plan comparisonPlan(QuerySql compiler, Plan plan) {
        var filters = plan.filters() == null
                ? null
                : plan.filters().stream()
                        .filter(filter ->
                                filter == null || filter.field() == null || !governed.contains(filter.field()))
                        .toList();
        return compiler.withTime(plan, filters, plan.time());
    }

    /**
     * 单独核对同一用户条件与该发布的实际字段和固定规则。
     */
    CandidateExclusion exclusion(QuerySql compiler, Basis basis, Plan plan, ValueComparison comparison) {
        var fixed = compiler.provenFixedFilters(basis, plan);
        for (var requested : verified) {
            // 不允许另一个同名对象代替已绑定的条件字段。
            var condition = requested.condition();
            if (!requested.field().equals(physical(basis, condition.field()))) {
                return excluded(basis, "USER_FIELD_UNAVAILABLE", List.of(), null, null);
            }
            for (var rule : fixed) {
                if (rule.field().equals(condition.field())) {
                    // 包含需等于固定值，排除需与固定值不同。
                    var same = comparison.same(
                            basis,
                            rule.field(),
                            QuerySql.bindFilter(basis, rule).value(),
                            QuerySql.bindFilter(basis, condition).value());
                    if (same == condition.excluded()) {
                        return new CandidateExclusion(
                                Ids.text(basis.binding().id()),
                                basis.ordinal(),
                                "USER_CONDITION_CONFLICT",
                                List.of(rule),
                                candidateEvidence(basis, null, null),
                                new ConditionComparison(condition, same));
                    }
                }
            }
        }
        return null;
    }

    /**
     * 为当前候选补入与其他候选相同的用户条件。
     */
    Plan apply(QuerySql compiler, Basis basis, Plan plan) {
        if (edited.isEmpty() || plan.filters() == null) {
            return plan;
        }
        var current = conditions();
        var filters = new ArrayList<>(plan.filters());
        // 当前明确指定的范围覆盖追问中保留的同列旧值，其他列继续按原范围执行。
        filters.removeIf(filter -> filter != null && filter.field() != null && edited.contains(filter.field()));
        var fixed = compiler.provenFixedFilters(basis, plan);
        // 已核对相容的固定等值范围采用发布原值。排除条件仍进入 SQL 再落实一次。
        current.stream()
                .filter(condition -> condition.excluded()
                        || fixed.stream().noneMatch(rule -> rule.field().equals(condition.field())))
                .forEach(filters::add);
        return compiler.withTime(plan, filters, plan.time());
    }

    /**
     * 供独立复核和用户商品条件核验读取实际绑定值。
     */
    List<Condition> conditions() {
        return verified.stream().map(VerifiedCondition::condition).distinct().toList();
    }

    /**
     * 只输出本轮已核验的发布标识与有限理由。
     */
    static CandidateExclusion excluded(
            Basis basis, String reason, List<Condition> fixed, Plan plan, Compiled compiled) {
        return new CandidateExclusion(
                Ids.text(basis.binding().id()),
                basis.ordinal(),
                reason,
                fixed,
                candidateEvidence(basis, plan, compiled),
                null);
    }

    /**
     * 使用同一取证形状提供已选和未选的实际物理身份与绑定证据。
     */
    static CandidateEvidence candidateEvidence(Basis basis, Plan plan, Compiled compiled) {
        return candidateEvidence(basis, plan, compiled, null);
    }

    /**
     * 编译失败时提供实际检查的关系和日期，成功时记录实际 SQL 范围。
     */
    static CandidateEvidence candidateEvidence(Basis basis, Plan plan, Compiled compiled, JoinAssessment assessment) {
        var columns = basis.labels().keySet().stream()
                .map(field -> physical(basis, field))
                .filter(Objects::nonNull)
                .map(field -> new ColumnIdentity(
                        field.field(),
                        Ids.text(field.object()),
                        field.generation(),
                        Ids.text(field.column()),
                        Ids.text(field.revision())))
                .toList();
        var objects = compiled == null
                ? List.<String>of()
                : compiled.usage().objects().stream()
                        .map(QueryUsage.ObjectRef::objectId)
                        .toList();
        var dates = compiled == null
                ? List.<String>of()
                : compiled.parameters().stream()
                        .filter(LocalDate.class::isInstance)
                        .map(Object::toString)
                        .distinct()
                        .toList();
        var fieldOrder = Comparator.comparing(Field::table).thenComparing(Field::column);
        var keys = compiled == null
                ? List.<List<Field>>of()
                : compiled.joinKeys().stream()
                        .map(pair -> pair.stream().sorted(fieldOrder).toList())
                        .sorted(Comparator.comparing((List<Field> pair) -> pair.get(0), fieldOrder)
                                .thenComparing(pair -> pair.get(pair.size() - 1), fieldOrder))
                        .toList();
        return new CandidateEvidence(basis.snippet(), columns, plan, objects, dates, keys, assessment);
    }

    /**
     * 字段、值和肯定或否定片段均必须来自当前真实的原文。
     */
    private static boolean grounded(RequestedTerm term, String question, String supplement) {
        var source = term != null && "SUPPLEMENT".equals(term.source()) ? supplement : question;
        return term != null
                && term.phrase() != null
                && !term.phrase().isBlank()
                && clauses(source).contains(term.phrase())
                && (term.field() == null
                        || !term.field().isBlank() && term.phrase().contains(term.field()))
                && ("SUPPLEMENT".equals(term.source())
                        ? term.action() != null
                                && Set.of("APPEND", "REPLACE", "REMOVE").contains(term.action())
                        : term.action() == null || "APPEND".equals(term.action()))
                && (clearsField(term)
                        ? term.field() != null && term.mode() == null
                        : term.value() != null
                                && !term.value().isBlank()
                                && term.value().length() <= 120
                                && term.phrase().contains(term.value())
                                && ("INCLUDE".equals(term.mode()) || "EXCLUDE".equals(term.mode())));
    }

    /**
     * 句尾用于确认引用和操作次序，逗号、换行与列表仍留在否定上下文中。
     */
    private static List<String> clauses(String source) {
        return source == null
                ? List.of()
                : Arrays.stream(source.split("[。！？!?；;]"))
                        .map(String::strip)
                        .filter(part -> !part.isBlank())
                        .toList();
    }

    /**
     * 只用确认的值映射、明确字段和原话值，规划只能为同一原值提供候选列。
     */
    private static void locate(
            QuerySql compiler,
            Basis basis,
            Plan plan,
            RequestedTerm term,
            LinkedHashMap<PhysicalField, MatchedField> matches,
            ValueComparison comparison,
            boolean allowModel) {
        var fixed = compiler.provenFixedFilters(basis, plan);
        // 一整句可包含多个独立条件，商品映射只解读当前条件的值。
        var product = term.value() == null ? null : QueryProductNames.resolve(List.of(basis), term.value());
        basis.labels().forEach((field, label) -> {
            // 日期条件继续由现有业务日期逻辑绑定。
            var column = QueryCorpus.confirmedColumn(basis, field);
            if (column == null || "date".equalsIgnoreCase(column.nativeType())) {
                return;
            }
            var fact = basis.fact("COLUMN", column.columnId());
            boolean named = term.field() != null
                    && (term.field().equalsIgnoreCase(field.column())
                            || term.field().equalsIgnoreCase(label)
                            || fact != null
                                    && (term.field().equalsIgnoreCase(fact.getLabel())
                                            || term.field()
                                                    .equalsIgnoreCase(fact.getContent()
                                                            .path("businessName")
                                                            .asText("")))
                            || "SKU".equalsIgnoreCase(term.field()) && QuerySql.isProductCode(label));
            if (term.field() != null && !named) {
                // 原话已限定维度时，不向其他同值字段绑定。
                return;
            }
            if (clearsField(term)) {
                // 已确认字段名足以撤销用户范围，发布固定条件在候选阶段另外落实。
                matches.computeIfAbsent(
                        physical(basis, field), ignored -> new MatchedField(basis, new LinkedHashMap<>()));
                return;
            }
            var values = new LinkedHashSet<String>();
            if (fact != null) {
                // 从已确认编码和业务含义的对应关系取得实际参数。
                for (var mapping : fact.getContent().path("valueMappings")) {
                    var code = mapping.path("code").asText();
                    var meaning = mapping.path("meaning").asText();
                    if (sameLiteral(basis, field, term.value(), code, comparison)
                            || term.value().equals(meaning)
                            || named && !meaning.isBlank() && term.value().equals(meaning + term.field())) {
                        // 仅忽略紧随已确认值的明确维度名，例如“饮料品类”。
                        values.add(code);
                    }
                }
            }
            if (product != null && product.confirmed() && field.equals(product.field())) {
                // 名称和规格由现有商品映射器核实，不接受模型猜出的码。
                values.add(product.code());
            }
            boolean knownValue = fixed.stream()
                            .anyMatch(filter -> field.equals(filter.field())
                                    && sameLiteral(basis, field, term.value(), filter.value(), comparison))
                    || QuerySql.isProductCode(label) && QueryProductNames.declares(basis, term.value());
            if (values.isEmpty()
                    && (named
                            || knownValue
                            || allowModel
                                    && plan.filters() != null
                                    && plan.filters().stream()
                                            .anyMatch(filter -> filter != null
                                                    && field.equals(filter.field())
                                                    && filter.value() != null
                                                    && sameLiteral(
                                                            basis, field, term.value(), filter.value(), comparison)))) {
                // 字面值已在原话中，且有确认列或术语归属可以绑定。
                values.add(term.value());
            }
            for (var value : values) {
                var condition =
                        "EXCLUDE".equals(term.mode()) ? new Condition(field, value, true) : new Condition(field, value);
                var bound = bindLiteral(basis, condition);
                if (bound != null) {
                    // 位置与物理列已知，多个值的唯一性留到编辑完成后确认。
                    matches.computeIfAbsent(
                                    physical(basis, field), ignored -> new MatchedField(basis, new LinkedHashMap<>()))
                            .values()
                            .putIfAbsent(bound, condition);
                }
            }
            if (named || !values.isEmpty()) {
                // 即使旧值无法绑定，确认的字段仍可让后续整列编辑覆盖它。
                matches.computeIfAbsent(
                        physical(basis, field), ignored -> new MatchedField(basis, new LinkedHashMap<>()));
            }
        });
    }

    /**
     * 业务含义仍根据已确认映射识别，编码字面值按实际列的类型及等值规则比较。
     */
    private static boolean sameLiteral(
            Basis basis, Field field, String input, String code, ValueComparison comparison) {
        if (input.equals(code)) {
            // 相同字面无需再次连接源库，最终仍检查字段类型。
            return true;
        }
        if (comparison == null || code.isBlank()) {
            return false;
        }
        var left = bindLiteral(basis, new Condition(field, input));
        var right = bindLiteral(basis, new Condition(field, code));
        return left != null && right != null && QuerySql.sameBoundFilter(basis, left, right, comparison);
    }

    /**
     * 不能转换为实际源类型的字面值不参与候选等值比较。
     */
    private static BoundFilter bindLiteral(Basis basis, Condition condition) {
        try {
            return QuerySql.bindFilter(basis, condition);
        } catch (QueryFailure invalid) {
            // 绑定仅依据目录与参数本身，未知或不可转换的类型没有已确认的候选值。
            return null;
        }
    }

    /**
     * 当前片段真实可用的物理字段及修订。
     */
    private static PhysicalField physical(Basis basis, Field field) {
        var column = QueryCorpus.confirmedColumn(basis, field);
        if (column == null) {
            return null;
        }
        var entry = basis.tables().get(field.table()).entry();
        return new PhysicalField(field, entry.id(), entry.generation(), column.columnId(), column.revisionId());
    }
}
