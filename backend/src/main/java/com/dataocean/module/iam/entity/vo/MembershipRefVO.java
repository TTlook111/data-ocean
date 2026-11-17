package com.dataocean.module.iam.entity.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公共契约的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class MembershipRefVO {

    /**
     * 该记录的稳定标识，对外长整型以十进制字符串表达。
     */
    private String id;

    /**
     * 当前成员关系或展示引用所指向的部门。
     */
    private DepartmentRefVO department;

    /**
     * 该成员关系是否为人员唯一的当前主部门。
     */
    @JsonProperty("isPrimary")
    private boolean primary;

    /**
     * 该成员关系建立的 UTC 时刻。
     */
    private Instant joinedAt;
}
