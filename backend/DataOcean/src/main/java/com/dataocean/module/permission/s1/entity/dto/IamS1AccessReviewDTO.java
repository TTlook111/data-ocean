package com.dataocean.module.permission.s1.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 审批 IAM-SIMPLE-1 访问申请。
 * <p>
 * 通过范围必须是申请范围的子集，时间不超过申请时长；审批不顺手加 SQL/导出功能。
 * </p>
 * <p>
 * {@code approvedValidUntil} 在 <b>APPROVE</b> 时为必填：审批只能生成有期限的个人临时授权，
 * 不允许为空（空值会让生成的授权成为永久权限）。REJECT 不需要该字段，
 * 因此必填校验放在服务端按结论分支执行，而不是用 DTO 级 {@code @NotNull}。
 * </p>
 */
@Data
public class IamS1AccessReviewDTO {

    /** APPROVE 或 REJECT。 */
    @NotBlank(message = "请选择审批结论")
    private String decision;

    private List<String> approvedColumns = new ArrayList<>();

    /** APPROVE 必填的批准到期时间；上限为申请时长与系统上限中的较小值。 */
    private LocalDateTime approvedValidUntil;

    @Size(max = 500, message = "审批理由不能超过 500 字")
    private String reason;
}
