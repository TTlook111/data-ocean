package com.dataocean.module.iam.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 部门成员复用去重人员摘要，额外保留本次范围实际命中的主兼任关系。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
public final class DepartmentMemberVO extends UserRowVO {

    /**
     * 本次组织范围内实际命中的全部主兼任关系，人员只占一行。
     */
    private List<MembershipRefVO> matchingMemberships;
}
