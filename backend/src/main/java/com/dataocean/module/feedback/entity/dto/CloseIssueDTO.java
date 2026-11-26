package com.dataocean.module.feedback.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 结案前确认原问接收人及服务端产生的受控回告。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloseIssueDTO {

    /**
     * 调用者确认该操作时所见的事项版本。
     */
    @Positive
    private int expectedVersion;

    /**
     * 已解决或说明结案的类型编码。
     */
    @NotBlank
    private String outcome;

    /**
     * 说明结案时对应的具体原因编码。
     */
    @Size(max = 24)
    private String reason;

    /**
     * 结案人确认的内部处理结论。
     */
    @NotBlank
    @Size(max = 1000)
    private String conclusion;

    /**
     * 结案人核对的实际依据。
     */
    @NotBlank
    @Size(max = 1000)
    private String basis;

    /**
     * 预览确认时的原提问人编号。
     */
    @NotBlank
    private String recipientId;

    /**
     * 结案人核对的受控回告模板编码。
     */
    @NotBlank
    private String confirmedTemplate;

    /**
     * 多原问预览的接收人及模板确认值，单原问仍采用上面的字段。
     */
    @Size(max = 36)
    private String confirmedRecipients;
}
