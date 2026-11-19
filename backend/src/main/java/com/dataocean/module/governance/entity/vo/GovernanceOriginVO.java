package com.dataocean.module.governance.entity.vo;

/**
 * 来源变化的获准定位信息。
 *
 * @param changeId 结构变化编号
 * @param factState 结构事实结论
 * @param noteChangeId 资料复核编号
 * @param noteId 资料编号
 * @param beforeRevision 变化前资料修订
 * @param afterRevision 变化后资料修订
 */
public record GovernanceOriginVO(
        String changeId,
        String factState,
        String noteChangeId,
        String noteId,
        Integer beforeRevision,
        Integer afterRevision) {}
