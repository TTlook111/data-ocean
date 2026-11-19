package com.dataocean.module.governance.entity.vo;

import java.util.List;

/**
 * 仅返回当次允许的资料身份和核对凭据。
 *
 * @param sourceId 当前源身份
 * @param objectId 稳定对象
 * @param notes 可披露的资料清单
 * @param noteVisibility 资料清单的可见性
 * @param reviewToken 本次范围的安全签名凭据
 */
public record GenerationPreviewVO(
        String sourceId,
        String objectId,
        List<GenerationNoteVO> notes,
        String noteVisibility,
        String reviewToken) {}
