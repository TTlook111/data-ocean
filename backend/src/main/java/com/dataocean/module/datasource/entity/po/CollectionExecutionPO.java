package com.dataocean.module.datasource.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.ToString;

/**
 * ds_collection_execution 单表持久化数据。
 */
@Data
@TableName("ds_collection_execution")
public class CollectionExecutionPO {

    /**
     * 源端执行登记编号。
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 执行所属采集任务。
     */
    private String taskId;

    /**
     * 源端执行的当前观察状态。
     */
    private String state;

    /**
     * 受控的源侧会话定位密文。
     */
    @ToString.Exclude
    private byte[] locatorEnvelope;
}
