package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.common.PageQuery;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.datasource.entity.po.CatalogColumnPO;
import com.dataocean.module.datasource.entity.po.CatalogConstraintPO;
import com.dataocean.module.datasource.entity.po.CatalogObjectPO;
import com.dataocean.module.datasource.entity.po.CollectionTaskPO;
import com.dataocean.module.datasource.entity.po.SnapshotObservationPO;
import com.dataocean.module.datasource.entity.po.StructureChangePO;
import com.dataocean.module.datasource.mapper.DatasourcePageMapper.CatalogRow;
import com.dataocean.module.datasource.source.SourceMetadata.Field;
import com.dataocean.module.datasource.source.SourceMetadata.Key;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;
import com.dataocean.module.datasource.source.SourceMetadata.Structure;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 按来源快照保存候选、正式结构修订及变化证据，查询仅披露成功快照。
 */
@Repository
@RequiredArgsConstructor
public class CatalogMapper {

    /**
     * 候选内容及变化证据采用可重读的明确类型。
     */
    private final JsonCodec json;

    /**
     * 执行有业务专属联接条件的分页查询。
     */
    private final DatasourcePageMapper pages;

    /**
     * 查询来源目录版本。
     */
    private final DatasourceRecordMapper sources;

    /**
     * 发布候选快照及其提交时间。
     */
    private final CollectionSnapshotMapper snapshots;

    /**
     * 按来源首次成功时间建立调度计划。
     */
    private final CollectionScheduleMapper schedules;

    /**
     * 保存对象的稳定身份。
     */
    private final CatalogObjectMapper objects;

    /**
     * 写入及核实快照观察。
     */
    private final SnapshotObservationMapper observations;

    /**
     * 登记稳定的字段身份。
     */
    private final CatalogColumnMapper columns;

    /**
     * 保存对象与字段的不可变结构修订。
     */
    private final CatalogRevisionMapper revisions;

    /**
     * 保存单表约束及其复合主键成员。
     */
    private final CatalogConstraintMapper constraintRecords;

    /**
     * 访问变化状态与其首次核实依据。
     */
    private final StructureChangeRecordMapper changeRecords;

    /**
     * 成功快照或候选中的实际观测。
     *
     * @param id 观察编号
     * @param snapshotId 固定的快照编号
     * @param objectId 已核验的对象身份
     * @param revisionId 已核验的结构修订
     * @param identityChangeId 待核实变化编号
     * @param pathKey 限定路径摘要
     * @param definitionDigest 结构摘要
     * @param structure 已读取的结构事实
     * @param collectedAt 成功快照提交时间
     */
    public record Observation(
            long id,
            long snapshotId,
            Long objectId,
            Long revisionId,
            Long identityChangeId,
            byte[] pathKey,
            byte[] definitionDigest,
            Structure structure,
            LocalDateTime collectedAt) {}

    /**
     * 最近可比较的同源成功快照。
     */
    public Long comparable(long source, long candidate) {
        return snapshots.comparable(source, candidate);
    }

    /**
     * 保存实际观察与读取状态，结构未读取时不写入候选正文。
     */
    public void saveCandidate(
            long snapshot,
            ObjectRef object,
            byte[] pathKey,
            byte[] digest,
            Structure structure,
            String errorCode,
            String errorMessage) {
        var row = new SnapshotObservationPO();
        row.setSnapshotId(snapshot);
        row.setObjectPathKey(pathKey);
        row.setNamespace(object.schema());
        row.setSourceName(object.name());
        row.setObjectType(object.type());
        row.setReadStatus(structure == null ? "FAILED" : "COMPLETE");
        row.setObservedIdentity(object.identity());
        row.setDefinitionDigest(digest);
        row.setCandidateStructure(structure == null ? null : json.write(structure));
        row.setFailureCode(errorCode);
        row.setFailureMessage(errorMessage);
        observations.insert(row);
    }

    /**
     * 读取完整的候选或指定成功快照成员。未成功的快照仅允许工作者在内部读取。
     */
    public List<Observation> members(long snapshot) {
        return pages.catalogMembers(snapshot).stream().map(this::observation).toList();
    }

