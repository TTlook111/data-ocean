package com.dataocean.module.access.entity.vo;

import com.dataocean.module.iam.entity.vo.UserOptionVO;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 本人可回看的申请实际办理动作。
 */
@Data
@AllArgsConstructor
public class RequestActivityVO {

    /**
     * 历史办理动作所在的单据修订。
     */
    private int version;

    /**
     * 实际处理动作。
     */
    private String action;

    /**
     * 操作人身份。
     */
    private UserOptionVO actor;

    /**
     * 实际提交时刻。
     */
    private Instant createdAt;
}
