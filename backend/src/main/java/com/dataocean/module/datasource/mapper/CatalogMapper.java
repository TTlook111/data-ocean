package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.common.PageQuery;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.datasource.entity.po.CatalogColumnPO;
import com.dataocean.module.datasource.entity.po.CatalogObjectPO;
import com.dataocean.module.datasource.entity.po.CollectionTaskPO;
import com.dataocean.module.datasource.entity.po.SnapshotObservationPO;
import com.dataocean.module.datasource.entity.po.StructureChangePO;
import com.dataocean.module.datasource.mapper.DatasourcePageMapper.CatalogRow;
import com.dataocean.module.datasource.source.SourceMetadata.Field;
import com.dataocean.module.datasource.source.SourceMetadata.Key;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;
import com.dataocean.module.datasource.source.SourceMetadata.Structure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 按来源快照保存候选、正式结构修订及变化证据，查询仅披露成功快照。
 */
@Repository
@RequiredArgsConstructor
public class CatalogMapper {

    /**
     * 平台库事务连接。
     */
    private final JdbcTemplate sql;

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
        return sql
                .query(
                        """
                SELECT previous.id FROM ds_snapshot previous JOIN ds_snapshot newer
                  ON newer.scope_key=previous.scope_key AND newer.comparison_contract_key=previous.comparison_contract_key
                WHERE previous.source_id=? AND newer.id=? AND previous.status='SUCCEEDED'
                ORDER BY previous.committed_at DESC,previous.id DESC LIMIT 1
                """,
                        (row, index) -> row.getLong(1),
                        source,
                        candidate)
                .stream()
                .findFirst()
                .orElse(null);
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
        return sql.query(
                """
                SELECT o.*,s.committed_at,COALESCE(r.resolved_object_id,o.object_id) AS effective_object_id,
                    COALESCE(r.resolved_object_revision_id,o.object_revision_id) AS effective_revision_id
                FROM ds_snapshot_object o JOIN ds_snapshot s ON s.id=o.snapshot_id
                LEFT JOIN ds_change_review r ON r.change_id=o.identity_change_id AND r.resolved_object_id IS NOT NULL
                WHERE s.id=? AND o.read_status='COMPLETE' ORDER BY o.id
                """,
                (row, index) -> observation(row),
                snapshot);
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
        Long observed = sql
                .query(
                        """
                SELECT o.id FROM ds_snapshot_object o JOIN ds_snapshot s ON s.id=o.snapshot_id
                WHERE s.source_id=? AND s.id=? AND s.status='SUCCEEDED' AND o.identity_change_id=?
                """,
                        (row, index) -> row.getLong(1),
                        source,
                        snapshot,
                        change)
                .stream()
                .findFirst()
                .orElse(null);
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
        return sql.query(
                """
                SELECT c.id,c.before_observation_id,c.fact_state,c.version FROM ds_change c
                JOIN ds_snapshot p ON p.id=c.before_snapshot_id
                JOIN ds_snapshot n ON n.id=? AND n.scope_key=p.scope_key AND n.comparison_contract_key=p.comparison_contract_key
                WHERE c.source_id=? AND c.change_type='ACCESS_LOST' AND c.before_observation_id IS NOT NULL
                  AND c.visibility_restored_snapshot_id IS NULL AND c.successor_change_id IS NULL
                  AND (c.fact_state='PENDING' OR EXISTS
                    (SELECT 1 FROM ds_change_review r WHERE r.change_id=c.id AND r.decision='STILL_EXISTS'))
                ORDER BY c.id
                """,
                (row, index) -> new LostAccess(row.getLong(1), row.getLong(2), row.getString(3), row.getInt(4)),
                candidate,
                source);
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
            sql.update(
                    """
                    INSERT INTO ds_change_review (source_id,change_id,actor_kind,reviewed_version,decision,evidence_text,
                        verified_observation_id,reviewed_at)
                    VALUES (?,?,'SYSTEM',?,'READ_RESTORED','成功读取已确认的同一来源对象',?,UTC_TIMESTAMP(3))
                    """,
                    source,
                    lost.changeId(),
                    lost.version(),
                    observation);
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
        int previousVersion = Objects.requireNonNull(sql.queryForObject(
                "SELECT COALESCE(MAX(revision),0) FROM ds_object_revision WHERE object_id=?", Integer.class, object));
        long revision = insert(
                """
                INSERT INTO ds_object_revision (object_id,revision,namespace,source_name,object_type,source_comment,
                    view_facts,definition_digest,created_at)
                VALUES (?,?,?,?,?,?,?, ?,UTC_TIMESTAMP(3))
                """,
                object,
                previousVersion + 1,
                ref.schema(),
                ref.name(),
                ref.type(),
                ref.comment(),
                ref.parent() == null ? null : json.write(ref.parent()),
                current.definitionDigest());
        Map<String, FormerField> previousFields = new HashMap<>();
        if (previous != null && previous.revisionId() != null) {
            // 当前对象上一个正式修订的同名字段可继续使用。
            Map<String, Field> facts = new HashMap<>();
            previous.structure().fields().forEach(field -> facts.put(field.name(), field));
            sql.query(
                    """
                    SELECT c.source_name,c.column_id,c.id,c.revision FROM ds_object_column f
                    JOIN ds_column_revision c ON c.id=f.column_revision_id WHERE f.object_revision_id=?
                    """,
                    row -> {
                        // 将上一版的事实与相同字段的持久化身份对应。
                        String name = row.getString(1);
                        previousFields.put(
                                name, new FormerField(row.getLong(2), row.getLong(3), row.getInt(4), facts.get(name)));
                    },
                    previous.revisionId());
        }
        Map<String, Long> fields = new HashMap<>();
        for (Field field : structure.fields()) {
            // 字段定义变化生成新修订；字段未变时沿用原结构事实。
            FormerField former = previousFields.get(field.name());
            long fieldRevision;
            if (former != null && field.equals(former.field())) {
                fieldRevision = former.revisionId();
            } else {
                long fieldId = former == null ? newColumn(object) : former.columnId();
                fieldRevision = insert(
                        """
                        INSERT INTO ds_column_revision (column_id,revision,source_name,ordinal,native_type,nullable,
                            default_expression,generated_expression,source_comment,created_at)
                        VALUES (?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))
                        """,
                        fieldId,
                        former == null ? 1 : former.version() + 1,
                        field.name(),
                        field.ordinal(),
                        field.type(),
                        field.nullable(),
                        field.defaultValue(),
                        field.generated() ? "GENERATED" : null,
                        field.comment());
            }
            fields.put(field.name(), fieldRevision);
            sql.update(
                    "INSERT INTO ds_object_column (object_revision_id,column_revision_id,ordinal) VALUES (?,?,?)",
                    revision,
                    fieldRevision,
                    field.ordinal());
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
            groups.computeIfAbsent(key.name(), ignored -> new java.util.ArrayList<>())
                    .add(key);
        }
        for (var group : groups.entrySet()) {
            // 约束目标保存来源可读标识，不扩展查询范围。
            Key first = group.getValue().get(0);
            long id = insert(
                    """
                    INSERT INTO ds_constraint (object_revision_id,kind,source_name,target_object_ref) VALUES (?,?,?,?)
                    """,
                    revision,
                    "INDEX".equals(kind) && first.unique() ? "UNIQUE_INDEX" : kind,
                    group.getKey(),
                    first.referencedObject() == null ? null : json.write(first.referencedObject()));
            for (Key key : group.getValue()) {
                // 连接字段修订及原外键目标。
                sql.update(
                        """
                        INSERT INTO ds_constraint_member (constraint_id,column_revision_id,ordinal,target_column_ref)
                        VALUES (?,?,?,?)
                        """,
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
        return insert(
                """
                INSERT INTO ds_change (source_id,discovery_task_id,before_snapshot_id,after_snapshot_id,
                    before_observation_id,after_observation_id,comparison_object_id,object_id,change_key,discovery_kind,
                    change_type,target_kind,attribute_kind,fact_state,stage,before_value,after_value,evidence,discovered_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,'COMPARISON',?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                """,
                task.getSourceId(),
                task.getId(),
                before,
                after,
                prior == null ? null : prior.id(),
                current == null ? null : current.id(),
                prior == null ? null : prior.objectId(),
                pending ? null : current != null ? current.objectId() : prior.objectId(),
                key,
                type,
                targetKind,
                attribute,
                pending ? "PENDING" : "CONFIRMED",
                pending ? "PENDING_REVIEW" : "PENDING_GOVERNANCE",
                left,
                right,
                evidence);
    }

    /**
     * 确认成功快照、切换源指针并刷新数据源更新时间，由同一事务更新任务及调度。
     */
    public int publish(long source, long snapshot, Long previous, Long comparison, String analysis, long changes) {
        sql.update(
                """
                UPDATE ds_snapshot SET status='SUCCEEDED',analysis=?,new_change_count=?,
                    previous_success_snapshot_id=?,comparison_snapshot_id=?,read_ended_at=UTC_TIMESTAMP(3),
                    committed_at=UTC_TIMESTAMP(3) WHERE id=? AND source_id=? AND status='CANDIDATE'
                """,
                analysis,
                changes,
                previous,
                comparison,
                snapshot,
                source);
        sql.update(
                """
                UPDATE ds_source SET current_snapshot_id=?,catalog_version=catalog_version+1,active_task_id=NULL,
                    first_success_at=COALESCE(first_success_at,UTC_TIMESTAMP(3)),stale_since=NULL,stale_scope_key=NULL,
                    updated_at=UTC_TIMESTAMP(3) WHERE id=?
                """,
                snapshot,
                source);
        sql.update(
                """
                INSERT IGNORE INTO ds_collection_schedule (source_id,revision,anchor_at,last_admitted_cycle_no,updated_at)
                SELECT id,1,first_success_at,0,UTC_TIMESTAMP(3) FROM ds_source WHERE id=?
                """,
                source);
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
        long total = pages.catalogCount(source, snapshot, configuration, keyword, type, state);
        var page = paging.<CatalogRow>toLastAvailableMpPage(total);
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
        String column = observed ? "o.id" : "COALESCE(r.resolved_object_id,o.object_id)";
        return sql
                .query(
                        """
                SELECT o.*,s.committed_at,COALESCE(r.resolved_object_id,o.object_id) AS effective_object_id,
                    COALESCE(r.resolved_object_revision_id,o.object_revision_id) AS effective_revision_id
                FROM ds_snapshot_object o JOIN ds_snapshot s ON s.id=o.snapshot_id
                LEFT JOIN ds_change_review r ON r.change_id=o.identity_change_id AND r.resolved_object_id IS NOT NULL
                WHERE s.source_id=? AND (? IS NULL OR s.id=?) AND s.status='SUCCEEDED' AND %s=?
                    AND (?=0 OR o.namespace='' OR EXISTS
                      (SELECT 1 FROM ds_connection_schema c WHERE c.connection_revision_id=? AND BINARY c.schema_name=BINARY o.namespace))
                """
                                .formatted(column),
                        (row, index) -> observation(row),
                        source,
                        snapshot,
                        snapshot,
                        object,
                        useCurrentScope ? 1 : 0,
                        configuration)
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * 汇合手工查询与 MyBatis 分页的目录观测投影，采用同一结构解码规则。
     */
    private Observation observation(ResultSet row) throws SQLException {
        return observation(new CatalogRow(
                row.getLong("id"),
                row.getLong("snapshot_id"),
                row.getObject("effective_object_id", Long.class),
                row.getObject("effective_revision_id", Long.class),
                row.getObject("identity_change_id", Long.class),
                row.getBytes("object_path_key"),
                row.getBytes("definition_digest"),
                row.getString("candidate_structure"),
                row.getObject("committed_at", LocalDateTime.class)));
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

    /**
     * 读取数据库生成的主键，所有值由 JDBC 参数绑定。
     */
    private long insert(String statement, Object... values) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        sql.update(
                connection -> {
                    // 禁止将元数据字段或目录内容拼入平台库 SQL。
                    var prepared = connection.prepareStatement(statement, Statement.RETURN_GENERATED_KEYS);
                    for (int index = 0; index < values.length; index++) {
                        prepared.setObject(index + 1, values[index]);
                    }
                    return prepared;
                },
                holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }
}
