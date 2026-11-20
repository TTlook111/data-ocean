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
     * 服务端核对过的目标，次序与签名使用的范围一致。
     */
    private List<Target> targets;

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

    /**
     * 预览只披露核对范围，任务状态和执行编号在接收后产生。
     */
    @Data
    @AllArgsConstructor
    public static class Target {

        /** 当前表说明、字段或发现任务的锚定对象。 */
        private GovernanceTargetVO target;

        /** 发现类型，普通起草为空。 */
        private String discoveryKind;

        /** 候选发现不按定义内容路径拆分。 */
        private List<String> contentPaths;
    }
}
