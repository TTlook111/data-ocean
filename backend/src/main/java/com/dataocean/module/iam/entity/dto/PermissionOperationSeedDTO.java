package com.dataocean.module.iam.entity.dto;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PermissionOperationSeedDTO {
    private String id;
    private String name;
    private String purpose;
    private boolean protectedAbility;
}
