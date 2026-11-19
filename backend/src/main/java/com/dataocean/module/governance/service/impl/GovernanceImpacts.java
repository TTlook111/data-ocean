package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.datasource.mapper.GovernanceCatalogMapper.Column;
import com.dataocean.module.datasource.mapper.GovernanceCatalogMapper.Fact;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Entry;
import com.dataocean.module.datasource.service.GovernanceNotes;
import com.dataocean.module.datasource.service.SourceNoteChanged;
import com.dataocean.module.datasource.service.SourceStructureChanged;
import com.dataocean.module.datasource.source.SourceMetadata.Key;
import com.dataocean.module.governance.entity.po.GovernanceImpactPO;
import com.dataocean.module.governance.entity.po.GovernanceItemPO;
import com.dataocean.module.governance.entity.po.GovernanceNoteChangePO;
import com.dataocean.module.governance.mapper.GovernanceControlMapper;
import com.dataocean.module.governance.mapper.GovernanceImpactMapper;
import com.dataocean.module.governance.mapper.GovernanceNoteChangeMapper;
import com.dataocean.module.governance.mapper.GovernanceReadMapper;
import com.dataocean.module.governance.mapper.GovernanceRevisionMapper;
import com.dataocean.module.governance.service.GovernanceTarget;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.JsonNode;

/**
 * 同步把 B 批确切结构和资料变动投影到受影响的真实条目。
 */
@Component
@RequiredArgsConstructor
public class GovernanceImpacts {

    /**
     * 数据类型明确收窄时采用原生类型提供的长度或精度。
     */
    private static final Pattern WIDTH = Pattern.compile("^[a-z ]+[(]([0-9]+)(?:, *([0-9]+))?[)].*");

    /**
     * B 批成功结构、证据和来源状态服务。
     */
    private final GovernanceCatalog catalog;

    /**
     * 同源资料及确切修订能力。
     */
    private final GovernanceNotes notes;

    /**
     * 判定实际采用的业务证据是否受影响。
     */
    private final GovernanceEvidence evidence;

    /**
     * 稳定条目及版本更新。
     */
    private final GovernanceEntries entries;

    /**
     * 当前和历史来源引用的投影查询。
     */
    private final GovernanceReadMapper reads;

    /**
     * 按固定修订取得实际引用条目。
     */
    private final GovernanceRevisionMapper revisions;

    /**
     * 存储条目复核和审核覆盖关系。
     */
    private final GovernanceImpactMapper impacts;

    /**
     * 资料变化证据与待定位状态。
     */
    private final GovernanceNoteChangeMapper noteChanges;

    /**
     * 同事务使用限制及其来源记录。
     */
    private final GovernanceControlMapper controls;

    /**
     * 已落实使用限制的提交后通知。
     */
    private final GovernanceEvents events;

    /**
     * 按 B 批的实际值结构解析字段和属性。
     */
    private final JsonCodec json;

