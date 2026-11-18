package com.dataocean.module.datasource.service;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.SecretKeyProvider;
import com.dataocean.module.datasource.entity.po.CollectionTaskPO;
import com.dataocean.module.datasource.entity.po.DatasourcePO;
import com.dataocean.module.datasource.mapper.CatalogMapper;
import com.dataocean.module.datasource.mapper.CatalogMapper.LostAccess;
import com.dataocean.module.datasource.mapper.CatalogMapper.Observation;
import com.dataocean.module.datasource.mapper.CollectionMapper;
import com.dataocean.module.datasource.source.SourceMetadata.Field;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;
import com.dataocean.module.datasource.source.SourceMetadata.Structure;

import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 在同源写事务内比较可靠结构，并发布唯一对外目录指针。
 */
@Component
@RequiredArgsConstructor
public class CatalogPublication {

    /**
     * 候选结构、身份、修订及变化证据的持久化边界。
     */
    private final CatalogMapper catalog;

    /**
     * 任务成功与目录切换使用同一事务。
     */
    private final CollectionMapper tasks;

    /**
     * 只在目录真正提交时写可靠通知。
     */
    private final DatasourceEvents events;

    /**
     * 生成固定的对象路径和定义摘要。
     */
    private final SecretKeyProvider signatures;

    /**
     * 类型化结构序列化及差异依据。
     */
    private final JsonCodec json;

    /**
     * 取得按源路径及类别组成的固定比较键。
     */
    public byte[] path(ObjectRef ref) {
        return signatures.hmac(
                "catalog:path|" + json.write(List.of(ref.catalog(), ref.schema(), ref.name(), ref.type())));
    }

    /**
     * 为完整读取的候选固定结构摘要。
     */
    public byte[] definition(Structure structure) {
        return signatures.hmac("catalog:definition|" + json.write(structure));
    }

    /**
     * 成功比较、提交候选、创建计划、切换目录并写通知。
     */
    public void publish(DatasourcePO source, CollectionTaskPO task) {
        long candidate = tasks.snapshot(task.getId()).getId();
        Long comparison = catalog.comparable(source.getId(), candidate);
        List<Observation> current = catalog.members(candidate);
        List<Observation> previous = comparison == null ? List.of() : catalog.members(comparison);
        Map<String, Observation> byPath = new HashMap<>();
        Map<String, Observation> byIdentity = new HashMap<>();
        for (Observation prior : previous) {
            // 前一快照的限定路径和可信源身份用于识别改名及同名重建。
            byPath.put(HexFormat.of().formatHex(prior.pathKey()), prior);
            String identity = prior.structure().object().identity();
            if (identity != null && prior.objectId() != null) {
                byIdentity.put(identity, prior);
            }
        }

        Map<Long, LostAccess> missing = new HashMap<>();
        if (comparison != null) {
            for (LostAccess item : catalog.unresolvedAccess(source.getId(), candidate)) {
                // 旧可见性异常使用最后确认的原观察，连续读取不到不会制造新移除项。
                Observation prior = catalog.object(source.getId(), null, item.observationId(), 0, false, true);
                if (prior != null) {
                    byPath.putIfAbsent(HexFormat.of().formatHex(prior.pathKey()), prior);
                    missing.put(prior.id(), item);
                }
            }
        }

        Set<Long> matched = new HashSet<>();
        long changes = 0;
        for (Observation next : current) {
            // 先按完整限定路径定位，再用可核实的源侧标识判断改名或同名变化。
            ObjectRef ref = next.structure().object();
            Observation prior = byPath.get(HexFormat.of().formatHex(next.pathKey()));
            Observation original = ref.identity() == null ? null : byIdentity.get(ref.identity());
            boolean uncertainty = prior != null
                    && (prior.objectId() == null
                            || (missing.containsKey(prior.id()) && ref.identity() == null)
                            || (ref.identity() != null
                                    && !ref.identity()
                                            .equals(prior.structure().object().identity())));
            if (uncertainty) {
                matched.add(prior.id());
                Long pending = prior.identityChangeId();
                if (pending != null && signatures.matches(prior.definitionDigest(), next.definitionDigest())) {
                    // 证据未变化，复用原待核实事项并继续显示实际的新快照观察。
                    catalog.bind(next.id(), null, null, pending);
                    continue;
                }
                Observation basis = pending == null ? prior : catalog.identityBasis(source.getId(), pending);
                long review = changed(
                        task,
                        basis.snapshotId(),
                        candidate,
                        basis,
                        next,
                        "IDENTITY_UNCERTAIN",
                        "IDENTITY",
                        "OBJECT",
                        basis.structure().object().identity(),
                        ref.identity(),
                        true);
                catalog.bind(next.id(), null, null, review);
                if (pending != null) {
                    catalog.supersede(source.getId(), pending, review);
                }
                changes++;
                continue;
            }
            if (prior == null && original != null) {
                prior = original;
                changes += changedOnce(
                        task,
                        comparison,
                        candidate,
                        prior,
                        next,
                        "OBJECT_RENAMED",
                        "NAME",
                        "OBJECT",
                        prior.structure().object().name(),
                        ref.name(),
                        false);
            }

            long object = prior == null
                    ? catalog.newObject(
                            source.getId(),
                            null,
                            1,
                            ref.identity() == null ? null : signatures.hmac("catalog:source|" + ref.identity()))
                    : prior.objectId();
            boolean sameDefinition =
                    prior != null && signatures.matches(prior.definitionDigest(), next.definitionDigest());
            long revision = sameDefinition ? prior.revisionId() : catalog.newRevision(object, next, prior);
            catalog.bind(next.id(), object, revision, null);
            if (prior != null) {
                // 真实已读取的定义才可用于计算变化，未验证消失不能标作删除。
                matched.add(prior.id());
                LostAccess lost = missing.get(prior.id());
                if (lost != null) {
                    catalog.restored(source.getId(), lost, candidate, next.id());
                }
                if (!sameDefinition) {
                    changes += describe(task, comparison, candidate, prior, next);
                }
            } else if (comparison != null) {
                changes += changedOnce(
                        task,
                        comparison,
                        candidate,
                        null,
                        next,
                        "OBJECT_ADDED",
                        "EXISTENCE",
                        "OBJECT",
                        null,
                        ref.name(),
                        false);
            }
        }

        for (Observation prior : previous) {
            // 目录消失只形成待核实的访问丢失事实。
            if (!matched.contains(prior.id()) && prior.objectId() != null) {
                changes += changedOnce(
                        task,
                        comparison,
                        candidate,
                        prior,
                        null,
                        "ACCESS_LOST",
                        "EXISTENCE",
                        "OBJECT",
                        prior.structure().object().name(),
                        null,
                        true);
            }
        }

        String analysis = comparison != null
                ? changes == 0 ? "NO_CHANGE" : "CHANGES_FOUND"
                : source.getCurrentSnapshotId() == null ? "INITIAL_BASELINE" : "NEW_SCOPE_BASELINE";
        int catalogVersion = catalog.publish(
                source.getId(), candidate, source.getCurrentSnapshotId(), comparison, analysis, changes);
        if (!tasks.finishSuccess(task)) {
            // 任务状态没有同步提交时回滚目录切换和候选发布。
            throw BizIllegalException.conflict();
        }
        events.catalog(source.getId(), candidate, catalogVersion);
    }

