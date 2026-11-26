package com.dataocean.module.feedback.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 复验者登记当前生效场景的期望、实际结果和核对依据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VerifyIssueDTO {

    /**
     * 操作人核对时所见的事项版本。
     */
    @Positive
    private int expectedVersion;

    /**
     * 本次判断采用的受控结论。
     */
    @NotBlank
    private String decision;

    /**
     * 复验人实际核对的业务场景。
     */
    @NotBlank
    @Size(max = 1000)
    private String testScope;

    /**
     * 本次复验预期的行为或结果。
     */
    @NotBlank
    @Size(max = 500)
    private String expected;

    /**
     * 本次复验实际观察到的结果。
     */
    @NotBlank
    @Size(max = 1000)
    private String actual;

    /**
     * 支撑复验结论的数据及检查依据。
     */
    @NotBlank
    @Size(max = 1000)
    private String dataBasis;

    /**
     * 复验使用的实际只读问数轮次编号。
     */
    @Size(max = 36)
    private String recheckRequestId;
}
