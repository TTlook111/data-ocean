package com.dataocean.module.governance.entity.vo;

import com.dataocean.module.governance.service.GovernanceDiscoveryWire.Basis;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.Pair;

import java.util.List;

import lombok.Data;

/**
 * 页面编辑当前候选，单独回看当时的证据和依赖。
 */
@Data
public class GovernanceCandidateVO {

    /** 候选稳定标识。 */
    private String id;

    /** 原发现任务标识。 */
    private String requestId;

    /** 实际结构采集快照。 */
    private String snapshotId;

    /** 关联或指标。 */
    private String kind;

    /** 当前人工编辑和审核版本。 */
    private int version;

    /** 待确认、草稿、已确认或待复核。 */
    private String state;

    /** 候选当前定义，受限时为空。 */
    private Definition definition;

    /** 经资料权限裁剪的实际结构和来源。 */
    private Basis basis;

    /**
     * @param name 业务名称
     * @param meaning 关联条件或指标含义
     * @param rule 完整键、数量关系或公式和适用筛选
     * @param usage 使用边界及风险
     * @param pairs 关联的实际有向字段对
     * @param fields 计算或关联使用的实际字段
     */
    public record Definition(String name, String meaning, String rule, String usage,
                             List<Pair> pairs, List<String> fields) {}
}
