package com.dataocean.module.iam.entity.dto;

import com.dataocean.module.iam.entity.vo.UserVersionRefVO;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 公共契约的明确传输结构，字段职责及校验与接口契约一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class AddSecondaryMembersDTO {

    /**
     * 提交者核对时的聚合版本，服务端以当前版本检查并发变化。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

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

    /**
     * 绑定操作者、完整提案、当前版本和到期时间的签名凭据。
     */
    @NotBlank
    @ToString.Exclude
    private String reviewToken;
}
