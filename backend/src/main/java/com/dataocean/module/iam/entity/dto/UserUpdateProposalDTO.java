package com.dataocean.module.iam.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 人员资料的完整非秘密提案，提交时须重新核对版本和实际资格。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class UserUpdateProposalDTO {

    /**
     * 人员当前显示姓名，不作为登录或授权依据。
     */
    @NotBlank
    @CodePointLength(max = 80)
    private String displayName;

    /**
     * 本次完整提交的主部门及兼任选择。
     */
    @NotNull
    @Valid
    private MembershipSelectionDTO membership;

    /**
     * 本人联系手机号，不作为独立登录或认证方式。
     */
    @CodePointLength(max = 32)
    private String phone;

    /**
     * 人员本人可维护的联系邮箱，不作为登录身份。
     */
    @Email
    @CodePointLength(max = 254)
    private String email;

    /**
     * 管理人员维护的备注，与本人联系方式区分。
     */
    @CodePointLength(max = 300)
    private String managementNote;
}