    /**
     * 按对象和字段真实属性分别记录差异，缺少声明的空结果保持其原义。
     */
    private long describe(CollectionTaskPO task, long comparison, long candidate, Observation prior, Observation next) {
        Structure before = prior.structure();
        Structure after = next.structure();
        long count = changeValue(
                task,
                comparison,
                candidate,
                prior,
                next,
                "COMMENT",
                "OBJECT",
                before.object().comment(),
                after.object().comment());
        count += changeValue(
                task,
                comparison,
                candidate,
                prior,
                next,
                "PARENT",
                "OBJECT",
                before.object().parent(),
                after.object().parent());
        count += changeValue(
                task,
                comparison,
                candidate,
                prior,
                next,
                "CONSTRAINT",
                "CONSTRAINT",
                List.of(before.primaryKeys(), before.foreignKeys(), before.indexes()),
                List.of(after.primaryKeys(), after.foreignKeys(), after.indexes()));
        Map<String, Field> fields = new HashMap<>();
        before.fields().forEach(field -> fields.put(field.name(), field));
        for (Field field : after.fields()) {
            // 名称及顺序来自源端字段定义，属性改变与新增分别记录。
            Field prev = fields.remove(field.name());
            if (prev == null) {
                count += changedOnce(
                        task,
                        comparison,
                        candidate,
                        prior,
                        next,
                        "COLUMN_ADDED",
                        "EXISTENCE",
                        "COLUMN",
                        null,
                        field,
                        false);
            } else {
                count += fieldDifference(task, comparison, candidate, prior, next, prev, field);
            }
        }
        for (Field removed : fields.values()) {
            // 对象身份可靠且字段读取完整时，缺失字段可作为来源结构移除。
            count += changedOnce(
                    task,
                    comparison,
                    candidate,
                    prior,
                    next,
                    "COLUMN_REMOVED",
                    "EXISTENCE",
                    "COLUMN",
                    removed,
                    null,
                    false);
        }
        return count;
    }

