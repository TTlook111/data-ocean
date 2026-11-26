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
 * 人员启用的预览与正式提交共用的非秘密资料，包含归属、角色和访问复核结论。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserEnableProposalDTO implements UserProposalDTO {

    /**
     * 本次完整提交的主部门及兼任选择。
     */
    @NotNull
    @Valid
    private MembershipSelectionDTO membership;

    /**
     * 当前角色、使用与授予集合及分配历史的访问依赖。
     */
    @NotNull
    private List<@Valid RoleVersionRefVO> roles;

    /**
     * 恢复账号前是否明确完成来源和保留配置复核。
     */
    @NotNull
    private Boolean reviewedAccess;

    /**
     * 本次管理动作的必填原因，不写入秘密凭据。
     */
    @NotBlank
    @CodePointLength(max = 500)
    private String reason;
}
