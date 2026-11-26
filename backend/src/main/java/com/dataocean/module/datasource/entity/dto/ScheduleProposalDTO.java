package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 计划控制核对阶段允许的说明，不涉及连接资料。
 */
@Data
public class ScheduleProposalDTO {

    /**
     * 暂停必填，恢复选填的业务原因。
     */
    @Size(max = 500)
    private String reason;
}
