package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.datasource.mapper.CatalogMapper.LostAccess;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 平台库跨表列表的显式投影，由现有分页插件负责分页 SQL。
 */
public interface DatasourcePageMapper {

    /**
     * 概况与详情共用的安全字段及最新成功元数据。
     */
    String SOURCE_SUMMARY =
            """
            SELECT s.id,s.name,s.lifecycle,s.version,r.database_type,u.display_name AS technical_contact,
                (SELECT c.detected_version FROM ds_capability_check c WHERE c.source_id=s.id AND c.read_only_safety='PASS'
                    ORDER BY c.checked_at DESC,c.id DESC LIMIT 1) AS detected_version,
                (SELECT t.state FROM ds_collection_task t WHERE t.source_id=s.id ORDER BY t.created_at DESC,t.id DESC LIMIT 1) AS latest_state,
                (SELECT t.ended_at FROM ds_collection_task t WHERE t.source_id=s.id ORDER BY t.created_at DESC,t.id DESC LIMIT 1) AS latest_end,
                p.committed_at
            FROM ds_source s JOIN ds_connection_revision r ON r.id=s.current_connection_revision_id
            LEFT JOIN iam_user u ON u.id=r.technical_contact_id
            LEFT JOIN ds_snapshot p ON p.id=s.current_snapshot_id
            """;

    /**
     * 源列表与总数用相同的名称、类型、技术联系人及生命周期条件。
     */
    String SOURCE_FILTER =
            """
            WHERE LOCATE(#{name},s.name)>0 AND (#{type}='' OR r.database_type=#{type})
              AND (#{contact}='' OR LOCATE(#{contact},COALESCE(u.display_name,''))>0
                   OR LOCATE(#{contact},COALESCE(u.username,''))>0)
              AND (#{lifecycle}='' OR s.lifecycle=#{lifecycle})
            """;

    /**
     * 目录列表、成员和详情共用的观察投影，已核实身份优先。
     */
    String CATALOG_SELECTION =
            """
            SELECT o.id,o.snapshot_id,COALESCE(r.resolved_object_id,o.object_id) AS effective_object_id,
              COALESCE(r.resolved_object_revision_id,o.object_revision_id) AS effective_revision_id,
              o.identity_change_id,o.object_path_key,o.definition_digest,o.candidate_structure,s.committed_at
            """;

    /**
     * 观察与其快照、已核实身份的共用关联。
     */
    String CATALOG_JOINS =
            """
            FROM ds_snapshot_object o JOIN ds_snapshot s ON s.id=o.snapshot_id
            LEFT JOIN ds_change_review r ON r.change_id=o.identity_change_id AND r.resolved_object_id IS NOT NULL
            """;

    /**
     * 当前 Schema 范围与已核实身份共同限定成功目录。
     */
    String CATALOG_FILTER = CATALOG_JOINS
            + """
            WHERE s.source_id=#{source} AND s.id=#{snapshot} AND s.status='SUCCEEDED'
              AND (LOCATE(#{keyword},o.source_name)>0
                   OR LOCATE(#{keyword},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(o.candidate_structure,'$.object.comment')),''))>0)
              AND (#{type}='' OR o.object_type=#{type})
              AND (#{state}='' OR #{state}=
                   IF(o.identity_change_id IS NOT NULL AND r.resolved_object_id IS NULL,'IDENTITY_UNCERTAIN','COLLECTED'))
              AND (o.namespace='' OR EXISTS
                   (SELECT 1 FROM ds_connection_schema c WHERE c.connection_revision_id=#{configuration}
                    AND BINARY c.schema_name=BINARY o.namespace))
            """;

    /**
     * 限定路径从前后观察提取，变化证据不依赖当前目录名称。
     */
    String CHANGE_PATH =
            "CONCAT_WS('.',NULLIF(COALESCE(b.namespace,a.namespace),''),COALESCE(b.source_name,a.source_name))";

