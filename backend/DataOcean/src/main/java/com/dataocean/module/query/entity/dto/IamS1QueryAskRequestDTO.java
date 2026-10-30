package com.dataocean.module.query.entity.dto;

import com.dataocean.module.permission.s1.entity.dto.IamS1TableRequestDTO;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** IAM-SIMPLE-1 独立查询入口请求；身份和时间均由 Java 服务端产生。 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class IamS1QueryAskRequestDTO {
    @NotBlank(message = "IAM-SIMPLE-1 协议版本不能为空")
    private String protocolVersion;

    @NotNull(message = "数据源 ID 不能为空")
    private Long datasourceId;

    @NotBlank(message = "问题不能为空")
    @Size(max = 500, message = "问题长度不能超过 500 字")
    private String question;

    private Long conversationId;

    @Valid
    private List<IamS1TableRequestDTO> tables = new ArrayList<>();
}