    /**
     * 成功目录指针、事实、使用限制和实际复核作为同一事务的一部分。
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void structureChanged(SourceStructureChanged event) {
        for (Fact fact : catalog.facts(event)) {
            // 每次来源通知只处理此次提交或确证的变化，历史不被重新打开。
            boolean resolved = project(event.sourceId(), fact);
            if (resolved) {
                catalog.stage(event.sourceId(), fact.id(), stage(byStructure(fact.id()), false));
            }
        }
    }

    /**
     * 只把当前定义中仍在真实使用的资料关联到复核。
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void noteChanged(SourceNoteChanged event) {
        var before = notes.revision(event.sourceId(), event.noteId(), event.beforeRevisionId());
        var after = event.afterRevisionId() == null ? null
                : notes.revision(event.sourceId(), event.noteId(), event.afterRevisionId());
        if (after != null && before.equivalent(after)) {
            // 原件整体和正文相同，复核状态延续原事实。
            return;
        }
        Set<Long> itemIds = new LinkedHashSet<>();
        for (var reference : reads.currentNoteReferences(event.sourceId(), event.noteId())) {
            // r1 与 r2 仅标题不同的场景仍可通过实际证据反查。
            var original = notes.revision(event.sourceId(), event.noteId(), reference.getNoteRevisionId());
            if (!evidence.applies(reference, original, after)) {
                itemIds.add(revisions.selectById(reference.getItemRevisionId()).getItemId());
            }
        }
        if (itemIds.isEmpty()) {
            // 只有历史取用的资料仍受删除保护，当前无须产生业务复核。
            return;
        }
        var change = new GovernanceNoteChangePO();
        change.setSourceId(event.sourceId());
        change.setNoteId(event.noteId());
        change.setNoteVersion(event.noteVersion());
        change.setBeforeRevisionId(event.beforeRevisionId());
        change.setAfterRevisionId(event.afterRevisionId());
        change.setKind(after == null ? "WITHDRAWN" : "CONTENT_CHANGED");
        change.setMappingState("MAPPED");
        change.setVersion(1);
        noteChanges.insert(change);

        for (Long id : itemIds) {
            // 一个说明变化可定位多条定义，每条只新增一次待处理项。
            link(event.sourceId(), id, null, change.getId(), "RECHECK");
        }
    }

    /**
     * 查询条目的具体影响及全部处理状态。
     */
    public List<GovernanceImpactPO> forItem(long itemId) {
        return impacts.selectList(Wrappers.<GovernanceImpactPO>lambdaQuery()
                .eq(GovernanceImpactPO::getItemId, itemId)
                .orderByDesc(GovernanceImpactPO::getId));
    }

    /**
     * 以一个对象为范围取待办，包含当前目录已移除的字段。
     */
    public List<GovernanceImpactPO> pending(long source, long object) {
        var ids = reads.items(source, object).stream().map(GovernanceItemPO::getId).toList();
        if (ids.isEmpty()) {
            // 不允许空集合释放 SQL 查询范围。
            return List.of();
        }
        return impacts.selectList(Wrappers.<GovernanceImpactPO>lambdaQuery()
                .in(GovernanceImpactPO::getItemId, ids)
                .ne(GovernanceImpactPO::getState, "REVIEWED")
                .orderByAsc(GovernanceImpactPO::getId));
    }

    /**
     * 当前条目是否有实际变化待办。
     */
    public boolean open(long itemId) {
        return impacts.exists(Wrappers.<GovernanceImpactPO>lambdaQuery()
                .eq(GovernanceImpactPO::getItemId, itemId)
                .ne(GovernanceImpactPO::getState, "REVIEWED"));
    }

    /**
     * 草稿、补充及任务接收实际开始时推动尚未办理的影响。
     */
    public void started(long source, long item) {
        for (var impact : forItem(item)) {
            // 已开始或已完成的原时间保留。
            if ("PENDING".equals(impact.getState())) {
                impacts.update(Wrappers.<GovernanceImpactPO>lambdaUpdate()
                        .eq(GovernanceImpactPO::getId, impact.getId())
                        .set(GovernanceImpactPO::getState, "IN_PROGRESS")
                        .setSql("started_at=UTC_TIMESTAMP(3)"));
                refresh(source, impact.getStructureChangeId());
            }
        }
    }