    /**
     * 变化及当前版本关联的人工核实结果。
     */
    String CHANGE_JOINS =
            """
            FROM ds_change c LEFT JOIN ds_snapshot_object a ON a.id=c.before_observation_id
            LEFT JOIN ds_snapshot_object b ON b.id=c.after_observation_id
            LEFT JOIN ds_change_review r ON r.change_id=c.id AND r.reviewed_version=c.version-1
            """;

    /**
     * 仅读取变化回看及写事务核对需要的字段。
     */
    String CHANGE_SELECTION = """
            SELECT c.id,c.source_id,c.discovery_task_id,
            """ + CHANGE_PATH
            + """
              AS target_path,c.change_type,c.attribute_kind,c.discovered_at,
              c.fact_state,c.stage,c.version,c.successor_change_id,c.before_value,c.after_value,c.evidence,
              r.decision,r.actor_id,r.evidence_text,r.reviewed_at,c.before_observation_id,c.after_observation_id,
              c.comparison_object_id,c.after_snapshot_id
            """;

    /**
     * 变化分页和总数共用的事实、对象、任务及发生时间条件。
     */
    String CHANGE_FILTER = "WHERE c.source_id=#{source} AND (LOCATE(#{word}," + CHANGE_PATH
            + """
            )>0 OR LOCATE(#{word},c.attribute_kind)>0
                OR LOCATE(#{word},COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.after_value,'$.name')),''))>0)
              AND (#{type}='' OR c.change_type=#{type}) AND (#{stage}='' OR c.stage=#{stage})
              AND (#{from} IS NULL OR c.discovered_at>=#{from}) AND (#{until} IS NULL OR c.discovered_at<#{until})
              AND (#{object} IS NULL OR COALESCE(c.object_id,c.comparison_object_id)=#{object})
              AND (#{task} IS NULL OR c.discovery_task_id=#{task})
            """;

    /**
     * 读取真实来源概况列表，排序列由调用处白名单设置。
     */
    @Select(SOURCE_SUMMARY + SOURCE_FILTER)
    Page<SourceSummary> sourcePage(
            Page<SourceSummary> page,
            @Param("name") String name,
            @Param("type") String type,
            @Param("contact") String contact,
            @Param("lifecycle") String lifecycle);

    /**
     * 统计符合来源列表条件的记录。
     */
    @Select(
            """
            SELECT COUNT(*) FROM ds_source s JOIN ds_connection_revision r ON r.id=s.current_connection_revision_id
            LEFT JOIN iam_user u ON u.id=r.technical_contact_id
            """
                    + SOURCE_FILTER)
    long sourceCount(
            @Param("name") String name,
            @Param("type") String type,
            @Param("contact") String contact,
            @Param("lifecycle") String lifecycle);

    /**
     * 读取单个来源的同口径概况。
     */
    @Select(SOURCE_SUMMARY + "WHERE s.id=#{source}")
    SourceSummary sourceSummary(@Param("source") long source);

    /**
     * 读取成功目录中的可披露观测，按限定名称和观察编号稳定排序。
     */
    @Select(CATALOG_SELECTION + CATALOG_FILTER + "ORDER BY o.namespace,o.source_name,o.id")
    Page<CatalogRow> catalogPage(
            Page<CatalogRow> page,
            @Param("source") long source,
            @Param("snapshot") long snapshot,
            @Param("configuration") long configuration,
            @Param("keyword") String keyword,
            @Param("type") String type,
            @Param("state") String state);

    /**
     * 统计同样的成功快照和当前可读 Schema 内对象。
     */
    @Select("SELECT COUNT(*) " + CATALOG_FILTER)
    long catalogCount(
            @Param("source") long source,
            @Param("snapshot") long snapshot,
            @Param("configuration") long configuration,
            @Param("keyword") String keyword,
            @Param("type") String type,
            @Param("state") String state);

