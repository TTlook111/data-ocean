package com.dataocean.module.datasource.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 用于查询目录来源及采集结果的快照持久化字段。
 */
@Data
@TableName("ds_snapshot")
public class CollectionSnapshotPO {

    /**
     * 目录快照编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 数据源归属。
     */
    private long sourceId;

    /**
     * 固定配置编号。
     */
    private long configRevisionId;

    /**
     * 快照所属的已确认采集范围摘要。
     */
    @TableField(select = false)
    private byte[] scopeKey;

    /**
     * 用于判断两个快照是否可比较的协议摘要。
     */
    @TableField(select = false)
    private byte[] comparisonContractKey;

    /**
     * 接收任务时固定的源库适配器版本。
     */
    private String adapterVersion;

    /**
     * 接收任务时固定的目录比较协议版本。
     */
    private String comparisonVersion;

    /**
     * 快照当前为候选、成功、失败或取消。
     */
    private String status;

    /**
     * 上一次成功快照。
     */
    private Long previousSuccessSnapshotId;

    /**
     * 实际用于比较的成功快照。
     */
    private Long comparisonSnapshotId;

    /**
     * 分析结论。
     */
    private String analysis;

    /**
     * 本次新增变化数量。
     */
    private Long newChangeCount;

    /**
     * 快照成功提交的时间，UTC。
     */
    private LocalDateTime committedAt;

    /**
     * 分配快照的采集任务 UUID。
     */
    private String taskId;
}
