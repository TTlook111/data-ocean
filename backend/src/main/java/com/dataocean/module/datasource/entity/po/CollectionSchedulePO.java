package com.dataocean.module.datasource.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * ds_collection_schedule 单表持久化数据。
 */
@Data
@TableName("ds_collection_schedule")
public class CollectionSchedulePO {

    /**
     * 计划所属数据源，一个源维护一个调度游标。
     */
    @TableId(type = IdType.INPUT)
    private Long sourceId;

    /**
     * 最后一次成功接收的计划周期序号。
     */
    private Long lastAdmittedCycleNo;

    /**
     * 时间窗口策略对应的当前调度修订。
     */
    private Integer revision;

    /**
     * 下次检查的数据库 UTC 时间。
     */
    private LocalDateTime nextCheckAt;
}
