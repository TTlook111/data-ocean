package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Entry;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.datasource.service.GovernanceNotes;
import com.dataocean.module.datasource.service.GovernanceNotes.Note;
import com.dataocean.module.governance.entity.dto.GovernanceCandidateReviewDTO;
import com.dataocean.module.governance.entity.po.GovernanceGenerationPO;
import com.dataocean.module.governance.entity.vo.GovernanceCandidateVO;
import com.dataocean.module.governance.entity.vo.GovernanceCandidateVO.Definition;
import com.dataocean.module.governance.mapper.GovernanceDiscoveryMapper;
import com.dataocean.module.governance.mapper.GovernanceDiscoveryMapper.Candidate;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.Basis;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.Field;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.NoteRef;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.Pair;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.Proposal;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.Scope;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.StructureRef;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.Table;
import com.dataocean.module.governance.service.GovernanceGenerationWire.Evidence;
import com.dataocean.module.governance.service.GovernanceTarget;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.model.service.ModelRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 将模型候选绑定到同源目录和确切资料，保存可回看的人工审核。
 */
@Service
@RequiredArgsConstructor
public class GovernanceDiscovery {

    /** 解析普通 `表名.列名` 及带 Schema 的物理引用。 */
    private static final Pattern QUALIFIED = Pattern.compile(
            "([\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)?)\\.([\\p{L}_][\\p{L}\\p{N}_]*)");

    /** 平台候选及版本操作。 */
    private final GovernanceDiscoveryMapper candidates;

    /** 来源快照、对象归属与当前事实。 */
    private final GovernanceCatalog catalog;

    /** 已确认的表和列定义版本。 */
    private final GovernanceEntries entries;

    /** 确切业务资料修订。 */
    private final GovernanceNotes notes;

    /** 继续使用既有的操作授权。 */
    private final PermissionService permissions;

    /** 对明确内部结构序列化。 */
    private final JsonCodec json;

    /**
     * 将外部类型转换为已接入的实际业务节点。
     */
    public String node(String kind) {
        return switch (kind) {
            // 两类任务分别绑定各自的配置修订。
            case "RELATION" -> ModelRegistry.RELATION_DISCOVERY;
            case "METRIC" -> ModelRegistry.METRIC_DISCOVERY;
            default -> throw BizIllegalException.invalid("请选择关联或指标");
        };
    }

    /**
     * 使用源锁所确认的目录组装这次可见的结构和验证查找表。
     */
    public DiscoveryInput input(Source source, Entry anchor, String kind) {
        var peers = new ArrayList<Entry>();
        peers.add(anchor);
        peers.addAll(catalog.peers(source, anchor.id(), 23));
        var byName = new LinkedHashMap<String, Entry>();
        var refs = new LinkedHashMap<String, StructureRef>();
        for (Entry entry : peers) {
            // 含 Schema 的同名表只允许按确切路径查找。
            var object = entry.observation().structure().object();
            byName.put(qualified(entry), entry);
            if (object.catalog() != null && !object.catalog().isBlank()) {
                byName.put(object.catalog() + "." + qualified(entry), entry);
            }
            for (var column : entry.columns().stream().limit(48).toList()) {
                // 固定模型能引用的稳定字段及当次修订。
                var id = Long.toString(column.columnId());
                refs.put(id, new StructureRef(Long.toString(entry.id()), Ids.text(entry.observation().revisionId()),
                        id, Long.toString(column.revisionId()), qualified(entry) + "." + column.name(),
                        column.nativeType()));
            }
        }
        var tables = new ArrayList<Table>();
        for (Entry entry : peers) {
            // 外键必须在同一成功范围内逐对定位完整成员。
            var structure = entry.observation().structure();
            var primary = structure.primaryKeys().stream().map(key -> key.field()).collect(Collectors.toSet());
            var fields = entry.columns().stream().limit(48).map(column -> new Field(Long.toString(column.columnId()),
                    column.name(), column.nativeType(), column.sourceComment(), primary.contains(column.name()))).toList();
            var grouped = structure.foreignKeys().stream().collect(Collectors.groupingBy(key -> key.name()));
            var keys = new ArrayList<List<Pair>>();
            for (var group : grouped.values()) {
                // 以采集键内的顺序产生完整配对。
                var pairs = group.stream().sorted(java.util.Comparator.comparingInt(key -> key.ordinal()))
                        .map(key -> {
                            // 源外目标或缺少任一成员时，这个键不进入发现范围。
                            var target = byName.get(key.referencedObject());
                            var left = entry.named(key.field());
                            var right = target == null ? null : target.named(key.referencedField());
                            return left == null || right == null ? null
                                    : new Pair(Long.toString(left.columnId()), Long.toString(right.columnId()));
                        }).toList();
                if (pairs.stream().allMatch(pair -> pair != null
                        && refs.containsKey(pair.left()) && refs.containsKey(pair.right()))) {
                    // 只提供可核实的完整外键，而不是同名线索。
                    keys.add(pairs);
                }
            }
            tables.add(new Table(Long.toString(entry.id()), qualified(entry), fields, keys));
        }
        return new DiscoveryInput(new Scope(kind, Long.toString(anchor.id()), tables), refs);
    }

