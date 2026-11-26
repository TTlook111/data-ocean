package com.dataocean.module.query.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 最近对话定位到其实际最新轮次。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryConversationVO {

    /**
     * 可稳定定位当前请求或记录的编号。
     */
    private String id;

    /**
     * 对话或正式知识来源的可见标题。
     */
    private String title;

    /**
     * 当前对话中最后一条实际问数轮次。
     */
    private String latestRequestId;

    /**
     * 权威状态最后变化的 UTC 时刻。
     */
    private Instant updatedAt;
}