    /**
     * 保存本次确实完成的条目复核，再汇总每笔实际来源。
     */
    public List<String> resolve(GovernanceItemPO item, long confirmation, boolean removal, boolean blocked) {
        Set<Long> origin = new HashSet<>();
        var resolved = new java.util.ArrayList<String>();
        for (var impact : forItem(item.getId())) {
            // 已确认的历史结果留在原确认记录上。
            if ("REVIEWED".equals(impact.getState())) {
                continue;
            }
            boolean knowledge = removal || blocked || "NEW_ITEM".equals(impact.getImpactKind())
                    || !Objects.equals(item.getHeadRevisionId(), impact.getBaselineRevisionId());
            impacts.update(Wrappers.<GovernanceImpactPO>lambdaUpdate()
                    .eq(GovernanceImpactPO::getId, impact.getId())
                    .set(GovernanceImpactPO::getState, "REVIEWED")
                    .set(GovernanceImpactPO::getConfirmationId, confirmation)
                    .set(GovernanceImpactPO::getKnowledgeRequired, knowledge)
                    .setSql("started_at=COALESCE(started_at,UTC_TIMESTAMP(3)), resolved_at=UTC_TIMESTAMP(3)"));
            origin.add(impact.getStructureChangeId());
            resolved.add(Long.toString(impact.getId()));
        }
        origin.forEach(id -> refresh(item.getSourceId(), id));
        return resolved;
    }

    /**
     * 将已有待定位的事实关联到经过核验的同源目标。
     */
    public void associate(GovernanceNoteChangePO change, GovernanceItemPO item) {
        link(change.getSourceId(), item.getId(), null, change.getId(), "RECHECK");
    }

    /**
     * 返回资料的实际全部目标，供所属入口汇总当前处理阶段。
     */
    public List<GovernanceImpactPO> byNote(long change) {
        return impacts.selectList(Wrappers.<GovernanceImpactPO>lambdaQuery()
                .eq(GovernanceImpactPO::getNoteChangeId, change)
                .orderByAsc(GovernanceImpactPO::getId));
    }

    /**
     * 当前阶段以全部必要实际影响计算。
     */
    public String stage(List<GovernanceImpactPO> records, boolean needsMapping) {
        if (needsMapping || records.stream().anyMatch(record -> !"REVIEWED".equals(record.getState()))) {
            // 已经有实际办理的来源不能因新增影响倒退到未开始。
            return records.stream().anyMatch(record -> !"PENDING".equals(record.getState()))
                    ? "GOVERNANCE_IN_PROGRESS" : "PENDING_GOVERNANCE";
        }
        return records.stream().anyMatch(record -> Boolean.TRUE.equals(record.getKnowledgeRequired()))
                ? "KNOWLEDGE_IN_PROGRESS" : "COMPLETED";
    }

    /**
     * 人工操作和确证风险共用权威限制及可靠事件。
     */
    public void forbid(GovernanceItemPO item, Long change, String code, String reason, String command, Long actor) {
        if (change != null && controls.recorded(item.getId(), change)) {
            // 来源变化同一影响不制造新的限制版本。
            return;
        }
        controls.forbid(item.getId());
        int version = controls.version(item.getId());
        controls.event(item.getId(), version, code, reason, change, command, actor);
        if (change != null) {
            // 通过 B 批记录实际限制引用，保留查阅路径。
            catalog.link(change, "USAGE_CONTROL", item.getId());
        }
        events.usage(item.getSourceId(), item.getId(), version, command);
    }

