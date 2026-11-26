package com.dataocean.module.iam.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 公共契约的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class CsrfVO {

    /**
     * 写请求应携带 CSRF 的请求头名称。
     */
    private String headerName;

    /**
     * 仅供浏览器当前会话写请求使用的 CSRF 令牌。
     */
    @ToString.Exclude
    private String token;
}
