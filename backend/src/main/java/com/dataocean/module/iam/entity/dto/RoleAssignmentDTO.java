package com.dataocean.module.iam.entity.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class RoleAssignmentDTO {
    @NotNull
    @Min(1)
    private Integer expectedVersion;
    @NotNull
    @Valid
    private List<RoleVersionRefVO> roles;
}
