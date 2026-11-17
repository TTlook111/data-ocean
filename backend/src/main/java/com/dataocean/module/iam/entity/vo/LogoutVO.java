package com.dataocean.module.iam.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公共契约的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class LogoutVO {

    /**
     * 当前浏览器会话是否已结束，不代表所有后台任务取消。
     */
    private boolean loggedOut;
}
