package com.dataocean.module.query.service;

import com.dataocean.common.Ids;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;
import com.dataocean.module.query.service.QueryCorpus.Basis;

import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 使用实际 SQL 血缘约束选片，重排名次不能决定同名表指向。
 */
final class QueryTableSelection {

    /**
     * 接受普通标识和含特殊字符的 SQL 双引号标识。
     */
    private static final String QUOTED = "\"(?:[^\"]|\"\")+\"";

    /**
     * 识别恰好由模式和表组成的限定名。
     */
    private static final Pattern QUALIFIED = qualification("[\\p{L}_][\\p{L}\\p{M}\\p{N}_$]*", "\\p{L}");

    /**
     * 普通英文标识和双引号标识可以紧贴汉语叙述。
     */
    private static final Pattern INLINE_QUALIFIED = qualification("[A-Za-z_][A-Za-z0-9_$]*", "\\p{L}&&[^\\p{IsHan}]");

    /**
     * 目录没有命中时，用明确的查询或表名引导词区分限定名与普通点分文本。
     */
    private static final Pattern TABLE_DIRECTIVE =
            Pattern.compile("(?i)(?:查(?:询|看)?|读取|统计|从|使用|在|\\bfrom|\\bjoin)\\s*(?:表\\s*)?\\z");

    /**
     * 选片规则不持有请求或目录状态。
     */
    private QueryTableSelection() {
        // 选片通过静态入口完成。
    }

    /**
     * 用两个独立标识比对用户指定的对象和目录中的对象。
     */
    record Name(String schema, String table) {}

    /**
     * 规划只能读取限定后的候选，派发前再次核对实际使用对象。
     */
    record Scope(List<Basis> bases, Set<String> requiredObjects) {}

    /**
     * 检查输入是否可能包含有效限定名。普通日期和小数无需补查目录。
     */
    static boolean mayMentionTable(String question, String supplement) {
        return INLINE_QUALIFIED.matcher(question).find()
                || QUALIFIED.matcher(question).find()
                || supplement != null
                        && (INLINE_QUALIFIED.matcher(supplement).find()
                                || QUALIFIED.matcher(supplement).find());
    }

    /**
     * 复用已确认目录中的库或模式名。
     */
    static Name name(ObjectRef object) {
        return new Name(
                object.schema() == null || object.schema().isBlank() ? object.catalog() : object.schema(),
                object.name());
    }

    /**
     * 在已有候选中找能够共同支撑所指定物理表的片段。
     */
    static Optional<Scope> recalledScope(List<Basis> bases, String question, String supplement) {
        var names = bases.stream()
                .flatMap(basis -> basis.tables().values().stream())
                .map(table -> name(table.entry().observation().structure().object()))
                .collect(Collectors.toSet());
        var targets = requestedNames(names, question, supplement);
        if (targets.isEmpty()) {
            // 仍需从正式目录核对是否有首轮未召回的限定名。
            return Optional.empty();
        }
        return matchingScope(bases, targets);
    }

    /**
     * 补查已经确认限定名时，直接用固定目标校验实际回读的候选。
     */
    static Optional<Scope> matchingScope(List<Basis> bases, Set<Name> targets) {
        var matched = bases.stream()
                .filter(basis -> basis.tables().values().stream()
                        .filter(table -> !table.columns().isEmpty())
                        .map(table ->
                                name(table.entry().observation().structure().object()))
                        .collect(Collectors.toSet())
                        .containsAll(targets))
                .toList();
        if (matched.isEmpty()) {
            // 单片段必须能支撑全部对象，不能拼接不同口径的证据。
            return Optional.empty();
        }
        var ids = matched.stream()
                .flatMap(basis -> basis.tables().values().stream())
                .filter(table -> targets.contains(
                        name(table.entry().observation().structure().object())))
                .map(table -> Ids.text(table.entry().id()))
                .collect(Collectors.toUnmodifiableSet());
        return Optional.of(new Scope(matched, ids));
    }

