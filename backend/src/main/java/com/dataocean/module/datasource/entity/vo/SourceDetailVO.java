package com.dataocean.module.datasource.entity.vo;

import com.dataocean.module.datasource.entity.dto.RuntimePolicyDTO;
import com.dataocean.module.datasource.entity.dto.SourceConfigurationDTO;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理页面可查阅的当前配置和运行状态，秘密从未进入返回对象。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceDetailVO {

    /**
     * 复用数据源列表概要。
     */
    private SourceRowVO source;

    /**
     * 可披露源配置。
     */
    private SourceConfigurationDTO configuration;

    /**
     * 固定运行及定时采集参数。
     */
    private RuntimePolicyDTO runtimePolicy;

    /**
     * 是否存在受控凭据。
     */
    private boolean credentialConfigured;

    /**
     * 当前配置修订序号。
     */
    private int configurationRevision;

    /**
     * 当前成功快照。
     */
    private String currentSnapshotId;

    /**
     * 同源在途任务。
     */
    private String activeTaskId;

    /**
     * 在途替换操作。
     */
    private String pendingCredentialChangeId;

    /**
     * 实际计划状态。
     */
    private Schedule schedule;

    /**
     * 数据源定时计划的可披露读模型。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Schedule {

        /**
         * 计划当前呈现状态。
         */
        private String state;

        /**
         * 管理员是否已暂停后续计划。
         */
        private boolean paused;

        /**
         * 平台业务时区。
         */
        private String timeZone;

        /**
         * 依据当前计划推算的时间。
         */
        private Instant nextScheduledAt;

        /**
         * 当前陈旧区间开始时间。
         */
        private Instant staleSince;
    }
}
