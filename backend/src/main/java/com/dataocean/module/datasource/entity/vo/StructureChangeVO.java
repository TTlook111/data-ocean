package com.dataocean.module.datasource.entity.vo;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 同源结构变化、历史证据和事实核实结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StructureChangeVO {

    /**
     * 变化记录编号。
     */
    private String changeId;

    /**
     * 所属源。
     */
    private String sourceId;

    /**
     * 发现这条事实的实际任务。
     */
    private String sourceTaskId;

    /**
     * 可回查的对象限定路径。
     */
    private String target;

    /**
     * 对象、字段或属性变化类别。
     */
    private String changeType;

    /**
     * 属性标识。
     */
    private String attributeKind;

    /**
     * 发现时间。
     */
    private Instant discoveredAt;

    /**
     * 实际事实状态。
     */
    private String factState;

    /**
     * 当前处理阶段。
     */
    private String stage;

    /**
     * 当前变化版本。
     */
    private int version;

    /**
     * 后续核实编号。
     */
    private String successorChangeId;

    /**
     * 变化前的受控值。
     */
    private JsonNode before;

    /**
     * 变化后的受控值。
     */
    private JsonNode after;

    /**
     * 实际成功快照关联的来源事实。
     */
    private JsonNode evidence;

    /**
     * 上一次核实证据。
     */
    private Review latestReview;

    /**
     * 核实通过后可回看的证据。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Review {

        /**
         * 结论。
         */
        private String decision;

        /**
         * 操作者编号，系统自动动作时为空。
         */
        private String reviewedBy;

        /**
         * 支持结论的依据。
         */
        private String evidenceText;

        /**
         * 核实时间。
         */
        private Instant reviewedAt;
    }
}
