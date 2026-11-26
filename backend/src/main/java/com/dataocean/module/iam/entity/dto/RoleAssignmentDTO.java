package com.dataocean.module.iam.entity.dto;

import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色与授予的明确传输结构，字段职责及校验与接口契约一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class RoleAssignmentDTO {

    /**
     * 提交者核对时的聚合版本，服务端以当前版本检查并发变化。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 当前角色、使用与授予集合及分配历史的访问依赖。
     */
    @NotNull
    private List<@Valid RoleVersionRefVO> roles;
}
