package com.dataocean.module.governance.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.datasource.mapper.DatasourcePageMapper;
import com.dataocean.module.governance.entity.po.GovernanceEvidencePO;
import com.dataocean.module.governance.entity.po.GovernanceItemPO;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 将 B 批成功目录和治理事实合并为工作台的一致分页投影。
 */
public interface GovernanceReadMapper {

    /**
     * 与 B 批复用成功观察、核实身份和当前 Schema 条件。历史移除从来源入口查看。
     */
    String TABLES =
            """
            WITH directory AS (
                SELECT o.id AS observation_id,o.snapshot_id,o.namespace,o.source_name AS name,o.object_type,
                       NULLIF(JSON_UNQUOTE(JSON_EXTRACT(o.candidate_structure,'$.object.comment')),'null') AS source_comment,
                       COALESCE(r.resolved_object_id,o.object_id) AS object_id,
                       COALESCE(r.resolved_object_revision_id,o.object_revision_id) AS revision_id
            """
                    + DatasourcePageMapper.CATALOG_JOINS
                    + """
                WHERE s.source_id=#{source} AND s.id=#{snapshot} AND s.status='SUCCEEDED'
                  AND o.read_status='COMPLETE'
                  AND (o.namespace='' OR EXISTS (SELECT 1 FROM ds_connection_schema scope
                         WHERE scope.connection_revision_id=#{configuration} AND BINARY scope.schema_name=BINARY o.namespace))
            ), scored AS (
                SELECT d.*,x.generation,oi.id AS table_item_id,
                    v.content,
                    IF(d.object_id IS NULL,0,1+(SELECT COUNT(*) FROM ds_object_column c WHERE c.object_revision_id=d.revision_id))
                        AS total_count,
                    (SELECT COUNT(*) FROM gov_item g
                       WHERE g.source_id=#{source} AND g.object_id=d.object_id AND g.head_revision_id=g.last_confirmed_revision_id
                         AND NOT EXISTS (SELECT 1 FROM gov_impact_task p WHERE p.item_id=g.id AND p.state!='REVIEWED')
                         AND NOT EXISTS (SELECT 1 FROM gov_gap q WHERE q.item_id=g.id AND q.state='OPEN')
                         AND (g.kind='TABLE' OR EXISTS (SELECT 1 FROM ds_object_column c
                              JOIN ds_column_revision cr ON cr.id=c.column_revision_id
                              WHERE c.object_revision_id=d.revision_id AND cr.column_id=g.target_id))) AS confirmed_count,
                    EXISTS (SELECT 1 FROM gov_item g WHERE g.source_id=#{source} AND g.object_id=d.object_id
                               AND g.head_revision_id IS NOT NULL) AS started,
                    EXISTS (SELECT 1 FROM gov_item g JOIN gov_impact_task p ON p.item_id=g.id
                               WHERE g.source_id=#{source} AND g.object_id=d.object_id AND p.state!='REVIEWED') AS has_pending_impact,
                    EXISTS (SELECT 1 FROM ds_change c WHERE c.source_id=#{source} AND c.fact_state='PENDING'
                        AND c.successor_change_id IS NULL AND (c.object_id=d.object_id OR c.comparison_object_id=d.object_id)) AS source_pending,
                    (oi.head_revision_id IS NOT NULL AND oi.head_revision_id=oi.last_confirmed_revision_id
                        AND NOT EXISTS (SELECT 1 FROM gov_impact_task p WHERE p.item_id=oi.id AND p.state!='REVIEWED')
                        AND NOT EXISTS (SELECT 1 FROM gov_gap q WHERE q.item_id=oi.id AND q.state='OPEN')) AS table_confirmed
                FROM directory d LEFT JOIN ds_object x ON x.id=d.object_id
                LEFT JOIN gov_item oi ON oi.source_id=#{source} AND oi.kind='TABLE' AND oi.target_id=d.object_id
                LEFT JOIN gov_item_revision v ON v.id=IF(#{drafts},oi.head_revision_id,oi.last_confirmed_revision_id)
            ), labeled AS (
                SELECT scored.*,
                    NULLIF(JSON_UNQUOTE(JSON_EXTRACT(content,'$.businessName')),'null') AS business_name,
                    NULLIF(JSON_UNQUOTE(JSON_EXTRACT(content,'$.purpose')),'null') AS purpose,
                    IF(total_count>0 AND confirmed_count=total_count,'COMPLETE',
                        IF(started OR confirmed_count>0,'IN_PROGRESS','UNSTARTED')) AS progress
                FROM scored
            )
            SELECT observation_id,object_id,generation,namespace,name,object_type,source_comment,business_name,purpose,
                total_count,confirmed_count,table_confirmed,has_pending_impact,source_pending,progress
            FROM labeled
            WHERE (#{word}='' OR LOCATE(LOWER(#{word}),LOWER(name))>0
                 OR LOCATE(LOWER(#{word}),LOWER(COALESCE(source_comment,'')))>0
                 OR LOCATE(LOWER(#{word}),LOWER(COALESCE(business_name,'')))>0)
                AND (#{progress}='' OR progress=#{progress})
                AND (#{pendingImpact} IS NULL OR has_pending_impact=#{pendingImpact})
                AND (#{pendingKind}='' OR (#{pendingKind}='TABLE' AND total_count>0 AND NOT table_confirmed)
                     OR (#{pendingKind}='COLUMN' AND total_count>0
                          AND total_count-confirmed_count > IF(table_confirmed,0,1)))
            """;

