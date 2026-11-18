package com.dataocean.module.datasource.entity.vo;

import com.dataocean.module.datasource.entity.dto.RuntimePolicyDTO;
import com.dataocean.module.datasource.entity.dto.SourceConfigurationDTO;

import java.time.Instant;

/**
 * 管理页面可查阅的当前配置和运行状态，秘密从未进入返回对象。
 *
 * @param source 复用数据源列表概要
 * @param configuration 可披露源配置
 * @param runtimePolicy 固定运行及定时采集参数
 * @param credentialConfigured 是否存在受控凭据
 * @param configurationRevision 当前配置修订序号
 * @param currentSnapshotId 当前成功快照
 * @param activeTaskId 同源在途任务
 * @param pendingCredentialChangeId 在途替换操作
 * @param schedule 实际计划状态
 */
public record SourceDetailVO(
        SourceRowVO source,
        SourceConfigurationDTO configuration,
        RuntimePolicyDTO runtimePolicy,
        boolean credentialConfigured,
        int configurationRevision,
        String currentSnapshotId,
        String activeTaskId,
        String pendingCredentialChangeId,
        Schedule schedule) {

    /**
     * 数据源定时计划的可披露读模型。
     *
     * @param state 计划当前呈现状态
     * @param paused 管理员是否已暂停后续计划
     * @param timeZone 平台业务时区
     * @param nextScheduledAt 依据当前计划推算的时间
     * @param staleSince 当前陈旧区间开始时间
     */
    public record Schedule(
            String state, boolean paused, String timeZone, Instant nextScheduledAt, Instant staleSince) {}
}
