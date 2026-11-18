package com.dataocean.module.datasource.entity.vo;

import java.time.Instant;

/**
 * 数据源列表只包含页面可披露的概要，不返回地址、账号或凭据。
 *
 * @param id 源标识
 * @param name 展示名
 * @param databaseType 数据库类型
 * @param detectedVersion 真实分项测试识别版本
 * @param technicalContact 技术联系人展示名
 * @param lifecycle 配置生命周期
 * @param latestTaskState 最近采集状态
 * @param latestTaskEndedAt 最近采集结束时间
 * @param lastSuccessfulAt 当前目录提交时间
 * @param version 配置写入版本
 */
public record SourceRowVO(
        String id,
        String name,
        String databaseType,
        String detectedVersion,
        String technicalContact,
        String lifecycle,
        String latestTaskState,
        Instant latestTaskEndedAt,
        Instant lastSuccessfulAt,
        int version) {}