    /**
     * 在数据库按一致条件筛选和分页，排序只接收服务端参数。
     */
    @Select(TABLES + "ORDER BY IF(#{ascending},namespace,NULL),IF(#{ascending},name,NULL),"
            + "IF(#{ascending},NULL,namespace) DESC,IF(#{ascending},NULL,name) DESC,observation_id")
    Page<Table> tables(
            Page<Table> page,
            @Param("source") long source,
            @Param("snapshot") long snapshot,
            @Param("configuration") long configuration,
            @Param("drafts") boolean drafts,
            @Param("word") String word,
            @Param("progress") String progress,
            @Param("pendingImpact") Boolean pendingImpact,
            @Param("pendingKind") String pendingKind,
            @Param("ascending") boolean ascending);

    /**
     * 当前字段导航与审核状态在同一结构修订内计算。
     */
    @Select(
            """
            WITH columns AS (
                SELECT c.column_id,c.id AS revision_id,c.source_name AS name,c.native_type,c.nullable,c.source_comment,
                    f.ordinal,g.id AS item_id,
                    EXISTS(SELECT 1 FROM gov_impact_task p WHERE p.item_id=g.id AND p.state!='REVIEWED') AS pending_review,
                    EXISTS(SELECT 1 FROM gov_gap q WHERE q.item_id=g.id AND q.state='OPEN') AS open_gap,
                    (g.head_revision_id IS NOT NULL AND g.head_revision_id=g.last_confirmed_revision_id) AS confirmed,
                    g.head_revision_id IS NOT NULL AS drafted,
                    EXISTS(SELECT 1 FROM gov_usage_control u JOIN gov_item t ON t.id=u.item_id
                           WHERE u.blocked AND t.source_id=#{source} AND t.object_id=#{object}
                             AND (t.kind='TABLE' OR t.id=g.id)) AS blocked
                FROM ds_object_column f JOIN ds_column_revision c ON c.id=f.column_revision_id
                  LEFT JOIN gov_item g ON g.source_id=#{source} AND g.kind='COLUMN' AND g.target_id=c.column_id
                WHERE f.object_revision_id=#{revision}
            )
            SELECT column_id,revision_id,name,native_type,nullable,source_comment,pending_review,blocked,
                   IF(confirmed AND NOT pending_review AND NOT open_gap,'CONFIRMED',IF(drafted,'DRAFT','UNSTARTED')) AS state
            FROM columns
            WHERE (#{word}='' OR LOCATE(LOWER(#{word}),LOWER(name))>0
                   OR LOCATE(LOWER(#{word}),LOWER(COALESCE(source_comment,'')))>0)
              AND (#{state}='ALL'
                   OR (#{state}='CONFIRMED' AND confirmed AND NOT pending_review AND NOT open_gap)
                   OR (#{state}='PENDING' AND (NOT confirmed OR pending_review OR open_gap)))
            ORDER BY ordinal,column_id
            """)
    Page<Column> columns(
            Page<Column> page,
            @Param("source") long source,
            @Param("object") long object,
            @Param("revision") long revision,
            @Param("word") String word,
            @Param("state") String state);

    /**
     * 按当前草稿与最后确认仍实际采用的资料反查，历史引用不生成新工作。
     */
    @Select(
            """
            SELECT DISTINCT e.* FROM gov_evidence_ref e JOIN gov_item_revision v ON v.id=e.item_revision_id
            JOIN gov_item i ON i.id=v.item_id AND (i.head_revision_id=v.id OR i.last_confirmed_revision_id=v.id)
            WHERE e.source_id=#{source} AND e.note_id=#{note}
            """)
    List<GovernanceEvidencePO> currentNoteReferences(@Param("source") long source, @Param("note") long note);

    /**
     * 当前定义已采用的建议实际取用资料，但没有可以定位影响的确切引用。
     */
    @Select(
            """
            SELECT EXISTS (
                SELECT 1 FROM gov_item i
                JOIN gov_item_revision v ON v.item_id=i.id
                    AND (i.head_revision_id=v.id OR i.last_confirmed_revision_id=v.id)
                JOIN gov_suggestion s ON s.id=v.suggestion_id
                JOIN gov_generation_input n ON n.request_id=s.request_id
                WHERE i.source_id=#{source} AND n.note_id=#{note} AND n.state='USED'
                  AND NOT EXISTS (SELECT 1 FROM gov_evidence_ref e
                      WHERE e.item_revision_id=v.id AND e.note_id=n.note_id)
            )
            """)
    boolean hasUnmappedNoteUse(@Param("source") long source, @Param("note") long note);

