package com.dataocean.module.iam.entity.dto;

import com.dataocean.module.iam.entity.vo.EntityVersionRefVO;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
public final class DepartmentMoveChildrenProposalDTO {

    /**
     * 批量迁移的目标对象及预览时版本。
     */
    @NotNull
    @Valid
    private EntityVersionRefVO destination;

    /**
     * 当前节点的直属子节点，顺序由服务端确定。
     */
    @NotNull
    private List<@Valid EntityVersionRefVO> children;

    /**
     * 本次管理动作的必填原因，不写入秘密凭据。
     */
    @NotBlank
    @CodePointLength(max = 500)
    private String reason;
}
