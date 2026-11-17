package com.dataocean.module.iam.entity.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.dataocean.module.iam.entity.vo.UserVersionRefVO;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class DepartmentAddSecondaryProposalDTO {
    @NotNull
    @Size(min=1,max=100)
    @Valid
    private List<UserVersionRefVO> members;
    @NotBlank
    @Size(max=500)
    private String reason;
}
