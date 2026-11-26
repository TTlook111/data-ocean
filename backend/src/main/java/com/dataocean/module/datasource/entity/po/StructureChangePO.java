package com.dataocean.module.datasource.entity.po;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 用于校验来源归属并更新结构变化的可办状态。
 */
@Data
@TableName("ds_change")
public class StructureChangePO {

    /**
     * 结构变化编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 变化所属的来源编号。
     */
    private Long sourceId;

    /**
     * 发现变化的采集任务 UUID。
     */
    @TableField(select = false)
    private String discoveryTaskId;

    /**
     * 用作比较依据的前一成功快照。
     */
    @TableField(select = false)
    private Long beforeSnapshotId;

    /**
     * 本次发现变化的成功快照。
     */
    @TableField(select = false)
    private Long afterSnapshotId;

    /**
     * 本次快照中的对象观察。
     */
    @TableField(select = false)
    private Long afterObservationId;

    /**
     * 比较时作为依据的已确认对象。
     */
    @TableField(select = false)
    private Long comparisonObjectId;

    /**
     * 同次采集内标识变化的摘要。
     */
    @TableField(select = false)
    private byte[] changeKey;

    /**
     * 发生变化的对象、字段或约束类型。
     */
    @TableField(select = false)
    private String targetKind;

    /**
     * 本次变化对应的具体结构属性。
     */
    @TableField(select = false)
    private String attributeKind;

    /**
     * 变更前可核实的 JSON 值。
     */
    @TableField(select = false)
    private String beforeValue;

    /**
     * 变更后可核实的 JSON 值。
     */
    @TableField(select = false)
    private String afterValue;

    /**
     * 用于核实变化事实的 JSON 证据。
     */
    @TableField(select = false)
    private String evidence;

    /**
     * 原始变化前可复核的观察编号。
     */
    private Long beforeObservationId;

    /**
     * 取代本记录的后继变化编号。
     */
    private Long successorChangeId;

    /**
     * 确认可见性恢复的实际快照。
     */
    private Long visibilityRestoredSnapshotId;

    /**
     * 结构变化当前办理阶段。
     */
    private String stage;

    /**
     * 结构变化当前事实结论。
     */
    private String factState;

    /**
     * 核实后的变化类型。
     */
    private String changeType;

    /**
     * 已确认的稳定对象身份。
     */
    private Long objectId;

    /**
     * 变化办理的乐观修订。
     */
    private Integer version;

    /**
     * 结构变化最近一次实际业务变更的数据库 UTC 毫秒时刻。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
