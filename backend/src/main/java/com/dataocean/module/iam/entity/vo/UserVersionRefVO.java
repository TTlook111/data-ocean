package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class UserVersionRefVO {
    @NotBlank
    @Pattern(regexp="[1-9][0-9]{0,18}")
    private String userId;
    @NotNull
    @Min(1)
    private Integer version;
}
