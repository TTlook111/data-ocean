package com.dataocean.module.iam.entity.dto;

import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色与授予的完整非秘密提案，提交时须重新核对版本和实际资格。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class UserProtectedRolesProposalDTO {

    /**
     * 当前角色、使用与授予集合及分配历史的访问依赖。
     */
    @NotNull
    private List<@Valid RoleVersionRefVO> roles;

    /**
     * 本次管理动作的必填原因，不写入秘密凭据。
     */
    @NotBlank
    @CodePointLength(max = 500)
    private String reason;
}
