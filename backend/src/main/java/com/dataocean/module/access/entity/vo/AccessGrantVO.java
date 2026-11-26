package com.dataocean.module.access.entity.vo;

import com.dataocean.module.iam.entity.vo.UserOptionVO;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 申请来源回看的历史与当前终态。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccessGrantVO {

    /**
     * 授权来源编号。
     */
    private String id;

    /**
     * 被授权人。
     */
    private UserOptionVO grantee;

    /**
     * 实际批准人。
     */
    private UserOptionVO approver;

    /**
     * 授权源。
     */
    private AccessSourceOptionVO source;

    /**
     * 原申请编号。
     */
    private String requestId;

    /**
     * 原申请当前修订，撤销时复核。
     */
    private int version;

    /**
     * 是否一次结束整组来源。
     */
    private boolean joint;

    /**
     * 当前有效、暂停或已结束。
     */
    private String state;

    /**
     * 实际批准时刻。
     */
    private Instant approvedAt;

    /**
     * 此来源实际生效时间。
     */
    private Instant startedAt;

    /**
     * 撤销或结束时间。
     */
    private Instant endedAt;

    /**
     * 曾发生的来源结束原因。
     */
    private String endReason;

    /**
     * 批准时冻结的规则。
     */
    private List<OutputRuleVO> rules;
}
