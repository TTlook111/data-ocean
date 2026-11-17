package com.dataocean.module.iam.entity.dto;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubjectRefDTO {
    private String kind;
    private String id;
}