    /**
     * 按实质结构差异定位原身份、字段与直接依赖。
     */
    private boolean project(long source, Fact fact) {
        var before = catalog.observed(source, fact.beforeObservationId());
        var after = catalog.observed(source, fact.afterObservationId());
        if ("OBJECT_ADDED".equals(fact.changeType())) {
            // 新对象及其字段从空白开始处理。
            if (after == null) {
                return false;
            }
            markObject(source, after, fact, false);
            return true;
        }
        if (Set.of("OBJECT_REMOVED", "OBJECT_REBUILT").contains(fact.changeType())) {
            // 旧身份继续承载历史，新世代另起治理条目。
            if (before == null) {
                return false;
            }
            markObject(source, before, fact, true);
            if ("OBJECT_REBUILT".equals(fact.changeType()) && after != null && before.id() != after.id()) {
                markObject(source, after, fact, false);
            }
            return true;
        }
        if (Set.of("ACCESS_LOST", "COLUMN_IDENTITY_UNCERTAIN").contains(fact.changeType())) {
            // 目录可见性由 B 批处理，不能从空观察推导删除。
            return true;
        }
        if ("IDENTITY_UNCERTAIN".equals(fact.changeType()) && before != null && after != null) {
            // SAME_OBJECT 确认后比较两侧的实际字段，不用当前名称猜测。
            return projectIdentity(source, fact, before, after);
        }
        if ("COLUMN".equals(fact.targetKind())) {
            return projectColumn(source, fact, before, after);
        }
        if (before == null || after == null) {
            return false;
        }
        if ("CONSTRAINT".equals(fact.attributeKind())) {
            // 键名称和排列差异独立于真实业务键成员。
            projectKeys(source, fact, before, after);
        } else if (!"VIEW_REFRESH".equals(fact.attributeKind())) {
            // 对象注释、类型或父对象变化关联表说明及实际对象依赖。
            var target = entries.ensure(GovernanceTarget.table(source, after.id()), after.generation());
            link(source, target.getId(), fact.id(), null, "RECHECK");
            depends(source, after.id(), null, fact.id());
        }
        return true;
    }

    /**
     * 已核实同一对象后补算两侧变化。
     */
    private boolean projectIdentity(long source, Fact fact, Entry before, Entry after) {
        if (before.id() != after.id()) {
            // 身份不同的观察不可作为同一条定义的连续依据。
            return false;
        }
        var table = entries.ensure(GovernanceTarget.table(source, after.id()), after.generation());
        link(source, table.getId(), fact.id(), null, "RECHECK");
        for (var field : before.columns()) {
            var current = after.column(field.columnId());
            if (current == null || current.revisionId() != field.revisionId()) {
                // 仅对同一稳定字段或确证消失的历史字段办理影响。
                markColumn(source, fact, before, field, current == null ? "REMOVAL" : "RECHECK",
                        current == null || incompatible(field, current));
            }
        }
        for (var field : after.columns()) {
            if (before.column(field.columnId()) == null) {
                // 本次结构出现的新字段独立待治理。
                markColumn(source, fact, after, field, "NEW_ITEM", false);
            }
        }
        return true;
    }

    /**
     * 字段路径使用每侧成功观察中的稳定成员，历史与新世代永不串名。
     */
    private boolean projectColumn(long source, Fact fact, Entry before, Entry after) {
        Column old = before == null ? null : before.named(fieldName(fact.beforeValue()));
        Column current = after == null ? null : after.named(fieldName(fact.afterValue()));
        if ("COLUMN_ADDED".equals(fact.changeType()) && current != null) {
            markColumn(source, fact, after, current, "NEW_ITEM", false);
            return true;
        }
        if ("COLUMN_REMOVED".equals(fact.changeType()) && old != null) {
            markColumn(source, fact, before, old, "REMOVAL", true);
            return true;
        }
        if (current == null || old == null || old.columnId() != current.columnId()) {
            // 缺少连续性证据时保留原事实及证据，不能套用待复核条目。
            return false;
        }
        if (!"ORDINAL".equals(fact.attributeKind())) {
            markColumn(source, fact, after, current, "RECHECK", incompatible(old, current));
        }
        return true;
    }

