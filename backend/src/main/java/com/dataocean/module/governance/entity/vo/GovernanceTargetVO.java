package com.dataocean.module.governance.entity.vo;

/**
 * 稳定定位一个表说明或字段。
 *
 * @param objectId 所属对象编号
 * @param kind 条目类型
 * @param targetId 对象或字段目标编号
 */
public record GovernanceTargetVO(
        String objectId,
        String kind,
        String targetId) {}
