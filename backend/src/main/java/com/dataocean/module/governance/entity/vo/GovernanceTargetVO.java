package com.dataocean.module.governance.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 稳定定位一个表说明或字段。
 */
@Data
@AllArgsConstructor
public class GovernanceTargetVO {

    /**
     * 所属对象编号。
     */
    private String objectId;

    /**
     * 条目类型。
     */
    private String kind;

    /**
     * 对象或字段目标编号。
     */
    private String targetId;
}
