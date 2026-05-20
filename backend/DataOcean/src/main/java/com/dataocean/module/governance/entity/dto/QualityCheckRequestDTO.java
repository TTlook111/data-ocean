package com.dataocean.module.governance.entity.dto;

import lombok.Data;

import java.util.List;

@Data
public class QualityCheckRequestDTO {

    private Long snapshotId;
    private List<String> dimensions;
    private List<String> tableNames;
}
