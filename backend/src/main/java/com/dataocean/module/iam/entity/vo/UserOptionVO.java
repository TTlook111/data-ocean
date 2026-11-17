package com.dataocean.module.iam.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 人员资料的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class UserOptionVO {

    /**
     * 该记录的稳定标识，对外长整型以十进制字符串表达。
     */
    private String id;

    /**
     * 创建后稳定的登录名，按契约精确匹配。
     */
    private String username;

    /**
     * 人员当前显示姓名，不作为登录或授权依据。
     */
    private String displayName;

    /**
     * 由生命周期、首次登录和待改密事实推导的展示状态。
     */
    private String displayStatus;

    /**
     * 该业务聚合的当前修订，写操作使用它检查并发变化。
     */
    private int version;

    /**
     * 该选项在当前动作和上限内是否可选择。
     */
    private boolean selectable;
}
