package com.dataocean.module.permission.s1.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 提交 IAM-SIMPLE-1 访问申请。
 * <p>
 * 第一期只支持“全部记录”（rowScope=ALL）与“按字段申请”，不接受手写 SQL 或记录条件原值。
 * </p>
 */
@Data
public class IamS1AccessRequestSubmitDTO {

    @NotNull(message = "请选择数据源")
    private Long datasourceId;

    @NotNull(message = "请选择已发布元数据快照")
    private Long metadataSnapshotId;

    @NotBlank(message = "请选择表")
    private String tableName;

    @NotEmpty(message = "请至少选择一个字段")
    private List<@NotBlank(message = "字段名不能为空") String> columns = new ArrayList<>();

    private String rowScope = "ALL";

    private LocalDateTime requestedValidUntil;

    @NotBlank(message = "请填写申请用途")
    @Size(max = 500, message = "申请用途不能超过 500 字")
    private String purpose;
}
