package com.dataocean.module.datasource.service;

import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.iam.entity.po.OutboxPO;
import com.dataocean.module.iam.mapper.OutboxMapper;

import java.util.UUID;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 保存与来源业务状态同事务的可靠事件，载荷只包含资源定位信息。
 */
@Component
@RequiredArgsConstructor
public class DatasourceEvents {

    /**
     * 公共可靠事件持久化。
     */
    private final OutboxMapper outbox;

    /**
     * 明确类型的事件 JSON 编码。
     */
    private final JsonCodec json;

    /**
     * 记录已接收的任务定位，避免通知回包丢失使任务消失。
     */
    public void accepted(long source, String task, String command, int version) {
        append("datasource.collection.requested", source, version, command, new TaskRef(Long.toString(source), task));
    }

    /**
     * 已有目录发生有效变更时通知消费者回读权威版本。
     */
    public void catalog(long source, Long snapshot, int version) {
        append(
                "datasource.catalog.changed",
                source,
                version,
                null,
                new CatalogRef(Long.toString(source), snapshot == null ? null : Long.toString(snapshot), version));
    }

    /**
     * 记录本段目录陈旧的发现时间，来源状态由消费者按版本回读。
     */
    public void stale(long source, int version, java.time.Instant since) {
        append("datasource.catalog.stale", source, version, null, new StaleRef(Long.toString(source), since));
    }

    /**
     * 仅在运行凭据已生效时发出新的配置版本。
     */
    public void replaced(long source, int version, String command) {
        availability(source, version, command, "CREDENTIAL_REPLACED");
    }

    /**
     * 记录当前可用性变更，实际读取方仍按权威源状态核验。
     */
    public void availability(long source, int version, String command, String change) {
        append(
                "datasource.availability.changed",
                source,
                version,
                command,
                new AvailabilityRef(Long.toString(source), version, change));
    }

    /**
     * 可用性变更的受控事件载荷。
     *
     * @param sourceId 来源编号
     * @param version 权威来源版本
     * @param reason 实际变更类型
     */
    private record AvailabilityRef(String sourceId, int version, String reason) {}

    /**
     * 已被采用的资料变更以稳定引用通知下游。
     */
    public void businessNote(long source, long note, int revision, String change) {
        append(
                "datasource.business-note.changed",
                source,
                revision,
                null,
                new BusinessNoteRef(Long.toString(source), Long.toString(note), revision, change));
    }

    /**
     * 仅保存资料、修订和实际变更类型的事件载荷。
     */
    private record BusinessNoteRef(String sourceId, String noteId, int revision, String change) {}

    /**
     * 当前陈旧提醒只包含来源及权威时间。
     *
     * @param sourceId 来源标识
     * @param staleSince 陈旧开始时间
     */
    private record StaleRef(String sourceId, java.time.Instant staleSince) {}

    /**
     * 构造公共 Outbox 记录，调用者必须已开启对应业务事务。
     */
    private void append(String type, long source, int version, String command, Object payload) {
        OutboxPO event = new OutboxPO();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(type);
        event.setAggregateType("datasource");
        event.setAggregateId(Long.toString(source));
        event.setAggregateVersion(version);
        event.setCommandId(command);
        event.setPayload(json.write(payload));
        outbox.append(event);
    }

    /**
     * 采集接收事件载荷。
     *
     * @param sourceId 来源编号
     * @param taskId 已登记的任务
     */
    private record TaskRef(String sourceId, String taskId) {}

    /**
     * 目录事件载荷。
     *
     * @param sourceId 来源编号
     * @param currentSnapshotId 当前成功快照
     * @param catalogVersion 当前公开目录版本
     */
    private record CatalogRef(String sourceId, String currentSnapshotId, int catalogVersion) {}
}
