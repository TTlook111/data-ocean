package com.dataocean.module.governance.service;

import java.util.List;

/**
 * 候选发现使用同源目录标识与可复核证据，不传递业务数据行。
 */
public final class GovernanceDiscoveryWire {

    /**
     * 通过各个明确的记录声明模型适配边界。
     */
    private GovernanceDiscoveryWire() {}

    /**
     * @param kind 关联或指标
     * @param anchor 当前发起对象
     * @param tables 同源成功目录中的有界对象
     */
    public record Scope(String kind, String anchor, List<Table> tables) {}

    /**
     * @param id 对象字符串标识
     * @param name 该结构版本的限定名
     * @param fields 明确成员字段
     * @param foreignKeys 目录中核实的完整外键字段组
     */
    public record Table(String id, String name, List<Field> fields, List<List<Pair>> foreignKeys) {}

    /**
     * @param id 字段字符串标识
     * @param name 源端名称
     * @param type 技术类型
     * @param comment 来源注释
     * @param primary 是否为主键成员
     */
    public record Field(String id, String name, String type, String comment, boolean primary) {}

    /**
     * @param left 发起方向的一侧字段标识
     * @param right 目标侧字段标识
     */
    public record Pair(String left, String right) {}

    /**
     * @param name 候选业务名称
     * @param meaning 关联用途和条件或指标含义
     * @param rule 数量关系和完整键或计算公式及条件
     * @param usage 适用边界
     * @param pairs 同一关系的全部有序字段对
     * @param fields 指标使用的实际字段标识
     * @param evidence 模型明确采用的资料片段
     */
    public record Proposal(String name, String meaning, String rule, String usage, List<Pair> pairs,
                           List<String> fields, List<GovernanceGenerationWire.Evidence> evidence) {}

    /**
     * @param objectId 稳定表身份
     * @param objectRevision 固定的表结构修订
     * @param fieldId 稳定字段身份
     * @param fieldRevision 固定字段修订
     * @param label 实际限定名
     * @param type 该字段的技术类型
     */
    public record StructureRef(String objectId, String objectRevision, String fieldId, String fieldRevision,
                               String label, String type) {}

    /**
     * @param noteId 资料身份
     * @param revisionId 不可变资料修订
     * @param revision 修订序号
     * @param title 当时可回看的资料标题
     * @param attachmentId 引用附件时的固定编号
     * @param start 正文开头偏移
     * @param end 正文结束偏移
     */
    public record NoteRef(String noteId, String revisionId, int revision, String title,
                          String attachmentId, Integer start, Integer end) {}

    /**
     * @param structures 所有确切引用的字段和对象
     * @param notes 模型实际采用的资料
     * @param foreignKey 来自采集的完整外键
     */
    public record Basis(List<StructureRef> structures, List<NoteRef> notes, boolean foreignKey) {}
}