    /**
     * 结构变化沿已确认对象或字段的显式引用定向传播。
     */
    @Select(
            """
            <script>
            SELECT DISTINCT i.* FROM gov_evidence_ref e JOIN gov_item_revision v ON v.id=e.item_revision_id
            JOIN gov_item i ON i.id=v.item_id AND (i.head_revision_id=v.id OR i.last_confirmed_revision_id=v.id)
            WHERE e.source_id=#{source} AND e.kind='STRUCTURE' AND e.object_id=#{object}
            <if test="column != null">AND e.column_id=#{column}</if>
            </script>
            """)
    List<GovernanceItemPO> structuralDependents(
            @Param("source") long source, @Param("object") long object, @Param("column") Long column);

    /**
     * 同一对象的已治理条目，移除表时仍保留各字段历史。
     */
    @Select("SELECT * FROM gov_item WHERE source_id=#{source} AND object_id=#{object} ORDER BY id")
    List<GovernanceItemPO> items(@Param("source") long source, @Param("object") long object);

    /**
     * 读取已通过来源或复核记录定位的条目。
     */
    @Select("SELECT * FROM gov_item WHERE id=#{item}")
    GovernanceItemPO item(long item);

    /**
     * 以追加内容或确认的实际发生时间提供统一的历史页。
     */
    @Select(
            """
            <script>
            SELECT * FROM (
                SELECT 'REVISION' AS kind,id,item_id,id AS revision_id,revision,previous_revision_id,
                       changed_by AS actor,created_at AS occurred_at,NULL AS action,snapshot_id,
                       object_revision_id,column_revision_id FROM gov_item_revision WHERE item_id=#{item}
                UNION ALL
                SELECT 'CONFIRMATION',a.id,a.item_id,a.item_revision_id,v.revision,v.previous_revision_id,
                       a.confirmed_by,a.confirmed_at,a.action,a.snapshot_id,a.object_revision_id,a.column_revision_id
                FROM gov_confirmation a LEFT JOIN gov_item_revision v ON v.id=a.item_revision_id
                WHERE a.item_id=#{item}
            ) h
            <if test="revision != null">WHERE h.revision=#{revision}</if>
            ORDER BY occurred_at DESC,id DESC,kind
            </script>
            """)
    Page<History> history(Page<History> page, @Param("item") long item, @Param("revision") Integer revision);

    /**
     * 工作台一行表的实际结构和治理汇总。
     *
     * @param observationId 成功观察编号
     * @param objectId 对象稳定身份，未核实时为空
     * @param generation 对象世代
     * @param namespace 数据库目录或 Schema
     * @param name 源对象名称
     * @param objectType 来源对象类型
     * @param sourceComment 来源原注释
     * @param businessName 可披露的治理名称
     * @param purpose 可披露的治理用途
     * @param totalCount 当前对象和字段的必要项数
     * @param confirmedCount 当前已经有效确认的项数
     * @param tableConfirmed 表说明是否已完成治理
     * @param hasPendingImpact 是否存在来源复核
     * @param sourcePending 当前表存在待核实事实
     * @param progress 表及字段的实际进度
     */
    record Table(
            long observationId,
            Long objectId,
            Integer generation,
            String namespace,
            String name,
            String objectType,
            String sourceComment,
            String businessName,
            String purpose,
            int totalCount,
            int confirmedCount,
            boolean tableConfirmed,
            boolean hasPendingImpact,
            boolean sourcePending,
            String progress) {}

    /**
     * 同一结构修订下的字段和当前完成情况。
     *
     * @param columnId 稳定字段编号
     * @param revisionId 字段结构修订
     * @param name 字段源端名称
     * @param nativeType 原生类型
     * @param nullable 是否允许空值
     * @param sourceComment 原注释
     * @param pendingReview 有来源复核待处理
     * @param blocked 当前禁止使用
     * @param state 当前归一的治理状态
     */
    record Column(
            long columnId,
            long revisionId,
            String name,
            String nativeType,
            boolean nullable,
            String sourceComment,
            boolean pendingReview,
            boolean blocked,
            String state) {}

    /**
     * 从两个独立时间序列整理的历史记录。
     *
     * @param kind 实际记录类型
     * @param id 来源事件编号
     * @param itemId 历史稳定条目
     * @param revisionId 当时采用的内容修订
     * @param revision 业务修订序号
     * @param previousRevisionId 实际前修订
     * @param actor 保存或确认的操作者
     * @param occurredAt 实际操作时刻
     * @param action 确认或移除动作
     * @param snapshotId 结构依据的快照
     * @param objectRevisionId 结构依据的对象修订
     * @param columnRevisionId 结构依据的字段修订
     */
    record History(
            String kind,
            long id,
            long itemId,
            Long revisionId,
            Integer revision,
            Long previousRevisionId,
            long actor,
            LocalDateTime occurredAt,
            String action,
            long snapshotId,
            long objectRevisionId,
            Long columnRevisionId) {}
}
