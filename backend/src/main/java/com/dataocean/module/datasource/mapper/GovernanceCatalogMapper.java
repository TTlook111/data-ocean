package com.dataocean.module.datasource.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 提供治理办理所需的稳定字段身份与来源变化事实。
 */
public interface GovernanceCatalogMapper {

    /**
     * 用不可变对象修订查出该版的实际字段身份。
     */
    @Select("""
            SELECT c.column_id,c.id AS revision_id,c.source_name AS name,c.native_type,c.nullable,
                   c.source_comment,f.ordinal
            FROM ds_object_column f JOIN ds_column_revision c ON c.id=f.column_revision_id
            WHERE f.object_revision_id=#{revision} ORDER BY f.ordinal,c.column_id
            """)
    List<Column> columns(long revision);

    /**
     * 返回同源待核实总数，排除已有后继事实的记录。
     */
    @Select("""
            SELECT COUNT(*) FROM ds_change WHERE source_id=#{source} AND successor_change_id IS NULL
              AND fact_state='PENDING' AND (object_id=#{object} OR comparison_object_id=#{object})
            """)
    int pending(@Param("source") long source, @Param("object") long object);

    /**
     * 保存本次发布已确定的事实集合，人工核实时使用指定 ID 精确读取。
     */
    @Select("""
            <script>
            SELECT c.id,c.change_type,c.target_kind,c.attribute_kind,c.object_id,c.comparison_object_id,
                   c.before_snapshot_id,c.after_snapshot_id,c.before_observation_id,c.after_observation_id,
                   c.before_value,c.after_value,c.discovered_at
            FROM ds_change c JOIN ds_snapshot s ON s.id=c.after_snapshot_id AND s.status='SUCCEEDED'
            WHERE c.source_id=#{source} AND c.fact_state='CONFIRMED' AND c.successor_change_id IS NULL
            <choose>
              <when test="change != null">AND c.id=#{change}</when>
              <otherwise>AND c.discovery_task_id=#{task}</otherwise>
            </choose>
            ORDER BY c.id
            </script>
            """)
    List<Fact> facts(
            @Param("source") long source, @Param("task") String task, @Param("change") Long change);

    /**
     * 最后一次确认对象或字段的成功观察仅用于历史和办理移除。
     */
    @Select("""
            <script>
            SELECT o.id FROM ds_snapshot_object o JOIN ds_snapshot s ON s.id=o.snapshot_id
            LEFT JOIN ds_change_review r ON r.change_id=o.identity_change_id AND r.resolved_object_id IS NOT NULL
            WHERE s.source_id=#{source} AND s.status='SUCCEEDED'
              AND COALESCE(r.resolved_object_id,o.object_id)=#{object}
            <if test="column != null">
              AND EXISTS (SELECT 1 FROM ds_object_column f JOIN ds_column_revision v ON v.id=f.column_revision_id
                          WHERE f.object_revision_id=COALESCE(r.resolved_object_revision_id,o.object_revision_id)
                            AND v.column_id=#{column})
            </if>
            ORDER BY s.committed_at DESC,s.id DESC LIMIT 1
            </script>
            """)
    Long lastObservation(
            @Param("source") long source, @Param("object") long object, @Param("column") Long column);

    /**
     * 写入所属模块可回读的稳定影响编号。
     */
    @Insert("""
            INSERT INTO ds_change_impact_ref(change_id,ref_kind,ref_id,linked_at)
            VALUES(#{change},#{kind},#{ref},UTC_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE linked_at=linked_at
            """)
    int addImpact(@Param("change") long change, @Param("kind") String kind, @Param("ref") long ref);

    /**
     * 内容办理阶段独立于事实核实结论和核实版本。
     */
    @Update("""
            UPDATE ds_change SET stage=#{stage},updated_at=UTC_TIMESTAMP(3)
            WHERE source_id=#{source} AND id=#{change} AND fact_state='CONFIRMED' AND stage!=#{stage}
            """)
    int stage(@Param("source") long source, @Param("change") long change, @Param("stage") String stage);

    /**
     * 来源字段的固定结构修订。
     *
     * @param columnId 稳定字段编号
     * @param revisionId 当前对象修订的字段成员
     * @param name 来源字段名
     * @param nativeType 源数据库原生类型
     * @param nullable 是否允许空值
     * @param sourceComment 原始注释
     * @param ordinal 来源字段顺序
     */
    record Column(
            long columnId, long revisionId, String name, String nativeType, boolean nullable,
            String sourceComment, int ordinal) {}

    /**
     * 保留前后两侧原观察和值，供治理按各自快照定位。
     *
     * @param id 结构事实编号
     * @param changeType 事实类型
     * @param targetKind 目标类型
     * @param attributeKind 发生变化的结构属性
     * @param objectId 核实后对象身份
     * @param comparisonObjectId 原对象身份
     * @param beforeSnapshotId 比较基准快照
     * @param afterSnapshotId 发现变化的成功快照
     * @param beforeObservationId 前侧实际观察
     * @param afterObservationId 后侧实际观察
     * @param beforeValue 前侧结构值
     * @param afterValue 后侧结构值
     * @param discoveredAt 发现时的 UTC 时间
     */
    record Fact(
            long id, String changeType, String targetKind, String attributeKind, Long objectId,
            Long comparisonObjectId, long beforeSnapshotId, long afterSnapshotId, Long beforeObservationId,
            Long afterObservationId, String beforeValue, String afterValue, LocalDateTime discoveredAt) {}
}
