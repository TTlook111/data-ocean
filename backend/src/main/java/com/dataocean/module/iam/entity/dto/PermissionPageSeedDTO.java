package com.dataocean.module.iam.entity.dto;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PermissionPageSeedDTO {
    private String id;
    private String group;
    private String name;
    private List<PermissionOperationSeedDTO> operations;
}
