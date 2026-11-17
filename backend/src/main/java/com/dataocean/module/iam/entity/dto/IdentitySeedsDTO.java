package com.dataocean.module.iam.entity.dto;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IdentitySeedsDTO {
    private List<PermissionPageSeedDTO> pages;
    private List<RoleSeedDTO> roles;
}
