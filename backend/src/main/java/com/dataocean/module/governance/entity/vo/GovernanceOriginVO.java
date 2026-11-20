package com.dataocean.module.governance.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 来源变化的获准定位信息。
 */
@Data
@AllArgsConstructor
public class GovernanceOriginVO {

    /**
     * 结构变化编号。
     */
    private String changeId;

    /**
     * 结构事实结论。
     */
    private String factState;

    /**
     * 资料复核编号。
     */
    private String noteChangeId;

    /**
     * 资料编号。
     */
    private String noteId;

    /**
     * 变化前资料修订。
     */
    private Integer beforeRevision;

    /**
     * 变化后资料修订。
     */
    private Integer afterRevision;

    /**
     * 有资料查看权限时返回受影响修订的标题。
     */
    private String noteTitle;
}