    /**
     * 未决身份使用首次可核实的原观察，避免以后续待核实对象代替已确认身份。
     */
    public Observation identityBasis(long source, long change) {
        var row = changeRecords.selectOne(Wrappers.<StructureChangePO>lambdaQuery()
                .eq(StructureChangePO::getSourceId, source)
                .eq(StructureChangePO::getId, change)
                .select(StructureChangePO::getId, StructureChangePO::getBeforeObservationId));
        if (row == null) {
            // 缺少已关联变化时保留数据访问错误。
            throw new EmptyResultDataAccessException(1);
        }
        Long prior = row.getBeforeObservationId();
        return prior == null ? null : object(source, null, prior, 0, false, true);
    }

    /**
     * 最近的同一观察仍可直接核实；快照成员本身保持不可变。
     */
    public Observation currentIdentityObservation(long source, long snapshot, long change) {
        Long observed = pages.currentIdentityObservation(source, snapshot, change);
        return observed == null ? null : object(source, snapshot, observed, 0, false, true);
    }

    /**
     * 新来源事实接续已有身份疑点，保留原证据和明确后续入口。
     */
    public void supersede(long source, long prior, long successor) {
        changeRecords.update(Wrappers.<StructureChangePO>lambdaUpdate()
                .eq(StructureChangePO::getSourceId, source)
                .eq(StructureChangePO::getId, prior)
                .isNull(StructureChangePO::getSuccessorChangeId)
                .set(StructureChangePO::getSuccessorChangeId, successor)
                .set(StructureChangePO::getStage, "COMPLETED")
                .setIncrBy(StructureChangePO::getVersion, 1)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 曾经可见但目前仍未证实删除的对象依据。
     *
     * @param changeId 原访问丢失记录
     * @param observationId 当时成功读取的观察
     * @param factState 原技术核实状态
     * @param version 当前办理版本
     */
    public record LostAccess(long changeId, long observationId, String factState, int version) {}

    /**
     * 访问丢失后继续保留同一可比较范围的最后确证身份。
     */
    public List<LostAccess> unresolvedAccess(long source, long candidate) {
        return pages.unresolvedAccess(source, candidate);
    }

    /**
     * 成功重读可确证的原对象后保留恢复证据，不改写已作出的人工结论。
     */
    public void restored(long source, LostAccess lost, long snapshot, long observation) {
        changeRecords.update(Wrappers.<StructureChangePO>lambdaUpdate()
                .eq(StructureChangePO::getSourceId, source)
                .eq(StructureChangePO::getId, lost.changeId())
                .set(StructureChangePO::getVisibilityRestoredSnapshotId, snapshot)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
        if ("PENDING".equals(lost.factState())) {
            // 系统只在实际成功读取且身份一致时排除未决的访问异常。
            changeRecords.appendRestore(source, lost.changeId(), lost.version(), observation);
            changeRecords.update(Wrappers.<StructureChangePO>lambdaUpdate()
                    .eq(StructureChangePO::getSourceId, source)
                    .eq(StructureChangePO::getId, lost.changeId())
                    .set(StructureChangePO::getFactState, "EXCLUDED")
                    .set(StructureChangePO::getStage, "COMPLETED")
                    .setIncrBy(StructureChangePO::getVersion, 1)
                    .setSql("updated_at=UTC_TIMESTAMP(3)"));
        }
    }

    /**
     * 为已经确认的新身份或新世代建档。
     */
    public long newObject(long source, Long predecessor, int generation, byte[] identity) {
        var object = new CatalogObjectPO();
        object.setSourceId(source);
        object.setGeneration(generation);
        object.setSourceIdentityKey(identity);
        object.setPredecessorObjectId(predecessor);
        objects.create(object);
        return object.getId();
    }

    /**
     * 写入变动后的不可变对象修订和字段、约束成员。未变化的字段继续复用可证明的同名修订。
     */
    public long newRevision(long object, Observation current, Observation previous) {
        Structure structure = current.structure();
        ObjectRef ref = structure.object();
        var row = CatalogRevisionMapper.ObjectRevision.builder()
                .objectId(object)
                .revision(revisions.latestObjectVersion(object) + 1)
                .namespace(ref.schema())
                .sourceName(ref.name())
                .objectType(ref.type())
                .sourceComment(ref.comment())
                .viewFacts(ref.parent() == null ? null : json.write(ref.parent()))
                .definitionDigest(current.definitionDigest())
                .build();
        revisions.insertObjectRevision(row);
        long revision = Objects.requireNonNull(row.getId(), "数据库未返回对象修订编号");

        Map<String, FormerField> previousFields = new HashMap<>();
        if (previous != null && previous.revisionId() != null) {
            // 将上一版的字段身份与已保存的字段事实关联，用于判断能否复用。
            Map<String, Field> facts = new HashMap<>();
            previous.structure().fields().forEach(field -> facts.put(field.name(), field));
            for (var column : revisions.columns(previous.revisionId())) {
                // 对应同名来源字段及其上一版的稳定身份。
                String name = column.sourceName();
                previousFields.put(
                        name,
                        new FormerField(column.columnId(), column.revisionId(), column.version(), facts.get(name)));
            }
        }

        Map<String, Long> fields = new HashMap<>();
        for (Field field : structure.fields()) {
            // 字段定义变化生成新修订，定义相同则复用已确认的修订。
            FormerField former = previousFields.get(field.name());
            long fieldRevision;
            if (former != null && field.equals(former.field())) {
                // 未改动的字段沿用身份和修订。
                fieldRevision = former.revisionId();
            } else {
                // 首次出现时分配字段身份，之后只递增同一身份的修订。
                long fieldId = former == null ? newColumn(object) : former.columnId();
                var column = CatalogRevisionMapper.ColumnRevision.builder()
                        .columnId(fieldId)
                        .revision(former == null ? 1 : former.version() + 1)
                        .sourceName(field.name())
                        .ordinal(field.ordinal())
                        .nativeType(field.type())
                        .nullable(field.nullable())
                        .defaultExpression(field.defaultValue())
                        .generatedExpression(field.generated() ? "GENERATED" : null)
                        .sourceComment(field.comment())
                        .build();
                revisions.insertColumnRevision(column);
                fieldRevision = Objects.requireNonNull(column.getId(), "数据库未返回字段修订编号");
            }
            fields.put(field.name(), fieldRevision);
            revisions.addColumn(revision, fieldRevision, field.ordinal());
        }
        constraints(revision, "PRIMARY", structure.primaryKeys(), fields);
        constraints(revision, "FOREIGN", structure.foreignKeys(), fields);
        constraints(revision, "INDEX", structure.indexes(), fields);
        return revision;
    }

    /**
     * 字段在上一版中的已核实身份与定义。
     *
     * @param columnId 字段身份
     * @param revisionId 字段定义编号
     * @param version 字段定义序号
     * @param field 源库定义
     */
    private record FormerField(long columnId, long revisionId, int version, Field field) {}

    /**
     * 将复合键与索引按成员顺序保存，源外关系只保存来源路径。
     */
    private void constraints(long revision, String kind, List<Key> keys, Map<String, Long> fields) {
        Map<String, List<Key>> groups = new LinkedHashMap<>();
        for (Key key : keys) {
            // 同名键各成员归入同一结构约束。
            groups.computeIfAbsent(key.name(), ignored -> new ArrayList<>()).add(key);
        }
        for (var group : groups.entrySet()) {
            // 约束目标保存来源可读标识，不扩展查询范围。
            Key first = group.getValue().get(0);
            var constraint = new CatalogConstraintPO();
            constraint.setObjectRevisionId(revision);
            constraint.setKind("INDEX".equals(kind) && first.unique() ? "UNIQUE_INDEX" : kind);
            constraint.setSourceName(group.getKey());
            constraint.setTargetObjectRef(
                    first.referencedObject() == null ? null : json.write(first.referencedObject()));
            constraintRecords.insert(constraint);
            long id = Objects.requireNonNull(constraint.getId(), "数据库未返回目录约束编号");

            for (Key key : group.getValue()) {
                // 以复合主键保存字段修订及原外键目标。
                constraintRecords.addMember(
                        id,
                        fields.get(key.field()),
                        key.ordinal(),
                        key.referencedField() == null ? null : json.write(key.referencedField()));
            }
        }
    }

    /**
     * 候选观察绑定正式结构，待核实的观察只保留变化引用。
     */
    public void bind(long observation, Long object, Long revision, Long identityChange) {
        observations.update(Wrappers.<SnapshotObservationPO>lambdaUpdate()
                .eq(SnapshotObservationPO::getId, observation)
                .set(SnapshotObservationPO::getObjectId, object)
                .set(SnapshotObservationPO::getObjectRevisionId, revision)
                .set(SnapshotObservationPO::getIdentityChangeId, identityChange));
    }

    /**
     * 记录一种结构变化。之前快照和实际比较快照由调用者明确区分。
     */
    public long change(
            CollectionTaskPO task,
            long before,
            long after,
            Observation prior,
            Observation current,
            String type,
            String attribute,
            String targetKind,
            String left,
            String right,
            String evidence,
            byte[] key,
            boolean pending) {
        // 保留原快照和实际观察关系，待核实的对象暂不绑定正式身份。
        var row = new StructureChangePO();
        row.setSourceId(task.getSourceId());
        row.setDiscoveryTaskId(task.getId());
        row.setBeforeSnapshotId(before);
        row.setAfterSnapshotId(after);
        row.setBeforeObservationId(prior == null ? null : prior.id());
        row.setAfterObservationId(current == null ? null : current.id());
        row.setComparisonObjectId(prior == null ? null : prior.objectId());
        row.setObjectId(pending ? null : current != null ? current.objectId() : prior.objectId());
        row.setChangeKey(key);
        row.setChangeType(type);
        row.setTargetKind(targetKind);
        row.setAttributeKind(attribute);
        row.setFactState(pending ? "PENDING" : "CONFIRMED");
        row.setStage(pending ? "PENDING_REVIEW" : "PENDING_GOVERNANCE");
        row.setBeforeValue(left);
        row.setAfterValue(right);
        row.setEvidence(evidence);
        changeRecords.createComparison(row);
        return Objects.requireNonNull(row.getId(), "数据库未返回结构变化编号");
    }

    /**
     * 确认成功快照、切换源指针并刷新数据源更新时间，由同一事务更新任务及调度。
     */
    public int publish(long source, long snapshot, Long previous, Long comparison, String analysis, long changes) {
        snapshots.publish(source, snapshot, previous, comparison, analysis, changes);
        sources.publishSnapshot(source, snapshot);
        schedules.initialize(source);
        return sources.catalogVersion(source);
    }

    /**
     * 按可读 Schema、目录快照和过滤条件读取当前目录页。
     */
    public IPage<Observation> page(
            long source,
            long snapshot,
            long configuration,
            String keyword,
            String type,
            String state,
            PageQuery paging) {
        var page = paging.<CatalogRow>toFixedOrderMpPage();
        page.setCountId("catalogCount");
        var rows = pages.catalogPage(page, source, snapshot, configuration, keyword, type, state);
        return rows.convert(this::observation);
    }

    /**
     * 统计相同目录范围内的可读对象，可独立于当前列表筛选使用。
     */
    public long count(long source, long snapshot, long configuration, String keyword, String type, String state) {
        return pages.catalogCount(source, snapshot, configuration, keyword, type, state);
    }

    /**
     * 当前目录和历史结构均按源、成功状态和实际快照精确读取。
     */
    public Observation object(
            long source, Long snapshot, long object, long configuration, boolean useCurrentScope, boolean observed) {
        var row = pages.catalogObject(source, snapshot, object, configuration, useCurrentScope, observed);
        return row == null ? null : observation(row);
    }

    /**
     * 已核实的稳定对象身份优先，只有待核实观察保留变化引用。
     */
    private Observation observation(CatalogRow row) {
        return new Observation(
                row.id(),
                row.snapshotId(),
                row.effectiveObjectId(),
                row.effectiveRevisionId(),
                row.effectiveObjectId() == null ? row.identityChangeId() : null,
                row.objectPathKey(),
                row.definitionDigest(),
                json.read(row.candidateStructure(), Structure.class),
                row.committedAt());
    }

    /**
     * 为首次出现的字段分配独立身份，后续修订继续沿用此编号。
     */
    private long newColumn(long object) {
        var column = new CatalogColumnPO();
        column.setObjectId(object);
        columns.insert(column);
        return column.getId();
    }
}
