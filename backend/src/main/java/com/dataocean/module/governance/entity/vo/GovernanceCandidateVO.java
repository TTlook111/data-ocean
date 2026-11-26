package com.dataocean.module.governance.entity.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.dataocean.module.governance.entity.dto.MetricRulesDTO;
import com.dataocean.module.governance.entity.dto.RelationWindowDTO;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.Basis;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.Pair;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 页面编辑当前候选，单独回看当时的证据和依赖。
 */
@Data
public class GovernanceCandidateVO {

    /**
     * 候选稳定标识。
     */
    private String id;

    /**
     * 原发现任务标识。
     */
    private String requestId;

    /**
     * 实际结构采集快照。
     */
    private String snapshotId;

    /**
     * 关联或指标。
     */
    private String kind;

    /**
     * 当前人工编辑和审核版本。
     */
    private int version;

    /**
     * 待确认、草稿、已确认或待复核。
     */
    private String state;

    /**
     * 候选当前定义，受限时为空。
     */
    private Definition definition;

    /**
     * 经资料权限裁剪的实际结构和来源。
     */
    private Basis basis;

    /**
     * 候选的历史定义与可编辑业务内容。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Definition {

        /**
         * 业务名称。
         */
        private String name;

        /**
         * 关联条件或指标含义。
         */
        private String meaning;

        /**
         * 关联的完整键与数量关系，或指标的纯计算公式。
         */
        private String rule;

        /**
         * 使用边界及风险。
         */
        private String usage;

        /**
         * 关联的实际有向字段对。
         */
        private List<Pair> pairs;

        /**
         * 计算或关联使用的实际字段。
         */
        private List<String> fields;

        /**
         * 人工确认的指标适用条件下可作为默认口径。
         */
        private boolean defaultMetric;

        /**
         * 当前指标候选的结构化口径，随确认修订一起固定。
         */
        private MetricRulesDTO metricRules;

        /**
         * 本条关系确认后随修订固定的日期窗口。
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private RelationWindowDTO relationWindow;
    }
}
