package com.dataocean.module.iam.entity.dto;

import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 人员资料的明确传输结构，字段职责及校验与接口契约一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NewUserProfileDTO {

    /**
     * 创建后稳定的登录名，按契约精确匹配。
     */
    @NotBlank
    @CodePointLength(max = 80)
    private String username;

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

    /**
     * 当前角色、使用与授予集合及分配历史的访问依赖。
     */
    @NotNull
    private List<@Valid RoleVersionRefVO> roles;
}