    /**
     * 内部读取指定快照的完整成员，支持尚处于候选状态的快照。
     */
    @Select(CATALOG_SELECTION + CATALOG_JOINS + "WHERE s.id=#{snapshot} AND o.read_status='COMPLETE' ORDER BY o.id")
    List<CatalogRow> catalogMembers(@Param("snapshot") long snapshot);

    /**
     * 仅在源和成功快照范围内查成员，按调用语义使用观察或稳定对象编号。
     */
    @Select(
            "<script>" + CATALOG_SELECTION + CATALOG_JOINS
                    + """
            WHERE s.source_id=#{source} AND (#{snapshot} IS NULL OR s.id=#{snapshot}) AND s.status='SUCCEEDED'
            <choose>
              <when test="observed">AND o.id=#{object}</when>
              <otherwise>AND COALESCE(r.resolved_object_id,o.object_id)=#{object}</otherwise>
            </choose>
            AND (#{useCurrentScope}=0 OR o.namespace='' OR EXISTS
                (SELECT 1 FROM ds_connection_schema c WHERE c.connection_revision_id=#{configuration}
                  AND BINARY c.schema_name=BINARY o.namespace))
            LIMIT 1
            </script>
            """)
    CatalogRow catalogObject(
            @Param("source") long source,
            @Param("snapshot") Long snapshot,
            @Param("object") long object,
            @Param("configuration") long configuration,
            @Param("useCurrentScope") boolean useCurrentScope,
            @Param("observed") boolean observed);

    /**
     * 只在指定源的成功快照中定位待核实变化的观察编号。
     */
    @Select(
            """
            SELECT o.id FROM ds_snapshot_object o JOIN ds_snapshot s ON s.id=o.snapshot_id
            WHERE s.source_id=#{source} AND s.id=#{snapshot} AND s.status='SUCCEEDED'
                AND o.identity_change_id=#{change} LIMIT 1
            """)
    Long currentIdentityObservation(
            @Param("source") long source, @Param("snapshot") long snapshot, @Param("change") long change);

    /**
     * 读取同一可比较范围仍未证实删除的原访问丢失记录。
     */
    @Select(
            """
            SELECT c.id AS change_id,c.before_observation_id AS observation_id,c.fact_state,c.version FROM ds_change c
            JOIN ds_snapshot p ON p.id=c.before_snapshot_id
            JOIN ds_snapshot n ON n.id=#{candidate}
                AND n.scope_key=p.scope_key AND n.comparison_contract_key=p.comparison_contract_key
            WHERE c.source_id=#{source} AND c.change_type='ACCESS_LOST' AND c.before_observation_id IS NOT NULL
              AND c.visibility_restored_snapshot_id IS NULL AND c.successor_change_id IS NULL
              AND (c.fact_state='PENDING' OR EXISTS
                (SELECT 1 FROM ds_change_review r WHERE r.change_id=c.id AND r.decision='STILL_EXISTS'))
            ORDER BY c.id
            """)
    List<LostAccess> unresolvedAccess(@Param("source") long source, @Param("candidate") long candidate);

    /**
     * 读取结构变化记录，最新发现优先。
     */
    @Select(CHANGE_SELECTION + CHANGE_JOINS + CHANGE_FILTER + "ORDER BY c.discovered_at DESC,c.id DESC")
    Page<ChangeProjection> changePage(
            Page<ChangeProjection> page,
            @Param("source") long source,
            @Param("word") String word,
            @Param("type") String type,
            @Param("stage") String stage,
            @Param("from") LocalDateTime from,
            @Param("until") LocalDateTime until,
            @Param("object") Long object,
            @Param("task") String task);

    /**
     * 对相同变化查询统计总数。
     */
    @Select("SELECT COUNT(*) " + CHANGE_JOINS + CHANGE_FILTER)
    long changeCount(
            @Param("source") long source,
            @Param("word") String word,
            @Param("type") String type,
            @Param("stage") String stage,
            @Param("from") LocalDateTime from,
            @Param("until") LocalDateTime until,
            @Param("object") Long object,
            @Param("task") String task);

