package com.dataocean.module.fieldsemantic.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 同一快照下逐字段确认来源绑定；每项携带独立并发版本与核验依据。 */
@Data
public class FieldSemanticBindingBatchConfirmDTO {
    @NotEmpty
    @Size(max = 100)
    @Valid
    private List<Item> items;

    @Data
    public static class Item {
        @NotNull
        private Long columnMetaId;
        @NotNull
        private Integer expectedVersion;
        @NotBlank
        @Size(min = 1, max = 1000)
        private String evidence;
    }
}