    /**
     * 依据字段完整前后信息计算每个改变的属性。
     */
    private long fieldDifference(
            CollectionTaskPO task,
            long comparison,
            long candidate,
            Observation prior,
            Observation next,
            Field before,
            Field after) {
        long count = namedChange(
                task, comparison, candidate, prior, next, "TYPE", before, after, before.type(), after.type());
        count += namedChange(
                task,
                comparison,
                candidate,
                prior,
                next,
                "NULLABILITY",
                before,
                after,
                before.nullable(),
                after.nullable());
        count += namedChange(
                task,
                comparison,
                candidate,
                prior,
                next,
                "DEFAULT",
                before,
                after,
                before.defaultValue(),
                after.defaultValue());
        count += namedChange(
                task, comparison, candidate, prior, next, "COMMENT", before, after, before.comment(), after.comment());
        count += namedChange(
                task, comparison, candidate, prior, next, "ORDINAL", before, after, before.ordinal(), after.ordinal());
        count += namedChange(
                task,
                comparison,
                candidate,
                prior,
                next,
                "GENERATED",
                before,
                after,
                before.generated(),
                after.generated());
        return count;
    }

    /**
     * 同一属性在不同字段变化时以字段名称区分。
     */
    private long namedChange(
            CollectionTaskPO task,
            long comparison,
            long candidate,
            Observation prior,
            Observation next,
            String attr,
            Field before,
            Field after,
            Object oldValue,
            Object newValue) {
        return Objects.equals(oldValue, newValue)
                ? 0
                : changedOnce(
                        task,
                        comparison,
                        candidate,
                        prior,
                        next,
                        "ATTRIBUTE_CHANGED",
                        attr,
                        "COLUMN",
                        new FieldValue(before.name(), oldValue),
                        new FieldValue(after.name(), newValue),
                        false);
    }

    /**
     * 记录独立的对象级属性变化。
     */
    private long changeValue(
            CollectionTaskPO task,
            long comparison,
            long candidate,
            Observation prior,
            Observation next,
            String attribute,
            String kind,
            Object before,
            Object after) {
        return Objects.equals(before, after)
                ? 0
                : changedOnce(
                        task,
                        comparison,
                        candidate,
                        prior,
                        next,
                        "ATTRIBUTE_CHANGED",
                        attribute,
                        kind,
                        before,
                        after,
                        false);
    }

    /**
     * 写入一条事实，供外层累加当前任务的真实变化条数。
     */
    private long changedOnce(
            CollectionTaskPO task,
            long comparison,
            long candidate,
            Observation prior,
            Observation next,
            String type,
            String attribute,
            String kind,
            Object before,
            Object after,
            boolean needsReview) {
        changed(task, comparison, candidate, prior, next, type, attribute, kind, before, after, needsReview);
        return 1;
    }

    /**
     * 变化依据按本次实际读取的前后观察绑定，禁止改取对象最新版。
     */
    private long changed(
            CollectionTaskPO task,
            long comparison,
            long candidate,
            Observation prior,
            Observation next,
            String type,
            String attribute,
            String kind,
            Object before,
            Object after,
            boolean needsReview) {
        String left = before == null ? null : json.write(before);
        String right = after == null ? null : json.write(after);
        var evidence = new ChangeEvidence(
                prior == null ? null : Long.toString(prior.id()),
                next == null ? null : Long.toString(next.id()),
                prior == null ? null : prior.structure().object(),
                next == null ? null : next.structure().object());
        String details = json.write(evidence);
        byte[] key =
                signatures.hmac("catalog:change|" + json.write(new ChangeKey(type, attribute, details, left, right)));
        return catalog.change(
                task,
                comparison,
                candidate,
                prior,
                next,
                type,
                attribute,
                kind,
                left,
                right,
                details,
                key,
                needsReview);
    }

    /**
     * 一项字段属性，绑定真实字段名。
     *
     * @param name 字段名
     * @param value 实际类型值
     */
    private record FieldValue(String name, Object value) {}

    /**
     * 变化两侧的源端证据，历史回看沿用实际观察。
     *
     * @param beforeId 原观察
     * @param afterId 新观察
     * @param before 原对象身份
     * @param after 新对象身份
     */
    private record ChangeEvidence(String beforeId, String afterId, ObjectRef before, ObjectRef after) {}

    /**
     * 稳定差异去重依据。
     *
     * @param type 变化类别
     * @param attribute 属性名
     * @param evidence 实际观察依据
     * @param before 原值
     * @param after 新值
     */
    private record ChangeKey(String type, String attribute, String evidence, String before, String after) {}
}
