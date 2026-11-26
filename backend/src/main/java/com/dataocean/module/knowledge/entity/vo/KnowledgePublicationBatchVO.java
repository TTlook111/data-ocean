package com.dataocean.module.knowledge.entity.vo;

import java.time.Instant;
import java.util.List;

/**
 * 同一批次的核对、实际指针和历史操作记录。
 */
public final class KnowledgePublicationBatchVO {

    /**
     * 类型分组以显式记录输出。
     */
    private KnowledgePublicationBatchVO() {}

    /**
     * @param taskId 原始单文档任务
     * @param title 冻结的正式标题
     * @param currentBindingId 读到的当前权威绑定
     * @param currentRevision 读到的当前修订
     * @param targetBindingId 回退目标或已核实的发布绑定，首次发布预检为空
     * @param targetRevision 待发布或待回退的确切修订
     * @param issue 当前不能办理的实际原因
     * @param paused 是否有会保留的范围暂停
     * @param publishedBindingId 回看时原发布固定的绑定
     * @param publishedRevision 回看时原发布固定的修订
     */
    public record Row(
            String taskId,
            String title,
            String currentBindingId,
            Integer currentRevision,
            String targetBindingId,
            Integer targetRevision,
            String issue,
            boolean paused,
            String publishedBindingId,
            Integer publishedRevision) {}

    /**
     * @param rows 必要范围逐项对照
     * @param reviewToken 全部成员通过后才签发的本次核对凭据
     */
    public record Preview(List<Row> rows, String reviewToken) {}

    /**
     * @param id 原回退命令的记录
     * @param actor 按当前权限办理回退的人员
     * @param reason 确认时的回退依据
     * @param at 实际切换时刻
     * @param verifiedAt 核实完整组合的时刻，待核实时为空
     */
    public record Rollback(String id, String actor, String reason, Instant at, Instant verifiedAt) {}

    /**
     * @param id 真实发布批次
     * @param status 当前批次或回退核实阶段
     * @param rows 本批固定目标和实际入口
     * @param rollback 本批的原回退及审计信息
     */
    public record Result(String id, String status, List<Row> rows, Rollback rollback) {}
}
