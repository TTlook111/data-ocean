package com.dataocean.module.governance.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

/**
 * 仅返回当次允许的资料身份和核对凭据。
 */
@Data
@AllArgsConstructor
public class GenerationPreviewVO {

    /**
     * 当前源身份。
     */
    private String sourceId;

    /**
     * 稳定对象。
     */
    private String objectId;

    /**
     * 可披露的资料清单。
     */
    private List<GenerationNoteVO> notes;

    /**
     * 资料清单的可见性。
     */
    private String noteVisibility;

    /**
     * 本次范围的安全签名凭据。
     */
    @ToString.Exclude
    private String reviewToken;
}
