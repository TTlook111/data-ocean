package com.dataocean.module.governance.service.impl;

import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.iam.entity.po.OutboxPO;
import com.dataocean.module.iam.mapper.OutboxMapper;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 仅将稳定编号和业务版本写入公共可靠事件。
 */
@Component
@RequiredArgsConstructor
public class GovernanceEvents {

    /**
     * 共用可靠通知存储。
     */
    private final OutboxMapper outbox;

    /**
     * 按固定事件模型编码。
     */
    private final JsonCodec json;

    /**
     * 发布一次真实治理确认。
     */
    public void confirmed(long source, long item, int version, long confirm, Long revision, List<String> impacts,
            String command) {
        append("governance.item.confirmed", "governance_item", Long.toString(item), version, command,
                new Confirmed(Long.toString(source), Long.toString(item), Long.toString(confirm),
                        revision == null ? null : Long.toString(revision), impacts));
    }

    /**
     * 通知已经同步落库的使用禁止。
     */
    public void usage(long source, long item, int version, String command) {
        append("governance.usage.changed", "governance_item", Long.toString(item), version, command,
                new Usage(Long.toString(source), Long.toString(item), version, true));
    }

    /**
     * 通知任务已被业务事务接收。
     */
    public void requested(long source, String request, String command) {
        append("governance.generation.requested", "governance_generation", request, 1, command,
                new Requested(Long.toString(source), request));
    }

    /**
     * 落库事件，调用方保持实际业务事务开启。
     */
    private void append(String type, String aggregate, String id, int version, String command, Object payload) {
        outbox.append(new OutboxPO(UUID.randomUUID().toString(), type, 1, aggregate, id, version, command,
                json.write(payload), "PENDING", 0, null, null, null, null, null, null));
    }

    /**
     * 确认的稳定引用与实际覆盖的影响。
     *
     * @param sourceId 来源
     * @param itemId 条目
     * @param confirmationId 人工确认
     * @param revisionId 固定内容修订
     * @param resolvedImpactIds 实际结转的复核
     */
    private record Confirmed(
            String sourceId, String itemId, String confirmationId, String revisionId, List<String> resolvedImpactIds) {}

    /**
     * 已落实的权威使用限制。
     *
     * @param sourceId 来源
     * @param itemId 条目
     * @param controlVersion 使用控制修订
     * @param blocked 当前是否禁止
     */
    private record Usage(String sourceId, String itemId, int controlVersion, boolean blocked) {}

    /**
     * 已经提交的生成任务。
     *
     * @param sourceId 来源
     * @param requestId 原任务编号
     */
    private record Requested(String sourceId, String requestId) {}
}
