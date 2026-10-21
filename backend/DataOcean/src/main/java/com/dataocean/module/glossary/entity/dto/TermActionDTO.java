package com.dataocean.module.glossary.entity.dto;

import lombok.Data;

/** 审核动作必须携带用户实际审查的修订。 */
@Data
public class TermActionDTO {
    private Long revision;
    private boolean approved;
    private String reason;
}
