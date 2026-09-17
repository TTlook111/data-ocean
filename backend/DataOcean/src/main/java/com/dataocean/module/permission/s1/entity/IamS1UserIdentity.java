package com.dataocean.module.permission.s1.entity;

import lombok.Data;

/** S1 可读取的账号身份最小事实。 */
@Data
public class IamS1UserIdentity {
    private Long id;
    private Long departmentId;
    private Integer status;
}
