package com.dataocean.module.datasource.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据源列表只包含页面可披露的概要，不返回地址、账号或凭据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceRowVO {

    /**
     * 源标识。
     */
    private String id;

    /**
     * 展示名。
     */
    private String name;

    /**
     * 数据库类型。
     */
    private String databaseType;

    /**
     * 真实分项测试识别版本。
     */
    private String detectedVersion;

    /**
     * 技术联系人展示名。
     */
    private String technicalContact;

    /**
     * 配置生命周期。
     */
    private String lifecycle;

    /**
     * 最近采集状态。
     */
    private String latestTaskState;

    /**
     * 最近采集结束时间。
     */
    private Instant latestTaskEndedAt;

    /**
     * 当前目录提交时间。
     */
    private Instant lastSuccessfulAt;

    /**
     * 配置写入版本。
     */
    private int version;
}
