package com.dataocean.module.iam.entity.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.ToString;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;

@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude={"initialPassword"})
public final class CreateUserDTO {
    @NotBlank
    @Size(max=80)
    private String username;
    @NotBlank
    @Size(max=80)
    private String displayName;
    @NotNull
    @Valid
    private MembershipSelectionDTO membership;
    @Size(max=32)
    private String phone;
    @Email
    @Size(max=254)
    private String email;
    @Size(max=300)
    private String managementNote;
    @NotNull
    @Valid
    private List<RoleVersionRefVO> roles;
    @NotBlank
    @Size(min=8,max=32)
    private String initialPassword;
    @NotBlank
    private String reviewToken;
}
