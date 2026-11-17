package com.dataocean.module.iam.entity.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.dataocean.module.iam.entity.vo.EntityVersionRefVO;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class DepartmentMoveChildrenProposalDTO {
    @NotNull
    @Valid
    private EntityVersionRefVO destination;
    @NotNull
    @Valid
    private List<EntityVersionRefVO> children;
    @NotBlank
    @Size(max=500)
    private String reason;
}
