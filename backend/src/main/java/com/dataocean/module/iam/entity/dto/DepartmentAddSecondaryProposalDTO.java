package com.dataocean.module.iam.entity.dto;

import com.dataocean.module.iam.entity.vo.UserVersionRefVO;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 部门与成员的完整非秘密提案，提交时须重新核对版本和实际资格。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class DepartmentAddSecondaryProposalDTO {

    /**
     * 本次整批处理的完整人员与版本集合。
     */
    @NotNull
    @Size(min = 1, max = 100)
    private List<@Valid UserVersionRefVO> members;

    /**
     * 本次管理动作的必填原因，不写入秘密凭据。
     */
    @NotBlank
    @CodePointLength(max = 500)
    private String reason;
}
