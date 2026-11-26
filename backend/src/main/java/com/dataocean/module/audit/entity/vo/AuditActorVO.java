package com.dataocean.module.audit.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 历史审计的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class AuditActorVO {

    /**
     * 当前引用或统计对象的业务类别。
     */
    private String kind;

    /**
     * 当前人员的稳定标识，客户端自报值不能替代登录身份。
     */
    private String userId;

    /**
     * 创建后稳定的登录名，按契约精确匹配。
     */
    private String username;

    /**
     * 人员当前显示姓名，不作为登录或授权依据。
     */
    private String displayName;
}
