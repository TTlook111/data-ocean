package com.dataocean.module.datasource.entity.vo;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

/**
 * 同源结构变化、历史证据和事实核实结果。
 *
 * @param changeId 变化记录编号
 * @param sourceId 所属源
 * @param sourceTaskId 发现这条事实的实际任务
 * @param target 可回查的对象限定路径
 * @param changeType 对象、字段或属性变化类别
 * @param attributeKind 属性标识
 * @param discoveredAt 发现时间
 * @param factState 实际事实状态
 * @param stage 当前处理阶段
 * @param version 当前变化版本
 * @param successorChangeId 后续核实编号
 * @param before 变化前的受控值
 * @param after 变化后的受控值
 * @param evidence 实际成功快照关联的来源事实
 * @param latestReview 上一次核实证据
 */
public record StructureChangeVO(
        String changeId,
        String sourceId,
        String sourceTaskId,
        String target,
        String changeType,
        String attributeKind,
        Instant discoveredAt,
        String factState,
        String stage,
        int version,
        String successorChangeId,
        JsonNode before,
        JsonNode after,
        JsonNode evidence,
        Review latestReview) {

    /**
     * 核实通过后可回看的证据。
     *
     * @param decision 结论
     * @param reviewedBy 操作者编号，系统自动动作时为空
     * @param evidenceText 支持结论的依据
     * @param reviewedAt 核实时间
     */
    public record Review(String decision, String reviewedBy, String evidenceText, Instant reviewedAt) {}
}
