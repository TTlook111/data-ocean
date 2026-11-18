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