    /**
     * 只有物理表集合唯一才按输入顺序选用第一份证据。空结果表示需要澄清。
     */
    static OptionalInt select(List<QueryUsage> compiled, Set<String> requiredObjects) {
        var objects = compiled.stream()
                .map(usage -> usage.objects().stream()
                        .map(QueryUsage.ObjectRef::objectId)
                        .collect(Collectors.toUnmodifiableSet()))
                .toList();
        int first = -1;
        for (int i = 0; i < objects.size(); i++) {
            // 同时核对用户明确指定的对象和候选实际使用的物理表。
            if (!objects.get(i).containsAll(requiredObjects)) {
                // 跳过缺少任一明确指代对象的候选。
                continue;
            }
            if (first == -1) {
                // 保留这一物理表组排名最靠前的完整片段。
                first = i;
            } else if (!objects.get(first).equals(objects.get(i))) {
                // 都能编译但会读取不同物理对象时，排序不能进行消歧。
                return OptionalInt.empty();
            }
        }
        if (first == -1) {
            // 计划输入已限定对象，编译后仍越出该范围属于规划失败。
            throw QueryFailure.planTargetMismatch();
        }
        return OptionalInt.of(first);
    }

    /**
     * 解析当前话题中用户写出的限定名，澄清回答中的限定优先。
     */
    static Set<Name> requestedNames(Set<Name> known, String question, String supplement) {
        var clarified = supplement == null ? Set.<Name>of() : qualified(known, supplement);
        return clarified.isEmpty() ? qualified(known, question) : clarified;
    }

    /**
     * 优先识别目录中的完整名称，随后检查紧接中文的限定名及未召回的名称。
     */
    private static Set<Name> qualified(Set<Name> known, String text) {
        var occupied = new BitSet(text.length());
        var found = new HashSet<Name>();
        collectNames(QUALIFIED, text, known, occupied, found, true);
        collectNames(INLINE_QUALIFIED, text, known, occupied, found, false);
        collectNames(QUALIFIED, text, known, occupied, found, false);
        return found;
    }

    /**
     * 从未被其他限定名使用的文本位置提取完整标识。
     */
    private static void collectNames(
            Pattern pattern, String text, Set<Name> known, BitSet occupied, Set<Name> found, boolean exactOnly) {
        var matches = pattern.matcher(text);
        while (matches.find()) {
            // 一个位置只采用一份限定名，原有的完整目录名优先。
            int overlap = occupied.nextSetBit(matches.start());
            if (overlap >= 0 && overlap < matches.end()) {
                // 已采用的限定名不能再按另一种文本边界拆分。
                continue;
            }
            var name = new Name(identifier(matches.group(1)), identifier(matches.group(2)));
            if (known.contains(name)
                    || !exactOnly
                            && (matches.group(1).startsWith("\"")
                                    || TABLE_DIRECTIVE
                                            .matcher(text)
                                            .region(0, matches.start())
                                            .find()
                                    || known.stream()
                                            .anyMatch(item -> name.schema().equals(item.schema())
                                                    || name.table().equals(item.table())))) {
                // 编译结果只能核对已识别到的目标，不能反过来决定哪些目标需要核对。
                found.add(name);
                occupied.set(matches.start(), matches.end());
            }
        }
    }

    /**
     * 限定名恰有模式和表两段，排除更长的点分隔标识。
     */
    private static Pattern qualification(String ordinaryIdentifier, String adjacentLetters) {
        var identifier = "(" + QUOTED + "|" + ordinaryIdentifier + ")";
        var adjacent = "(?:[" + adjacentLetters + "]|[\\p{M}\\p{N}_$\"])";
        return Pattern.compile("(?<!" + adjacent + "|\\.)" + identifier + "\\s*\\.\\s*" + identifier + "(?!" + adjacent
                + "|\\s*\\.\\s*[\"\\p{L}_])");
    }

    /**
     * 将双引号形式还原成目录原名。
     */
    private static String identifier(String name) {
        return name.startsWith("\"") ? name.substring(1, name.length() - 1).replace("\"\"", "\"") : name;
    }
}