    /**
     * 校验完整结果后持久化可审查的候选，重复发现不会改写旧记录。
     */
    public void finish(GovernanceGenerationPO task, DiscoveryInput input, List<Note> notes, List<Proposal> proposals) {
        if (proposals == null || proposals.size() > 6) {
            // 仅处理明确有限的发现结果。
            throw BizIllegalException.invalid("模型返回的候选格式不正确");
        }
        var prepared = new ArrayList<Prepared>();
        for (Proposal proposal : proposals) {
            // 写入前完成所有业务验证。
            var value = prepare(input, notes, proposal);
            if (value != null) {
                prepared.add(value);
            }
        }
        for (Prepared value : prepared) {
            // 身份按方向、字段、口径及限制决定，保留已处理的记录。
            String identity = digest(json.write(List.of(value.definition().pairs(), value.definition().fields(),
                    value.definition().meaning(), value.definition().rule(), value.definition().usage())));
            if (candidates.exists(task.getSourceId(), task.getDiscoveryKind(), identity) != null) {
                continue;
            }
            long id = IdWorker.getId();
            candidates.add(id, task.getRequestId(), task.getSourceId(), task.getObjectId(), task.getDiscoveryKind(),
                    identity, task.getSnapshotId(), json.write(value.basis()));
            value.basis().structures().stream().map(StructureRef::objectId).distinct()
                    .forEach(object -> candidates.attach(id, Ids.parse(object)));
            candidates.revise(IdWorker.getId(), id, 1, "CANDIDATE", json.write(value.definition()),
                    "[]", task.getRequestedBy());
        }
    }

