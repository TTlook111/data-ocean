package com.dataocean.module.governance.entity.vo;

import java.time.Instant;
import java.util.List;

/**
 * 内容修订或人工审核的历史序列。
 *
 * @param kind 发生记录类型
 * @param id 原历史记录编号
 * @param occurredAt 实际记录时间
 * @param actor 操作者编号
 * @param revisionId 涉及的内容修订
 * @param revision 修订序号
 * @param previousRevisionId 前一内容修订
 * @param action 若为审核则列出实际动作
 * @param structureBasis 当时的固定结构
 * @param detail 按指定修订读取的获准内容
 * @param changedPaths 本次实际变动的业务字段
 */
public record GovernanceHistoryRowVO(
        String kind,
        String id,
        Instant occurredAt,
        String actor,
        String revisionId,
        Integer revision,
        String previousRevisionId,
        String action,
        GovernanceBasisVO structureBasis,
        GovernanceRevisionVO detail,
        List<String> changedPaths) {}
