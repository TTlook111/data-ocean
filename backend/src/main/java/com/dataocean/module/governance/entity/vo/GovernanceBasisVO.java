package com.dataocean.module.governance.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 标识本次操作依据的成功结构。
 */
@Data
@AllArgsConstructor
public class GovernanceBasisVO {

    /**
     * 固定成功快照。
     */
    private String snapshotId;

    /**
     * 对象结构修订。
     */
    private String objectRevisionId;

    /**
     * 字段结构修订，可空。
     */
    private String columnRevisionId;
}
