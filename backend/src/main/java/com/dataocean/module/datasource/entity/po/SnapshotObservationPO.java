package com.dataocean.module.datasource.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 记录候选快照中实际观测的结构及其核实关联。
 */
@Data
@TableName("ds_snapshot_object")
public class SnapshotObservationPO {

    /**
     * 观察行数据库主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 候选或成功快照的编号。
     */
    private Long snapshotId;

    /**
     * 同一快照内对象限定路径摘要。
     */
    private byte[] objectPathKey;

    /**
     * 真实源库命名空间，未使用时为空字符串。
     */
    private String namespace;

    /**
     * 源端实际对象名。
     */
    private String sourceName;

    /**
     * 表、视图或其他构造的类型。
     */
    private String objectType;

    /**
     * 本次观察的读取结果。
     */
    private String readStatus;

    /**
     * 源端可用的身份标识。
     */
    private String observedIdentity;

    /**
     * 读取所得结构摘要。
     */
    private byte[] definitionDigest;

    /**
     * 源端已读取结构的 JSON 内容。
     */
    private String candidateStructure;

    /**
     * 读取失败的业务原因。
     */
    private String failureCode;

    /**
     * 允许披露的失败详情。
     */
    private String failureMessage;

    /**
     * 已确认的稳定对象身份。
     */
    private Long objectId;

    /**
     * 正式结构修订编号。
     */
    private Long objectRevisionId;

    /**
     * 身份尚未确定时的核实变化引用。
     */
    private Long identityChangeId;
}