    /**
     * 关联真正变化的主键字段，并只沿实际外键依赖定位既有定义。
     */
    private void projectKeys(long source, Fact fact, Entry before, Entry after) {
        var left = before.observation().structure();
        var right = after.observation().structure();
        var leftPrimary = left.primaryKeys().stream().map(Key::field).collect(Collectors.toSet());
        var rightPrimary = right.primaryKeys().stream().map(Key::field).collect(Collectors.toSet());
        if (!leftPrimary.equals(rightPrimary)) {
            // 行粒度实际受主键集合变化影响。
            var table = entries.ensure(GovernanceTarget.table(source, after.id()), after.generation());
            link(source, table.getId(), fact.id(), null, "RECHECK");
            leftPrimary.addAll(rightPrimary);
            for (String name : leftPrimary) {
                // 新旧成员只在各自结构里定位，废止字段由移除事实保护。
                Column field = after.named(name);
                if (field != null) {
                    markColumn(source, fact, after, field, "RECHECK", false);
                }
            }
        }
        Set<List<String>> oldForeign = foreignMembers(left.foreignKeys());
        Set<List<String>> newForeign = foreignMembers(right.foreignKeys());
        if (!oldForeign.equals(newForeign)) {
            // 成组的多列目标按实际字段对应比较，忽略约束改名。
            var changed = new HashSet<>(oldForeign);
            changed.addAll(newForeign);
            var same = new HashSet<>(oldForeign);
            same.retainAll(newForeign);
            changed.removeAll(same);
            changed.stream().flatMap(List::stream).map(pair -> pair.substring(0, pair.indexOf('|')))
                    .distinct().map(after::named).filter(Objects::nonNull)
                    .forEach(field -> depends(source, after.id(), field.columnId(), fact.id()));
        }
    }

    /**
     * 以同一键的所有成员映射作为外键身份值，保留复合关系对应。
     */
    private Set<List<String>> foreignMembers(List<Key> keys) {
        return keys.stream().collect(Collectors.groupingBy(Key::name)).values().stream()
                .map(members -> members.stream()
                        .map(key -> key.field() + "|" + key.referencedObject() + "|" + key.referencedField())
                        .sorted().toList()).collect(Collectors.toSet());
    }

    /**
     * 对象级删除立即保护所有列的依赖。
     */
    private void markObject(long source, Entry object, Fact fact, boolean removal) {
        String kind = removal ? "REMOVAL" : "NEW_ITEM";
        var table = entries.ensure(GovernanceTarget.table(source, object.id()), object.generation());
        link(source, table.getId(), fact.id(), null, kind);
        if (removal) {
            // 表级权威控制覆盖本表字段，字段历史仍逐项留有移除办理。
            forbid(table, fact.id(), "OBJECT_REMOVED", "来源对象已删除或重建", null, null);
            depends(source, object.id(), null, fact.id());
        }
        for (var field : object.columns()) {
            markColumn(source, fact, object, field, kind, false);
        }
    }

    /**
     * 为字段差异记录自身及其明确依赖，同时落实需要的禁止。
     */
    private void markColumn(long source, Fact fact, Entry object, Column field, String kind, boolean blocked) {
        var item = entries.ensure(GovernanceTarget.column(source, object.id(), field.columnId()), object.generation());
        link(source, item.getId(), fact.id(), null, kind);
        if (blocked) {
            forbid(item, fact.id(), "REMOVAL".equals(kind) ? "COLUMN_REMOVED" : "INCOMPATIBLE",
                    "来源字段已移除或结构不兼容", null, null);
        }
        if (!"NEW_ITEM".equals(kind)) {
            depends(source, object.id(), field.columnId(), fact.id());
        }
    }

    /**
     * 从实际固定结构依赖定位其他定义。
     */
    private void depends(long source, long object, Long column, long change) {
        for (var item : reads.structuralDependents(source, object, column)) {
            link(source, item.getId(), change, null, "RECHECK");
        }
    }