    /**
     * 以实际同源对象分页读取候选。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<GovernanceCandidateVO> list(long source, long object, String kind, PageQuery page) {
        node(kind);
        var origin = catalog.source(source, false);
        return PageVO.from(candidates.list(page.toFixedOrderMpPage(), source, object, kind),
                row -> view(row, origin));
    }

    /**
     * 以当前候选版本保存人工内容，并在确认时固定已审核的依赖。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GovernanceCandidateVO review(long source, long object, long id, GovernanceCandidateReviewDTO input) {
        var origin = catalog.source(source, true);
        var row = candidates.lock(source, object, id);
        if (row == null) {
            // 非当前同源表的候选不可办理。
            throw BizIllegalException.missing();
        }
        if (row.version() != input.getVersion()) {
            // 并行编辑采用当前版本比较。
            throw BizIllegalException.conflict();
        }
        var basis = json.read(row.evidence(), Basis.class);
        if (!basis.notes().isEmpty()) {
            // 工作人员必须能核对生成时实际采用的资料。
            permissions.require("business-notes.view");
        }
        var before = json.read(row.content(), Definition.class);
        var definition = new Definition(text(input.getName()), text(input.getMeaning()), text(input.getRule()),
                text(input.getUsage()), before.pairs(), before.fields());
        boolean confirm = "CONFIRM".equals(input.getAction());
        if (!confirm || !before.equals(definition)) {
            // 只拥有审核权限的人可确认已经保存的内容。
            permissions.require("governance.edit");
        }
        List<Dependency> dependencies = List.of();
        if (confirm) {
            // 核验人真正提交审核时，才记载确认及实际依赖。
            permissions.require("governance.confirm");
            if (definition.name().isBlank() || definition.meaning().isBlank() || definition.rule().isBlank()) {
                throw BizIllegalException.invalid("请填写业务名称、含义和完整规则，再确认治理");
            }
            references(definition, basis.structures());
            dependencies = dependencies(origin, basis);
        }
        String state = confirm ? "CONFIRMED" : "DRAFT";
        String content = json.write(definition);
        String versions = json.write(dependencies);
        candidates.revise(IdWorker.getId(), id, row.version() + 1, state, content, versions, UserContext.getUser());
        if (candidates.promote(id, row.version()) != 1) {
            // 指针推进失败时本次新修订回滚。
            throw BizIllegalException.conflict();
        }
        return view(new Candidate(id, row.requestId(), source, row.kind(), row.snapshotId(), row.evidence(),
                row.version() + 1, state, content, versions), origin);
    }

    /**
     * 对模型结果做同源、方向、完整键及来源引用验证。
     */
    private Prepared prepare(DiscoveryInput input, List<Note> inputs, Proposal proposal) {
        if (proposal == null || text(proposal.name()).isBlank() || proposal.name().length() > 80) {
            // 不生成无法被办理的空白条目。
            return null;
        }
        var scope = input.scope();
        var pairs = proposal.pairs() == null ? List.<Pair>of() : proposal.pairs();
        var fieldIds = new LinkedHashSet<String>();
        boolean foreignKey = false;
        if ("RELATION".equals(scope.kind())) {
            // 同一条关系只能连接两个真实同源表，完整字段对同向。
            if (pairs.isEmpty() || pairs.size() > 12 || pairs.stream().distinct().count() != pairs.size()) {
                return null;
            }
            String leftId = null;
            String rightId = null;
            for (Pair pair : pairs) {
                // 只使用当次目录列入范围的稳定字段标识。
                var left = input.refs().get(pair.left());
                var right = input.refs().get(pair.right());
                if (left == null || right == null || left.objectId().equals(right.objectId())
                        || leftId != null && (!leftId.equals(left.objectId()) || !rightId.equals(right.objectId()))) {
                    return null;
                }
                leftId = left.objectId();
                rightId = right.objectId();
                fieldIds.add(left.fieldId());
                fieldIds.add(right.fieldId());
            }
            if (!scope.anchor().equals(leftId) && !scope.anchor().equals(rightId)) {
                return null;
            }
            foreignKey = scope.tables().stream().flatMap(table -> table.foreignKeys().stream())
                    .anyMatch(key -> sameKey(key, pairs));
        } else {
            // 指标必须关联当前表，公式涉及的字段也必须来自当次目录。
            if (!pairs.isEmpty() || proposal.fields() == null || proposal.fields().isEmpty()) {
                return null;
            }
            fieldIds.addAll(proposal.fields());
        }
        if (!input.refs().keySet().containsAll(fieldIds)) {
            return null;
        }
        var structures = fieldIds.stream().map(input.refs()::get).toList();
        if (structures.stream().noneMatch(ref -> scope.anchor().equals(ref.objectId()))) {
            return null;
        }
        var noteRefs = citations(proposal.evidence(), inputs);
        if ("RELATION".equals(scope.kind()) && !foreignKey && noteRefs.isEmpty()) {
            // 同名字段无法单独证明关系，必须有外键或实际资料。
            return null;
        }
        if ("METRIC".equals(scope.kind()) && noteRefs.isEmpty() && fieldIds.stream().noneMatch(
                id -> scope.tables().stream().flatMap(table -> table.fields().stream())
                        .anyMatch(field -> id.equals(field.id()) && (field.primary() || numeric(field.type()))))) {
            // 无资料时只给有记录标识或真实数量字段的指标线索。
            return null;
        }
        var definition = new Definition(text(proposal.name()), text(proposal.meaning()), text(proposal.rule()),
                text(proposal.usage()), pairs, List.copyOf(fieldIds));
        if (List.of(definition.meaning(), definition.rule(), definition.usage()).stream()
                .anyMatch(value -> value.length() > 4000)) {
            return null;
        }
        references(definition, structures);
        return new Prepared(definition, new Basis(structures, noteRefs, foreignKey));
    }

