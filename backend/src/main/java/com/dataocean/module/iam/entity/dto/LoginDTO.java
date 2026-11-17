package com.dataocean.module.iam.entity.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.ToString;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude={"password"})
public final class LoginDTO {
    @NotBlank
    @Size(max=80)
    private String username;
    @NotBlank
    @Size(max=32)
    private String password;
}
