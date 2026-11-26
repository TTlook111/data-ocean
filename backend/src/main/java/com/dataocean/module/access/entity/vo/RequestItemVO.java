package com.dataocean.module.access.entity.vo;

import com.dataocean.module.iam.entity.vo.UserOptionVO;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审批子项的受控披露。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RequestItemVO {

    /**
     * 子项编号。
     */
    private String id;

    /**
     * 已核定源。
     */
    private AccessSourceOptionVO source;

    /**
     * 当前公开办理状态。
     */
    private String state;

    /**
     * 当前可用的审批负责人。
     */
    private UserOptionVO assignee;

    /**
     * 最近实际办理人。
     */
    private UserOptionVO handler;

    /**
     * 实际办理时刻。
     */
    private Instant handledAt;

    /**
     * 分流原因或办理说明。
     */
    private String reason;

    /**
     * 当前子项实际办结时保存的规则。
     */
    private List<OutputRuleVO> rules;

    /**
     * 独立子项的资料覆盖，仅部分重提时存在。
     */
    private RequestApplicationVO application;
}