    /**
     * 每个来源与条目只入队一次，并推进条目并发版本。
     */
    private void link(long source, long itemId, Long change, Long noteChange, String kind) {
        var existing = impacts.selectOne(Wrappers.<GovernanceImpactPO>lambdaQuery()
                .eq(GovernanceImpactPO::getItemId, itemId)
                .eq(change != null, GovernanceImpactPO::getStructureChangeId, change)
                .eq(noteChange != null, GovernanceImpactPO::getNoteChangeId, noteChange));
        if (existing != null) {
            // 同一来源直接移除优先于其普通依赖复核。
            if ("REMOVAL".equals(kind) && !"REMOVAL".equals(existing.getImpactKind())
                    && !"REVIEWED".equals(existing.getState())) {
                impacts.update(Wrappers.<GovernanceImpactPO>lambdaUpdate()
                        .eq(GovernanceImpactPO::getId, existing.getId())
                        .set(GovernanceImpactPO::getImpactKind, kind));
            }
            return;
        }
        var item = reads.item(itemId);
        if (item == null || item.getSourceId() != source) {
            // 拒绝任何跨源的来源写入。
            throw BizIllegalException.missing();
        }
        var task = new GovernanceImpactPO();
        task.setSourceId(source);
        task.setItemId(itemId);
        task.setStructureChangeId(change);
        task.setNoteChangeId(noteChange);
        task.setBaselineRevisionId(item.getLastConfirmedRevisionId());
        task.setImpactKind(kind);
        task.setState("PENDING");
        impacts.insert(task);
        entries.update(item, item.getHeadRevisionId(), item.getLastConfirmedRevisionId());
        if (change != null) {
            // 记录来源变更与实际治理条目的稳定关联。
            catalog.link(change, "GOVERNANCE_ITEM", itemId);
        }
    }

    /**
     * 只使用来源事实携带的前侧或后侧原名称。
     */
    private String fieldName(String raw) {
        JsonNode value = raw == null ? null : json.mapper().readTree(raw);
        return value == null ? "" : value.path("name").asText("");
    }

    /**
     * 区分已明确的类型类别替换、长度收窄与待业务复核。
     */
    private boolean incompatible(Column before, Column after) {
        String from = before.nativeType().toLowerCase(Locale.ROOT);
        String to = after.nativeType().toLowerCase(Locale.ROOT);
        String family = family(from);
        if (!family.isEmpty() && !family(to).isEmpty() && !family.equals(family(to))) {
            // 明确改变原业务值表达类别。
            return true;
        }
        var left = WIDTH.matcher(from);
        var right = WIDTH.matcher(to);
        if (left.matches() && right.matches() && !family.equals("INTEGER")) {
            // 整数显示宽度不等于数值域，文本长度或数值精度则需要比较。
            int scaleLeft = left.group(2) == null ? 0 : Integer.parseInt(left.group(2));
            int scaleRight = right.group(2) == null ? 0 : Integer.parseInt(right.group(2));
            return scaleRight < scaleLeft || Integer.parseInt(right.group(1)) - scaleRight
                    < Integer.parseInt(left.group(1)) - scaleLeft;
        }
        return false;
    }

    /**
     * 按已验证 MySQL 与 PostgreSQL 的已知原生类型识别确定的变化类别。
     */
    private String family(String type) {
        if (type.matches("^(tinyint|smallint|mediumint|bigint|int|integer)([^a-z]|$).*")) {
            return "INTEGER";
        }
        if (type.matches("^(numeric|decimal|float|double|real)([^a-z]|$).*")) {
            return "DECIMAL";
        }
        if (type.matches("^(char|varchar|character|text|tinytext|mediumtext|longtext)([^a-z]|$).*")) {
            return "TEXT";
        }
        if (type.matches("^(time|timestamp|date|datetime)([^a-z]|$).*")) {
            return "TIME";
        }
        return "";
    }

    /**
     * 一个结构来源的全部影响。
     */
    private List<GovernanceImpactPO> byStructure(long change) {
        return impacts.selectList(Wrappers.<GovernanceImpactPO>lambdaQuery()
                .eq(GovernanceImpactPO::getStructureChangeId, change));
    }

    /**
     * 保持来源实际事实和业务阶段各自的写入归属。
     */
    private void refresh(long source, Long change) {
        if (change != null) {
            catalog.stage(source, change, stage(byStructure(change), false));
        }
    }
}