    /**
     * 按来源和编号读取已记录变化。
     */
    @Select(CHANGE_SELECTION + CHANGE_JOINS + "WHERE c.source_id=#{source} AND c.id=#{id}")
    ChangeProjection changeDetail(@Param("source") long source, @Param("id") long id);

    /**
     * 在结构核实事务中锁住变化及其查询关联。
     */
    @Select(CHANGE_SELECTION + CHANGE_JOINS + "WHERE c.source_id=#{source} AND c.id=#{id} FOR UPDATE")
    ChangeProjection lockChange(@Param("source") long source, @Param("id") long id);

    /**
     * 来源概要的原始数据库投影，时间列以平台库 UTC 本地时间映射。
     *
     * @param id 数据源编号
     * @param name 数据源展示名
     * @param lifecycle 当前生命周期
     * @param version 当前配置版本
     * @param databaseType 生效配置的数据库类型
     * @param technicalContact 技术联系人显示名
     * @param detectedVersion 最近通过安全检查的数据库版本
     * @param latestState 最近接收的采集任务状态
     * @param latestEnd 最近接收的采集任务结束时间
     * @param committedAt 当前目录成功快照提交时间
     */
    record SourceSummary(
            long id,
            String name,
            String lifecycle,
            int version,
            String databaseType,
            String technicalContact,
            String detectedVersion,
            String latestState,
            LocalDateTime latestEnd,
            LocalDateTime committedAt) {}

    /**
     * 目录观察的原始投影，结构正文在调用方按当前领域模型解码。
     *
     * @param id 观察编号
     * @param snapshotId 观察所属快照
     * @param effectiveObjectId 核实后实际有效的对象身份
     * @param effectiveRevisionId 核实后实际有效的结构修订
     * @param identityChangeId 待核实的身份变化
     * @param objectPathKey 同一快照内的限定路径摘要
     * @param definitionDigest 实际结构摘要
     * @param candidateStructure 原始结构的 JSON 正文
     * @param committedAt 成功快照提交时间
     */
    record CatalogRow(
            long id,
            long snapshotId,
            Long effectiveObjectId,
            Long effectiveRevisionId,
            Long identityChangeId,
            byte[] objectPathKey,
            byte[] definitionDigest,
            String candidateStructure,
            LocalDateTime committedAt) {}

    /**
     * 变化、原始观察及人工核实快照的联合投影。
     *
     * @param id 变化记录编号
     * @param sourceId 变化所属数据源
     * @param discoveryTaskId 发现此变化的采集任务
     * @param targetPath 前后观察中的实际限定路径
     * @param changeType 变化类别
     * @param attributeKind 具体的结构属性
     * @param discoveredAt 数据库发现时间
     * @param factState 变化事实的确认状态
     * @param stage 当前处理阶段
     * @param version 当前变化修订
     * @param successorChangeId 后续取代本记录的变化编号
     * @param beforeValue 变更前 JSON 值
     * @param afterValue 变更后 JSON 值
     * @param evidence 留存的结构证据
     * @param decision 对当前变化版本最近一次核实的决定
     * @param actorId 最近核实的操作者编号
     * @param evidenceText 人工核实的依据说明
     * @param reviewedAt 核实提交时间
     * @param beforeObservationId 变化前的实际观察
     * @param afterObservationId 变化后的实际观察
     * @param comparisonObjectId 用于对比的原稳定对象
     * @param afterSnapshotId 发现变化的成功快照
     */
    record ChangeProjection(
            long id,
            long sourceId,
            String discoveryTaskId,
            String targetPath,
            String changeType,
            String attributeKind,
            LocalDateTime discoveredAt,
            String factState,
            String stage,
            int version,
            Long successorChangeId,
            String beforeValue,
            String afterValue,
            String evidence,
            String decision,
            String actorId,
            String evidenceText,
            LocalDateTime reviewedAt,
            Long beforeObservationId,
            Long afterObservationId,
            Long comparisonObjectId,
            long afterSnapshotId) {}
}
