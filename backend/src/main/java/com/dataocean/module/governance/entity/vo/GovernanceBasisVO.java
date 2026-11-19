package com.dataocean.module.governance.entity.vo;

/**
 * 标识本次操作依据的成功结构。
 *
 * @param snapshotId 固定成功快照
 * @param objectRevisionId 对象结构修订
 * @param columnRevisionId 字段结构修订，可空
 */
public record GovernanceBasisVO(
        String snapshotId,
        String objectRevisionId,
        String columnRevisionId) {}
