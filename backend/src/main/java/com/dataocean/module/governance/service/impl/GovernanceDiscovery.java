package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Entry;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.datasource.service.GovernanceNotes;
import com.dataocean.module.datasource.service.GovernanceNotes.Note;
import com.dataocean.module.governance.entity.dto.GovernanceCandidateDraftDTO;
import com.dataocean.module.governance.entity.dto.GovernanceCandidateReviewDTO;
import com.dataocean.module.governance.entity.dto.GovernanceTargetDTO;
import com.dataocean.module.governance.entity.po.GovernanceGenerationPO;
import com.dataocean.module.governance.entity.vo.GovernanceCandidateVO;
import com.dataocean.module.governance.entity.vo.GovernanceCandidateVO.Definition;
import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
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
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.model.service.ModelRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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

    /** 人工创建使用共用的命令防重和提交回执。 */
    private final MutationService commands;

    /** 在创建事务内记录操作和稳定候选身份。 */
    private final AuditService audit;

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
                refs.put(
                        id,
                        new StructureRef(
                                Long.toString(entry.id()),
                                Ids.text(entry.observation().revisionId()),
                                id,
                                Long.toString(column.revisionId()),
                                qualified(entry) + "." + column.name(),
                                column.nativeType()));
            }
        }
        var tables = new ArrayList<Table>();
        for (Entry entry : peers) {
            // 外键必须在同一成功范围内逐对定位完整成员。
            var structure = entry.observation().structure();
            var primary =
                    structure.primaryKeys().stream().map(key -> key.field()).collect(Collectors.toSet());
            var fields = entry.columns().stream()
                    .limit(48)
                    .map(column -> new Field(
                            Long.toString(column.columnId()),
                            column.name(),
                            column.nativeType(),
                            column.sourceComment(),
                            primary.contains(column.name())))
                    .toList();
            var grouped = structure.foreignKeys().stream().collect(Collectors.groupingBy(key -> key.name()));
            var keys = new ArrayList<List<Pair>>();
            for (var group : grouped.values()) {
                // 以采集键内的顺序产生完整配对。
                var pairs = group.stream()
                        .sorted(Comparator.comparingInt(key -> key.ordinal()))
                        .map(key -> {
                            // 源外目标或缺少任一成员时，这个键不进入发现范围。
                            var target = byName.get(key.referencedObject());
                            var left = entry.named(key.field());
                            var right = target == null ? null : target.named(key.referencedField());
                            return left == null || right == null
                                    ? null
                                    : new Pair(Long.toString(left.columnId()), Long.toString(right.columnId()));
                        })
                        .toList();
                if (pairs.stream()
                        .allMatch(pair ->
                                pair != null && refs.containsKey(pair.left()) && refs.containsKey(pair.right()))) {
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
            // 同一口径换用新依据时保留旧记录，生成可办理的新候选。
            String definitionKey = digest(json.write(List.of(
                    value.definition().getPairs(),
                    value.definition().getFields(),
                    value.definition().getMeaning(),
                    value.definition().getRule(),
                    value.definition().getUsage())));
            String identity = identity(definitionKey, value.basis(), notes);
            if (alreadyDiscovered(task, definitionKey, identity, value.basis(), notes)) {
                // 同一有效依据继续沿用原有草稿与审核。
                continue;
            }
            long id = IdWorker.getId();
            candidates.add(
                    id,
                    task.getRequestId(),
                    task.getSourceId(),
                    task.getObjectId(),
                    task.getDiscoveryKind(),
                    identity,
                    task.getSnapshotId(),
                    json.write(value.basis()));
            value.basis().structures().stream()
                    .map(StructureRef::objectId)
                    .distinct()
                    .forEach(object -> candidates.attach(id, Ids.parse(object)));
            candidates.revise(
                    IdWorker.getId(),
                    id,
                    1,
                    "CANDIDATE",
                    json.write(value.definition()),
                    "[]",
                    task.getRequestedBy(),
                    null);
        }
    }

    /**
     * 依据身份固定结构修订和所引资料的实质内容，标题变化不会产生重复候选。
     */
    private String identity(String definitionKey, Basis basis, List<Note> inputs) {
        var referencedIds = basis.notes().stream().map(NoteRef::noteId).collect(Collectors.toSet());
        var documents = inputs.stream()
                .filter(note -> referencedIds.contains(Long.toString(note.noteId())))
                .sorted(Comparator.comparingLong(Note::noteId))
                .map(note -> List.of(
                        note.noteId(),
                        note.body(),
                        note.files().stream()
                                .map(GovernanceNotes.File::digest)
                                .sorted()
                                .toList()))
                .toList();
        return digest(json.write(List.of(definitionKey, basis.structures(), basis.foreignKey(), documents)));
    }

    /**
     * 已按口径入库的旧候选也可复用，以固定依据的实质内容为准。
     */
    private boolean alreadyDiscovered(
            GovernanceGenerationPO task, String definitionKey, String identity, Basis current, List<Note> inputs) {
        long source = task.getSourceId();
        String kind = task.getDiscoveryKind();
        if (candidates.evidenceForIdentity(source, kind, identity) != null) {
            // 已按完整依据去重。
            return true;
        }
        String evidence = candidates.evidenceForIdentity(source, kind, definitionKey);
        if (evidence == null) {
            // 没有需比较依据的旧口径。
            return false;
        }
        Basis previous = json.read(evidence, Basis.class);
        var before = previous.notes().stream().map(NoteRef::noteId).collect(Collectors.toSet());
        var after = current.notes().stream().map(NoteRef::noteId).collect(Collectors.toSet());
        return previous.structures().equals(current.structures())
                && previous.foreignKey() == current.foreignKey()
                && before.equals(after)
                && previous.notes().stream().distinct().allMatch(ref -> {
                    // 从固定历史修订读取正文，标题不同仍可复用。
                    var old = notes.revision(source, Ids.parse(ref.noteId()), Ids.parse(ref.revisionId()));
                    return inputs.stream().anyMatch(note -> note.noteId() == old.noteId() && note.equivalent(old));
                });
    }

    /**
     * 以实际同源对象分页读取候选。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<GovernanceCandidateVO> list(long source, long object, String kind, PageQuery page) {
        node(kind);
        var origin = catalog.source(source, false);
        return PageVO.from(candidates.list(page.toFixedOrderMpPage(), source, object, kind), row -> view(row, origin));
    }

    /**
     * 人工新增独立于模型任务，首次提交同时固定来源、初始修订和审计。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO create(long source, long object, String kind, String command, GovernanceCandidateDraftDTO input) {
        node(kind);
        if ("REJECT".equals(input.getAction())) {
            throw BizIllegalException.invalid("新增候选只能保存或确认");
        }
        return commands.execute("governance.edit", command, "governance-candidate:" + source + "/" + object + "/" + kind,
                input, null, false, false, () -> createLocked(source, object, kind, command, input));
    }

    /**
     * 按当前成功目录保存人工内容；只填了部分定义时允许先提交草稿。
     */
    private MutationVO createLocked(long source, long object, String kind, String command, GovernanceCandidateDraftDTO input) {
        var origin = catalog.source(source, true);
        var anchor = entries.get(origin, GovernanceTarget.table(source, object));
        if (!"AVAILABLE".equals(anchor.availability())) {
            throw BizIllegalException.state("当前来源状态不允许新增治理定义");
        }
        var empty = new Definition("", "", "", "", List.of(), List.of(), false);
        var prepared = edited(origin, object, kind, empty, new Basis(List.of(), List.of(), false), input);
        var definition = prepared.definition();
        var basis = prepared.basis();
        if (definition.equals(empty)) {
            throw BizIllegalException.invalid("请填写治理内容或选择依赖字段");
        }
        boolean confirm = "CONFIRM".equals(input.getAction());
        List<Dependency> dependencies = List.of();
        if (confirm) {
            permissions.require("governance.confirm");
            dependencies = confirmDefinition(origin, definition, basis, true);
        }
        long id = IdWorker.getId();
        String target = Long.toString(id);
        String evidence = json.write(basis);
        candidates.add(id, null, source, object, kind, digest("manual:" + id), origin.snapshotId(), evidence);
        // 不完整的人工草稿仍归当前表，选定依赖后也能从实际涉及的表找到。
        candidates.attach(id, object);
        basis.structures().stream().map(StructureRef::objectId).distinct().map(Ids::parse)
                .filter(related -> related != object).forEach(related -> candidates.attach(id, related));
        candidates.revise(IdWorker.getId(), id, 1, confirm ? "CONFIRMED" : "DRAFT", json.write(definition),
                json.write(dependencies), UserContext.getUser(), evidence);
        audit.record(AuditWriteDTO.builder().actionCode("governance.edit").targetType("governance_candidate")
                .targetId(target).targetName(definition.getName().isBlank() ? kind : definition.getName())
                .securityVersion(commands.auditSecurityVersion(false)).commandId(UUID.fromString(command).toString())
                .build());
        return new MutationVO(command, "governance_candidate", target, 1);
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
        var original = json.read(row.evidence(), Basis.class);
        if (row.usedBusinessNotes() || !original.notes().isEmpty()) {
            // 模型漏标引用时，仍按实际发送的资料核对工作人员资格。
            permissions.require("business-notes.view");
        }
        var before = json.read(row.content(), Definition.class);
        boolean reject = "REJECT".equals(input.getAction());
        boolean confirm = "CONFIRM".equals(input.getAction());
        if (reject && !"RELATION".equals(row.kind())) {
            // 拒绝表示关系审核结论，仅适用于关系候选。
            throw BizIllegalException.invalid("只有关联候选可以拒绝");
        }
        var updated =
                reject ? new Prepared(before, original) : edited(origin, object, row.kind(), before, original, input);
        var definition = updated.definition();
        var basis = updated.basis();
        if (!reject && (!confirm || !before.equals(definition) || !original.equals(basis))) {
            // 只拥有审核权限的人可确认已经保存的内容。
            permissions.require("governance.edit");
        }
        List<Dependency> dependencies = List.of();
        if (confirm || reject) {
            // 核验人真正提交审核时，才记载确认及实际依赖。
            permissions.require("governance.confirm");
        }
        if (confirm) {
            dependencies = confirmDefinition(origin, definition, basis, row.requestId() == null);
        }
        String state = reject ? "REJECTED" : confirm ? "CONFIRMED" : "DRAFT";
        String content = json.write(definition);
        String versions = json.write(dependencies);
        String evidence = json.write(basis);
        candidates.revise(
                IdWorker.getId(), id, row.version() + 1, state, content, versions, UserContext.getUser(), evidence);
        if (!original.structures().equals(basis.structures())) {
            // 当前两端入口指向新依赖，历史修订继续保留原结构。
            candidates.detachObjects(id);
            basis.structures().stream()
                    .map(StructureRef::objectId)
                    .distinct()
                    .forEach(target -> candidates.attach(id, Ids.parse(target)));
        }
        if (candidates.promote(id, row.version()) != 1) {
            // 指针推进失败时本次新修订回滚。
            throw BizIllegalException.conflict();
        }
        return view(
                new Candidate(
                        id,
                        row.requestId(),
                        source,
                        row.kind(),
                        row.snapshotId(),
                        evidence,
                        row.version() + 1,
                        state,
                        content,
                        versions,
                        row.usedBusinessNotes()),
                origin);
    }

    /**
     * 审核复用业务字段、实际引用和依赖确认校验。
     */
    private List<Dependency> confirmDefinition(Source origin, Definition definition,
            Basis basis, boolean manual) {
        if (definition.getName().isBlank() || definition.getMeaning().isBlank() || definition.getRule().isBlank()) {
            throw BizIllegalException.invalid("请填写业务名称、含义和完整规则，再确认治理");
        }
        if (definition.isDefaultMetric() && definition.getUsage().isBlank()) {
            throw BizIllegalException.invalid("请填写默认口径的适用条件");
        }
        if (manual && basis.structures().isEmpty()) {
            // 部分草稿允许没有依赖，正式确认至少需要当前对象的实际字段。
            throw BizIllegalException.invalid("请为当前治理对象选择依赖字段");
        }
        references(definition, basis.structures());
        return dependencies(origin, basis);
    }

    /**
     * 保存前将人工修改的依赖解析为当前同源目录及确切结构版本。
     */
    private Prepared edited(
            Source source,
            long anchor,
            String kind,
            Definition before,
            Basis original,
            GovernanceCandidateDraftDTO input) {
        boolean relation = "RELATION".equals(kind);
        boolean defaultMetric = input.getDefaultMetric() == null ? before.isDefaultMetric() : input.getDefaultMetric();
        if (relation && defaultMetric
                || !relation && input.getPairs() != null && !input.getPairs().isEmpty()) {
            // 关联和指标只接受各自的专属定义。
            throw BizIllegalException.invalid("所选定义与当前治理类型不符");
        }
        var pairs = before.getPairs();
        var basis = original;
        if (input.getFields() != null || input.getPairs() != null) {
            var refs = new LinkedHashMap<String, StructureRef>();
            var oldPairs = before.getPairs().stream()
                    .flatMap(pair -> java.util.stream.Stream.of(pair.left(), pair.right()))
                    .collect(Collectors.toSet());
            if (input.getFields() == null) {
                // 未提交替换时保留旧用途和筛选字段，旧连接字段由当前字段对提供。
                original.structures().stream()
                        .filter(ref -> input.getPairs() == null || !oldPairs.contains(ref.fieldId()))
                        .map(ref -> structure(source, ref.objectId(), ref.fieldId()))
                        .forEach(ref -> refs.put(ref.fieldId(), ref));
            } else {
                // 显式选择的范围按稳定对象和字段身份重读。
                input.getFields().stream()
                        .map(target -> structure(source, target))
                        .forEach(ref -> refs.put(ref.fieldId(), ref));
            }
            if (input.getPairs() != null) {
                // 修改完整连接键后，原字段对留在历史定义。
                pairs = input.getPairs().stream()
                        .map(pair -> {
                            var left = structure(source, pair.getLeft());
                            var right = structure(source, pair.getRight());
                            refs.put(left.fieldId(), left);
                            refs.put(right.fieldId(), right);
                            return new Pair(left.fieldId(), right.fieldId());
                        })
                        .toList();
            } else {
                // 只修改筛选字段时仍保留现有关系键。
                original.structures().stream()
                        .filter(ref -> oldPairs.contains(ref.fieldId()))
                        .map(ref -> structure(source, ref.objectId(), ref.fieldId()))
                        .forEach(ref -> refs.put(ref.fieldId(), ref));
            }
            var structures = List.copyOf(refs.values());
            validateSelection(anchor, relation, pairs, structures, refs);
            basis = new Basis(
                    structures,
                    original.notes(),
                    original.foreignKey()
                            && before.getPairs().equals(pairs)
                            && original.structures().stream().allMatch(structures::contains));
        }
        var definition = new Definition(
                text(input.getName()),
                text(input.getMeaning()),
                text(input.getRule()),
                text(input.getUsage()),
                pairs,
                basis.structures().stream().map(StructureRef::fieldId).toList(),
                defaultMetric);
        return new Prepared(definition, basis);
    }

    /**
     * 保证完整连接键定位到同一对表，且至少一端是当前治理对象。
     */
    private void validateSelection(
            long anchor,
            boolean relation,
            List<Pair> pairs,
            List<StructureRef> structures,
            Map<String, StructureRef> refs) {
        String object = Long.toString(anchor);
        if (structures.stream().noneMatch(ref -> object.equals(ref.objectId()))) {
            // 指标和关联都必须真实涉及当前页面对象。
            throw BizIllegalException.invalid("请为当前治理对象选择依赖字段");
        }
        if (relation) {
            if (pairs.isEmpty() || pairs.stream().distinct().count() != pairs.size()) {
                // 复合关系必须包含明确且不重复的完整字段对。
                throw BizIllegalException.invalid("请选择不重复的连接字段对");
            }
            String left = refs.get(pairs.get(0).left()).objectId();
            String right = refs.get(pairs.get(0).right()).objectId();
            if (left.equals(right)
                    || !(object.equals(left) || object.equals(right))
                    || pairs.stream()
                            .anyMatch(pair -> !left.equals(refs.get(pair.left()).objectId())
                                    || !right.equals(refs.get(pair.right()).objectId()))) {
                // 防止混合多张表的字段构建一条不明确的关系。
                throw BizIllegalException.invalid("连接字段必须属于相同的两张同源表");
            }
        }
    }

    /**
     * 人工依赖仅接受页面定向选择的真实字段。
     */
    private StructureRef structure(Source source, GovernanceTargetDTO target) {
        if (!"COLUMN".equals(target.getKind())) {
            // 表说明依赖由确切字段所属对象推导，不能伪造字段身份。
            throw BizIllegalException.invalid("请选择真实字段");
        }
        return structure(source, target.getObjectId(), target.getTargetId());
    }

    /**
     * 固定当前成功目录里的实际字段与所属表版本。
     */
    private StructureRef structure(Source source, String object, String field) {
        var context = entries.get(source, GovernanceTarget.column(source.id(), Ids.parse(object), Ids.parse(field)));
        if (!"AVAILABLE".equals(context.availability())) {
            // 已移除或身份待核实的目标不能作为当前依赖。
            throw BizIllegalException.invalid("当前字段不可作为治理依赖");
        }
        return new StructureRef(
                object,
                Ids.text(context.entry().observation().revisionId()),
                field,
                Ids.text(context.columnRevision()),
                qualified(context.entry()) + "." + context.field().name(),
                context.field().nativeType());
    }

    /**
     * 对模型结果做同源、方向、完整键及来源引用验证。
     */
    private Prepared prepare(DiscoveryInput input, List<Note> inputs, Proposal proposal) {
        if (proposal == null
                || text(proposal.name()).isBlank()
                || proposal.name().length() > 80) {
            // 不生成无法被办理的空白条目。
            return null;
        }
        var scope = input.scope();
        var pairs = proposal.pairs() == null ? List.<Pair>of() : proposal.pairs();
        var fieldIds = new LinkedHashSet<String>();
        boolean foreignKey = false;
        if ("RELATION".equals(scope.kind())) {
            // 同一条关系只能连接两个真实同源表，完整字段对同向。
            if (pairs.isEmpty()
                    || pairs.size() > 12
                    || pairs.stream().distinct().count() != pairs.size()) {
                return null;
            }
            String leftId = null;
            String rightId = null;
            for (Pair pair : pairs) {
                // 只使用当次目录列入范围的稳定字段标识。
                var left = input.refs().get(pair.left());
                var right = input.refs().get(pair.right());
                if (left == null
                        || right == null
                        || left.objectId().equals(right.objectId())
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
            foreignKey = scope.tables().stream()
                    .flatMap(table -> table.foreignKeys().stream())
                    .anyMatch(key -> sameKey(key, pairs));
        } else {
            // 指标必须关联当前表，公式涉及的字段也必须来自当次目录。
            if (!pairs.isEmpty()
                    || proposal.fields() == null
                    || proposal.fields().isEmpty()) {
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
        if ("METRIC".equals(scope.kind())
                && noteRefs.isEmpty()
                && fieldIds.stream().noneMatch(id -> scope.tables().stream()
                        .flatMap(table -> table.fields().stream())
                        .anyMatch(field -> id.equals(field.id()) && (field.primary() || numeric(field.type()))))) {
            // 无资料时只给有记录标识或真实数量字段的指标线索。
            return null;
        }
        var definition = new Definition(
                text(proposal.name()),
                text(proposal.meaning()),
                text(proposal.rule()),
                text(proposal.usage()),
                pairs,
                List.copyOf(fieldIds),
                false);
        if (List.of(definition.getMeaning(), definition.getRule(), definition.getUsage()).stream()
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
        return key.size() == proposal.size()
                && (key.containsAll(proposal)
                        || key.stream()
                                .map(pair -> new Pair(pair.right(), pair.left()))
                                .toList()
                                .containsAll(proposal));
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
            var note = notes.stream()
                    .filter(value -> Long.toString(value.noteId()).equals(ref.noteId()))
                    .findFirst()
                    .orElse(null);
            if (note == null) {
                continue;
            }
            boolean valid = ref.attachmentId() != null
                    ? note.files().stream()
                            .anyMatch(file -> Long.toString(file.id()).equals(ref.attachmentId()))
                    : ref.start() != null
                            && ref.end() != null
                            && ref.start() >= 0
                            && ref.end() > ref.start()
                            && ref.end() <= note.body().length();
            if (valid) {
                // 仅保存确切版本与位置，不复制原文。
                selected.add(new NoteRef(
                        ref.noteId(),
                        Long.toString(note.revisionId()),
                        note.revision(),
                        note.title(),
                        ref.attachmentId(),
                        ref.start(),
                        ref.end()));
            }
        }
        return List.copyOf(selected);
    }

    /**
     * 技术引用须能在给出的实际字段集合中找到。
     */
    private void references(Definition value, List<StructureRef> structures) {
        // 字符串常量不参与物理字段判断。
        var source = String.join(" ", value.getMeaning(), value.getRule(), value.getUsage())
                .replaceAll("'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"", "");
        var matcher = QUALIFIED.matcher(source);
        while (matcher.find()) {
            // 保留表限定名，避免把同名字段当作等价。
            String qualified = matcher.group();
            if (structures.stream()
                    .noneMatch(ref -> ref.label().equalsIgnoreCase(qualified)
                            || ref.label()
                                    .toLowerCase(Locale.ROOT)
                                    .endsWith("." + qualified.toLowerCase(Locale.ROOT)))) {
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
                    || !ref.objectRevision()
                            .equals(Ids.text(field.entry().observation().revisionId()))) {
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
        if (!"AVAILABLE".equals(context.availability())
                || item == null
                || item.getHeadRevisionId() == null
                || !Objects.equals(item.getHeadRevisionId(), item.getLastConfirmedRevisionId())) {
            var label = context.field() == null
                    ? qualified(context.entry())
                    : context.field().name();
            throw BizIllegalException.state("请先确认依赖：" + label);
        }
        return new Dependency(target.object(), target.kind(), target.target(), item.getHeadRevisionId());
    }

    /** 将关联或指标当前确认连同固定依赖交付给知识模块。 */
    GovernanceFactVO confirmedForKnowledge(Source source, GovernanceTarget target) {
        var row = candidates.read(source.id(), target.object(), target.target());
        if (row == null || !row.kind().equals(target.kind())) {
            throw BizIllegalException.missing();
        }
        var candidate = view(row, source);
        var anchor = entries.get(source, GovernanceTarget.table(source.id(), target.object()));
        if (candidate.getDefinition() == null) {
            throw BizIllegalException.denied();
        }
        if (!"CONFIRMED".equals(candidate.getState()) || !"AVAILABLE".equals(anchor.availability())) {
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "关联或指标的审核依据需要复核");
        }
        var refs = List.of(json.read(row.dependencies(), Dependency[].class)).stream()
                .map(ref -> new GovernanceTarget(source.id(), ref.object(), ref.kind(), ref.target()).view())
                .toList();
        return new GovernanceFactVO(target.view(), Ids.text(candidates.revisionId(row.id(), row.version())),
                anchor.entry().generation(), Long.toString(anchor.structureRevision()), candidate.getDefinition().getName(),
                json.mapper().valueToTree(candidate.getDefinition()), refs);
    }

    /**
     * 候选可回读，资料没有查看权限时只返回安全结构和状态。
     */
    private GovernanceCandidateVO view(Candidate row, Source source) {
        var basis = json.read(row.evidence(), Basis.class);
        boolean visible = !row.usedBusinessNotes() && basis.notes().isEmpty() || permissions.has("business-notes.view");
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
        return object.schema() == null || object.schema().isBlank()
                ? object.name()
                : object.schema() + "." + object.name();
    }

    /**
     * 判断可参与计算的物理字段类型。
     */
    private boolean numeric(String type) {
        return type.toUpperCase(Locale.ROOT).matches(".*(INT|DECIMAL|NUMERIC|REAL|FLOAT|DOUBLE).*");
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
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(signature.getBytes(StandardCharsets.UTF_8)));
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