    /**
     * 比较完整键成员，允许一个固定外键被从目标端解释。
     */
    private boolean sameKey(List<Pair> key, List<Pair> proposal) {
        return key.size() == proposal.size() && (key.containsAll(proposal)
                || key.stream().map(pair -> new Pair(pair.right(), pair.left())).toList().containsAll(proposal));
    }

    /**
     * 必须回指真实输入列表和实际正文范围。
     */
    private List<NoteRef> citations(List<Evidence> evidence, List<Note> notes) {
        if (evidence == null) {
            return List.of();
        }
        var selected = new LinkedHashSet<NoteRef>();
        for (Evidence ref : evidence) {
            // 无法定位的模型引用不进入候选。
            var note = notes.stream().filter(value -> Long.toString(value.noteId()).equals(ref.noteId()))
                    .findFirst().orElse(null);
            if (note == null) {
                continue;
            }
            boolean valid = ref.attachmentId() != null
                    ? note.files().stream().anyMatch(file -> Long.toString(file.id()).equals(ref.attachmentId()))
                    : ref.start() != null && ref.end() != null && ref.start() >= 0
                    && ref.end() > ref.start() && ref.end() <= note.body().length();
            if (valid) {
                // 仅保存确切版本与位置，不复制原文。
                selected.add(new NoteRef(ref.noteId(), Long.toString(note.revisionId()), note.revision(),
                        note.title(), ref.attachmentId(), ref.start(), ref.end()));
            }
        }
        return List.copyOf(selected);
    }

    /**
     * 技术引用须能在给出的实际字段集合中找到。
     */
    private void references(Definition value, List<StructureRef> structures) {
        // 字符串常量不参与物理字段判断。
        var source = String.join(" ", value.meaning(), value.rule(), value.usage())
                .replaceAll("'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"", "");
        var matcher = QUALIFIED.matcher(source);
        while (matcher.find()) {
            // 保留表限定名，避免把同名字段当作等价。
            String qualified = matcher.group();
            if (structures.stream().noneMatch(ref -> ref.label().equalsIgnoreCase(qualified)
                    || ref.label().toLowerCase(java.util.Locale.ROOT)
                            .endsWith("." + qualified.toLowerCase(java.util.Locale.ROOT)))) {
                throw BizIllegalException.invalid("技术引用未纳入当前依赖：" + qualified);
            }
        }
    }

    /**
     * 审核前锁定当前表说明和字段定义。
     */
    private List<Dependency> dependencies(Source origin, Basis basis) {
        var targets = new LinkedHashSet<GovernanceTarget>();
        for (StructureRef ref : basis.structures()) {
            // 优先检查发现时的技术依赖仍有效。
            var object = Ids.parse(ref.objectId());
            var target = GovernanceTarget.column(origin.id(), object, Ids.parse(ref.fieldId()));
            var field = entries.get(origin, target);
            if (!"AVAILABLE".equals(field.availability())
                    || !ref.fieldRevision().equals(Ids.text(field.columnRevision()))
                    || !ref.objectRevision().equals(Ids.text(field.entry().observation().revisionId()))) {
                throw BizIllegalException.state("来源结构已变化，请重新发现：" + ref.label());
            }
            targets.add(GovernanceTarget.table(origin.id(), object));
            targets.add(target);
        }
        for (NoteRef ref : basis.notes()) {
            // 原资料撤下或实质更新后不能用旧候选走新审核。
            var prior = notes.revision(origin.id(), Ids.parse(ref.noteId()), Ids.parse(ref.revisionId()));
            if (!notes.current(origin.id(), prior.noteId()).equivalent(prior)) {
                throw BizIllegalException.state("业务依据已变化，请重新发现：" + ref.title());
            }
        }
        return targets.stream().map(target -> confirmed(origin, target)).toList();
    }

    /**
     * 要求依赖的当前已保存定义就是其最后一次确认。
     */
    private Dependency confirmed(Source source, GovernanceTarget target) {
        var context = entries.get(source, target);
        var item = context.item();
        if (!"AVAILABLE".equals(context.availability()) || item == null || item.getHeadRevisionId() == null
                || !Objects.equals(item.getHeadRevisionId(), item.getLastConfirmedRevisionId())) {
            var label = context.field() == null ? qualified(context.entry()) : context.field().name();
            throw BizIllegalException.state("请先确认依赖：" + label);
        }
        return new Dependency(target.object(), target.kind(), target.target(), item.getHeadRevisionId());
    }

    /**
     * 候选可回读，资料没有查看权限时只返回安全结构和状态。
     */
    private GovernanceCandidateVO view(Candidate row, Source source) {
        var basis = json.read(row.evidence(), Basis.class);
        boolean visible = basis.notes().isEmpty() || permissions.has("business-notes.view");
        var result = new GovernanceCandidateVO();
        result.setId(Long.toString(row.id()));
        result.setKind(row.kind());
        result.setRequestId(row.requestId());
        result.setSnapshotId(Long.toString(row.snapshotId()));
        result.setVersion(row.version());
        String state = row.state();
        if ("CONFIRMED".equals(state)) {
            // 依赖再次改变后保留原确认版本，当前提示复核。
            try {
                var current = dependencies(source, basis);
                var recorded = List.of(json.read(row.dependencies(), Dependency[].class));
                if (!current.equals(recorded)) {
                    // 比较依赖的实际编号，避免 JSON 数字节点的宽度影响状态。
                    state = "REVIEW";
                }
            } catch (BizIllegalException stale) {
                // 依赖不可用时保留历史确认，提示重新核对。
                state = "REVIEW";
            }
        }
        result.setState(state);
        result.setDefinition(visible ? json.read(row.content(), Definition.class) : null);
        result.setBasis(visible ? basis : new Basis(basis.structures(), List.of(), basis.foreignKey()));
        return result;
    }

    /**
     * 取得确切观察的显示路径。
     */
    private String qualified(Entry entry) {
        var object = entry.observation().structure().object();
        return object.schema() == null || object.schema().isBlank() ? object.name()
                : object.schema() + "." + object.name();
    }

    /**
     * 判断可参与计算的物理字段类型。
     */
    private boolean numeric(String type) {
        return type.toUpperCase(java.util.Locale.ROOT).matches(".*(INT|DECIMAL|NUMERIC|REAL|FLOAT|DOUBLE).*");
    }

    /**
     * 文本持久化前统一去除边缘空白。
     */
    private String text(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 取得固定大小的完整候选身份。
     */
    private String digest(String signature) {
        try {
            // SHA-256 只用于幂等指纹，不替代业务权限。
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(signature.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * @param scope 只交给模型的实有目录
     * @param refs 服务端确切字段查找表
     */
    record DiscoveryInput(Scope scope, Map<String, StructureRef> refs) {}

    /**
     * @param definition 已核对的候选正文
     * @param basis 原始实际证据
     */
    private record Prepared(Definition definition, Basis basis) {}

    /**
     * @param object 当前实际对象
     * @param kind 表说明或字段
     * @param target 稳定条目身份
     * @param revision 已确认定义修订
     */
    private record Dependency(long object, String kind, long target, long revision) {}
}
